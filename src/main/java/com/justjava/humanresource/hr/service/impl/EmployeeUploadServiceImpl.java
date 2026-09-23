package com.justjava.humanresource.hr.service.impl;

import com.justjava.humanresource.hr.dto.EmployeeUploadDTO;
import com.justjava.humanresource.employee.EmployeeCreationGateService;
import com.justjava.humanresource.hr.entity.*;
import com.justjava.humanresource.hr.repository.*;
import com.justjava.humanresource.hr.service.CsvParserService;
import com.justjava.humanresource.hr.service.EmployeeUploadService;
import com.justjava.humanresource.payroll.service.EmployeePositionHistoryService;
import com.justjava.humanresource.payroll.service.PayrollChangeOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.justjava.humanresource.core.enums.EmploymentStatus;
import com.justjava.humanresource.core.enums.RecordStatus;
import com.justjava.humanresource.hr.service.EmployeeService;
import com.justjava.humanresource.onboarding.entity.EmployeeOnboarding;
import com.justjava.humanresource.onboarding.enums.OnboardingStatus;
import com.justjava.humanresource.onboarding.repositories.EmployeeOnboardingRepository;
import com.justjava.humanresource.aau.keycloak.KeycloakAdminService;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class EmployeeUploadServiceImpl implements EmployeeUploadService {

    private final CsvParserService csvParserService;
    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final JobGradeRepository jobGradeRepository;
    private final JobStepRepository jobStepRepository;
    private final EmployeePositionHistoryService positionHistoryService;
    private final PayGroupRepository payGroupRepository;
    private final EmployeeService employeeService;
    private final EmployeeOnboardingRepository onboardingRepository;
    private final KeycloakAdminService keycloakAdminService;
    private final PayrollChangeOrchestrator payrollChangeOrchestrator;
    private final EmployeeBankDetailRepository employeeBankDetailRepository;
    private final EmployeeCreationGateService employeeCreationGateService;

    private final ApplicationContext applicationContext;

    @Value("${keycloak.realm}")
    private String realmName;

    private static final Long DEFAULT_DEPARTMENT_ID = 1L;


    @Override
    public void uploadEmployees(MultipartFile file, List<String> groups) {
        employeeCreationGateService.assertCanCreateEmployees(1L);

        if (groups == null || groups.isEmpty()) {
            throw new IllegalArgumentException("At least one group must be selected before uploading employees.");
        }

        List<EmployeeUploadDTO> records = csvParserService.parse(file);

        // Deduplicate by email within the CSV — last row for a given email wins.
        // Rows with a blank/null email are silently dropped.
        Map<String, EmployeeUploadDTO> deduped = new LinkedHashMap<>();
        for (EmployeeUploadDTO dto : records) {
            if (dto.getEmail() != null && !dto.getEmail().isBlank()) {
                String normalizedEmail = dto.getEmail().toLowerCase().trim();
                dto.setEmail(normalizedEmail);
                deduped.put(normalizedEmail, dto);
            }
        }

        // Load defaults once — needed by both create and grade/step resolution paths.
        Department department = departmentRepository.findById(DEFAULT_DEPARTMENT_ID)
                .orElseThrow(() -> new IllegalStateException("Department not found"));
        PayGroup payGroup = payGroupRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("PayGroup not found"));

        Map<String, JobGrade> gradeCache = new HashMap<>();
        Map<String, JobStep> stepCache   = new HashMap<>();

        List<Long> createdIds              = new ArrayList<>();
        List<Long> updatedIdsNeedingRecalc = new ArrayList<>();

        for (EmployeeUploadDTO dto : deduped.values()) {

            // Resolve grade + step only when both columns are present with non-blank values.
            boolean hasGradeAndGross = dto.hasColumn("grade") && dto.getGrade() != null
                    && dto.hasColumn("gross") && dto.getGross() != null;

            JobStep resolvedStep = null;
            if (hasGradeAndGross) {
                resolvedStep = resolveStep(dto, department, gradeCache, stepCache);
            }

            Optional<Employee> existingOpt = employeeRepository.findByEmail(dto.getEmail());

            if (existingOpt.isPresent()) {
                // ── UPDATE path ───────────────────────────────────────────────
                Long updatedId = applicationContext
                        .getBean(EmployeeUploadServiceImpl.class)
                        .updateSingleEmployee(existingOpt.get(), dto, resolvedStep);

                if (hasGradeAndGross) {
                    updatedIdsNeedingRecalc.add(updatedId);
                }

            } else {
                // ── CREATE path ───────────────────────────────────────────────
                // Skip silently if any required field for creation is absent.
                if (!hasAllRequiredFieldsForCreate(dto)) {
                    continue;
                }
                Long createdId = applicationContext
                        .getBean(EmployeeUploadServiceImpl.class)
                        .saveSingleEmployee(dto, department, payGroup, resolvedStep, groups);
                createdIds.add(createdId);
            }
        }

        // ── Payroll recalculation ─────────────────────────────────────────────
        for (Long id : createdIds) {
            try {
                payrollChangeOrchestrator.recalculateForEmployee(id, LocalDate.now());
            } catch (Exception e) {
                System.err.println("CSV upload: payroll recalculation failed for new employee ID "
                        + id + " — " + e.getMessage());
            }
        }
        for (Long id : updatedIdsNeedingRecalc) {
            try {
                payrollChangeOrchestrator.recalculateForEmployee(id, LocalDate.now());
            } catch (Exception e) {
                System.err.println("CSV upload: payroll recalculation failed for updated employee ID "
                        + id + " — " + e.getMessage());
            }
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  UPDATE existing employee — only touches fields whose columns were present
    //  in the CSV header and whose values are non-blank.
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long updateSingleEmployee(Employee employee, EmployeeUploadDTO dto, JobStep resolvedStep) {

        if (dto.hasColumn("firstname") && dto.getFirstName() != null) {
            employee.setFirstName(dto.getFirstName());
        }
        if (dto.hasColumn("secondname") && dto.getSecondName() != null) {
            employee.setLastName(dto.getSecondName());
        }
        if (resolvedStep != null) {
            employee.setJobStep(resolvedStep);
        }
        if (dto.hasColumn("tinnumber") && dto.getTinNumber() != null) {
            employee.setTinNumber(dto.getTinNumber());
        }
        if (dto.hasColumn("rsapin") && dto.getRsaPin() != null) {
            employee.setRsaPin(dto.getRsaPin());
        }
        if (dto.hasColumn("pfa") && dto.getPfa() != null) {
            employee.setPfa(dto.getPfa());
        }
        if (dto.hasColumn("ninnumber") && dto.getNinNumber() != null) {
            employee.setNinNumber(dto.getNinNumber());
        }
        if (dto.hasColumn("bvnnumber") && dto.getBvnNumber() != null) {
            employee.setBvnNumber(dto.getBvnNumber());
        }
        if (dto.hasColumn("phonenumber") && dto.getPhoneNumber() != null) {
            employee.setPhoneNumber(dto.getPhoneNumber());
        }
        if (dto.hasColumn("dateofhire") && dto.getDateOfHire() != null) {
            try {
                employee.setDateOfHire(LocalDate.parse(dto.getDateOfHire()));
            } catch (Exception e) {
                System.err.println("CSV upload: invalid dateOfHire '" + dto.getDateOfHire()
                        + "' for " + dto.getEmail() + " — skipping field");
            }
        }
        if (dto.hasColumn("nextofkinname") && dto.getNextOfKinName() != null) {
            employee.setNextOfKinName(dto.getNextOfKinName());
        }
        if (dto.hasColumn("nextofkinphonenumber") && dto.getNextOfKinPhoneNumber() != null) {
            employee.setNextOfKinPhoneNumber(dto.getNextOfKinPhoneNumber());
        }
        if (dto.hasColumn("nextofkinemail") && dto.getNextOfKinEmail() != null) {
            employee.setNextOfKinEmail(dto.getNextOfKinEmail());
        }
        if (dto.hasColumn("nextofkinaddress") && dto.getNextOfKinAddress() != null) {
            employee.setNextOfKinAddress(dto.getNextOfKinAddress());
        }
        if (dto.hasColumn("guarantorname") && dto.getGuarantorName() != null) {
            employee.setGuarantorName(dto.getGuarantorName());
        }
        if (dto.hasColumn("guarantorphonenumber") && dto.getGuarantorPhoneNumber() != null) {
            employee.setGuarantorPhoneNumber(dto.getGuarantorPhoneNumber());
        }
        if (dto.hasColumn("guarantoremail") && dto.getGuarantorEmail() != null) {
            employee.setGuarantorEmail(dto.getGuarantorEmail());
        }
        if (dto.hasColumn("guarantoraddress") && dto.getGuarantorAddress() != null) {
            employee.setGuarantorAddress(dto.getGuarantorAddress());
        }
        if (dto.hasColumn("guarantorninnumber") && dto.getGuarantorNinNumber() != null) {
            employee.setGuarantorNinNumber(dto.getGuarantorNinNumber());
        }
        if (dto.hasColumn("dateofbirth") && dto.getDateOfBirth() != null) {
            try {
                employee.setDateOfBirth(LocalDate.parse(dto.getDateOfBirth()));
            } catch (Exception e) {
                System.err.println("CSV upload: invalid dateOfBirth '" + dto.getDateOfBirth()
                        + "' for " + dto.getEmail() + " — skipping field");
            }
        }
        if (dto.hasColumn("gender") && dto.getGender() != null) {
            employee.setGender(dto.getGender());
        }
        if (dto.hasColumn("maritalstatus") && dto.getMaritalStatus() != null) {
            employee.setMaritalStatus(dto.getMaritalStatus());
        }
        if (dto.hasColumn("residentialaddress") && dto.getResidentialAddress() != null) {
            employee.setResidentialAddress(dto.getResidentialAddress());
        }
        if (dto.hasColumn("mission") && dto.getMission() != null) {
            employee.setMission(dto.getMission());
        }

        employeeRepository.save(employee);

        // Upsert bank detail when all three bank columns are present with non-blank values.
        boolean allBankColumnsPresent = dto.hasColumn("accountname")
                && dto.hasColumn("bankname")
                && dto.hasColumn("accountnumber");
        boolean allBankValuesPresent  = dto.getAccountName()   != null
                && dto.getBankName()      != null
                && dto.getAccountNumber() != null;

        if (allBankColumnsPresent && allBankValuesPresent) {
            // Deactivate existing active details, then insert a new primary record.
            employeeBankDetailRepository.deactivateAllByEmployeeId(employee.getId());
            EmployeeBankDetail bankDetail = EmployeeBankDetail.builder()
                    .employee(employee)
                    .accountName(dto.getAccountName())
                    .bankName(dto.getBankName())
                    .accountNumber(dto.getAccountNumber())
                    .primaryAccount(true)
                    .effectiveFrom(LocalDate.now())
                    .status(RecordStatus.ACTIVE)
                    .build();
            employeeBankDetailRepository.save(bankDetail);
        }

        return employee.getId();
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  CREATE new employee — unchanged from original except optional fields
    //  from the DTO are now applied when present.
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long saveSingleEmployee(EmployeeUploadDTO dto, Department department,
                                   PayGroup payGroup, JobStep step, List<String> groups) {
        if (dto.getEmail() != null) dto.setEmail(dto.getEmail().toLowerCase());

        Employee employee = new Employee();
        employee.setFirstName(dto.getFirstName());
        employee.setLastName(dto.getSecondName());
        employee.setDepartment(department);
        employee.setEmployeeNumber("EMP-" + System.currentTimeMillis());
        employee.setEmploymentStatus(EmploymentStatus.ONBOARDING);
        employee.setStatus(RecordStatus.ACTIVE);
        employee.setJobStep(step);
        employee.setPayGroup(payGroup);
        employee.setEmail(dto.getEmail());
        employee.setKpiEnabled(true);

        // Optional fields that may be present in the CSV
        if (dto.getPhoneNumber()   != null) employee.setPhoneNumber(dto.getPhoneNumber());
        if (dto.getTinNumber()     != null) employee.setTinNumber(dto.getTinNumber());
        if (dto.getRsaPin()        != null) employee.setRsaPin(dto.getRsaPin());
        if (dto.getPfa()           != null) employee.setPfa(dto.getPfa());
        if (dto.getNinNumber()     != null) employee.setNinNumber(dto.getNinNumber());
        if (dto.getBvnNumber()     != null) employee.setBvnNumber(dto.getBvnNumber());
        if (dto.getGender()        != null) employee.setGender(dto.getGender());
        if (dto.getMaritalStatus() != null) employee.setMaritalStatus(dto.getMaritalStatus());
        if (dto.getResidentialAddress() != null) employee.setResidentialAddress(dto.getResidentialAddress());
        if (dto.getMission()       != null) employee.setMission(dto.getMission());
        if (dto.getNextOfKinName()        != null) employee.setNextOfKinName(dto.getNextOfKinName());
        if (dto.getNextOfKinPhoneNumber() != null) employee.setNextOfKinPhoneNumber(dto.getNextOfKinPhoneNumber());
        if (dto.getNextOfKinEmail()       != null) employee.setNextOfKinEmail(dto.getNextOfKinEmail());
        if (dto.getNextOfKinAddress()     != null) employee.setNextOfKinAddress(dto.getNextOfKinAddress());
        if (dto.getGuarantorName()        != null) employee.setGuarantorName(dto.getGuarantorName());
        if (dto.getGuarantorPhoneNumber() != null) employee.setGuarantorPhoneNumber(dto.getGuarantorPhoneNumber());
        if (dto.getGuarantorEmail()       != null) employee.setGuarantorEmail(dto.getGuarantorEmail());
        if (dto.getGuarantorAddress()     != null) employee.setGuarantorAddress(dto.getGuarantorAddress());
        if (dto.getGuarantorNinNumber()   != null) employee.setGuarantorNinNumber(dto.getGuarantorNinNumber());

        if (dto.getDateOfHire() != null) {
            try { employee.setDateOfHire(LocalDate.parse(dto.getDateOfHire())); }
            catch (Exception e) { System.err.println("CSV upload: invalid dateOfHire '" + dto.getDateOfHire() + "' for " + dto.getEmail()); }
        }
        if (dto.getDateOfBirth() != null) {
            try { employee.setDateOfBirth(LocalDate.parse(dto.getDateOfBirth())); }
            catch (Exception e) { System.err.println("CSV upload: invalid dateOfBirth '" + dto.getDateOfBirth() + "' for " + dto.getEmail()); }
        }

        employee = employeeRepository.save(employee);

        String password   = employeeService.generateInitialPassword(employee);
        String keycloakId = keycloakAdminService.createUser(
                realmName,
                dto.getEmail(),
                dto.getEmail(),
                password,
                dto.getFirstName(),
                dto.getSecondName(),
                Map.of("employeeId", List.of(String.valueOf(employee.getId())))
        );
        employee.setKeycloakUserId(keycloakId);
        employee.setGroups(groups);

        for (String groupName : groups) {
            keycloakAdminService.addUserToGroup(realmName, keycloakId, groupName);
        }

        try {
            employeeService.changeEmploymentStatus(employee.getId(), EmploymentStatus.ACTIVE, LocalDate.now());
        } catch (Exception e) {
            System.err.println("CSV upload: employment status change failed for "
                    + dto.getEmail() + " — " + e.getMessage()
                    + ". Employee saved as ONBOARDING; payroll will recalculate after upload.");
        }

        EmployeeOnboarding onboarding = EmployeeOnboarding.builder()
                .employee(employee)
                .status(OnboardingStatus.INITIATED)
                .initiatedBy("CSV-UPLOAD")
                .build();
        onboardingRepository.save(onboarding);

        // Save bank details if all three fields are provided
        String  accountName   = dto.getAccountName();
        String  bankName      = dto.getBankName();
        String  accountNumber = dto.getAccountNumber();
        boolean hasBankDetails = accountName   != null && !accountName.isBlank()
                && bankName      != null && !bankName.isBlank()
                && accountNumber != null && !accountNumber.isBlank();

        if (hasBankDetails) {
            EmployeeBankDetail bankDetail = EmployeeBankDetail.builder()
                    .employee(employee)
                    .accountName(accountName)
                    .bankName(bankName)
                    .accountNumber(accountNumber)
                    .primaryAccount(true)
                    .effectiveFrom(LocalDate.now())
                    .status(RecordStatus.ACTIVE)
                    .build();
            employeeBankDetailRepository.save(bankDetail);
        }

        return employee.getId();
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * All five fields must be non-null/non-blank for a new employee to be created.
     * Missing any one causes the row to be silently skipped.
     */
    private boolean hasAllRequiredFieldsForCreate(EmployeeUploadDTO dto) {
        return dto.getFirstName()  != null && !dto.getFirstName().isBlank()
                && dto.getSecondName() != null && !dto.getSecondName().isBlank()
                && dto.getEmail()      != null && !dto.getEmail().isBlank()
                && dto.getGrade()      != null && !dto.getGrade().isBlank()
                && dto.getGross()      != null;
    }

    /**
     * Resolves (or creates) the {@link JobGrade} and {@link JobStep} for the given
     * DTO, using shared caches to avoid redundant DB round-trips within one upload.
     */
    private JobStep resolveStep(EmployeeUploadDTO dto, Department department,
                                Map<String, JobGrade> gradeCache,
                                Map<String, JobStep>  stepCache) {

        JobGrade jobGrade = gradeCache.computeIfAbsent(dto.getGrade(), gradeName ->
                jobGradeRepository.findByName(gradeName).orElseGet(() -> {
                    JobGrade g = new JobGrade();
                    g.setName(gradeName);
                    g.setDepartment(department);
                    return jobGradeRepository.save(g);
                }));

        BigDecimal monthlySalary = dto.getGross().divide(BigDecimal.valueOf(12), 5, RoundingMode.HALF_UP);
        String stepKey = dto.getGrade() + "|" + dto.getGross().toPlainString();

        return stepCache.computeIfAbsent(stepKey, k ->
                jobStepRepository.findByGrossSalaryAndJobGrade(monthlySalary, jobGrade)
                        .orElseGet(() -> {
                            JobStep s = new JobStep();
                            s.setJobGrade(jobGrade);
                            s.setGrossSalary(monthlySalary);
                            s.setName("STEP-" + dto.getGross());
                            return jobStepRepository.save(s);
                        }));
    }


    // ─────────────────────────────────────────────────────────────────────────
    //  Kept for backward compatibility — the controller's catch block imports
    //  this exception. It is no longer thrown by the upsert flow.
    // ─────────────────────────────────────────────────────────────────────────

    public static class DuplicateEmailUploadException extends RuntimeException {

        private final List<String> conflictingEmails;

        public DuplicateEmailUploadException(String message, List<String> conflictingEmails) {
            super(message);
            this.conflictingEmails = Collections.unmodifiableList(conflictingEmails);
        }

        public List<String> getConflictingEmails() {
            return conflictingEmails;
        }
    }
}