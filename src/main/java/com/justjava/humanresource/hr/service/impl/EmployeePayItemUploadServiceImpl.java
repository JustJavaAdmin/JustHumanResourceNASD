package com.justjava.humanresource.hr.service.impl;

import com.justjava.humanresource.core.enums.RecordStatus;
import com.justjava.humanresource.hr.dto.EmployeePayItemUploadDTO;
import com.justjava.humanresource.hr.entity.Employee;
import com.justjava.humanresource.hr.repository.EmployeeRepository;
import com.justjava.humanresource.hr.service.EmployeePayItemCsvParserService;
import com.justjava.humanresource.hr.service.EmployeePayItemUploadService;
import com.justjava.humanresource.hr.service.JobHrEmployeeAccessService;
import com.justjava.humanresource.payroll.entity.Allowance;
import com.justjava.humanresource.payroll.entity.AllowanceAttachmentRequest;
import com.justjava.humanresource.payroll.entity.Deduction;
import com.justjava.humanresource.payroll.entity.DeductionAttachmentRequest;
import com.justjava.humanresource.payroll.entity.EmployeeAllowance;
import com.justjava.humanresource.payroll.entity.EmployeeDeduction;
import com.justjava.humanresource.payroll.entity.EmployeeTaxRelief;
import com.justjava.humanresource.payroll.entity.TaxRelief;
import com.justjava.humanresource.payroll.entity.TaxReliefAttachmentRequest;
import com.justjava.humanresource.payroll.repositories.AllowanceRepository;
import com.justjava.humanresource.payroll.repositories.DeductionRepository;
import com.justjava.humanresource.payroll.repositories.EmployeeAllowanceRepository;
import com.justjava.humanresource.payroll.repositories.EmployeeDeductionRepository;
import com.justjava.humanresource.payroll.repositories.EmployeeTaxReliefRepository;
import com.justjava.humanresource.payroll.repositories.TaxReliefRepository;
import com.justjava.humanresource.payroll.service.PayrollSetupService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeePayItemUploadServiceImpl implements EmployeePayItemUploadService {

    private final EmployeePayItemCsvParserService parserService;
    private final EmployeeRepository employeeRepository;
    private final AllowanceRepository allowanceRepository;
    private final DeductionRepository deductionRepository;
    private final TaxReliefRepository taxReliefRepository;
    private final PayrollSetupService payrollSetupService;
    private final JobHrEmployeeAccessService jobHrEmployeeAccessService;

    // Needed to resolve existing effectiveFrom per employee+item
    private final EmployeeAllowanceRepository employeeAllowanceRepository;
    private final EmployeeDeductionRepository employeeDeductionRepository;
    private final EmployeeTaxReliefRepository employeeTaxReliefRepository;

    @Override
    @Transactional
    public UploadSummary uploadPayItems(MultipartFile file, LocalDate effectiveFrom, LocalDate effectiveTo) {
        LocalDate resolvedEffectiveFrom = effectiveFrom != null ? effectiveFrom : LocalDate.now();
        if (effectiveTo != null && effectiveTo.isBefore(resolvedEffectiveFrom)) {
            throw new IllegalArgumentException("Effective To date cannot be before Effective From date");
        }
        List<EmployeePayItemUploadDTO> rows = parserService.parse(file);
        if (rows.isEmpty()) {
            return new UploadSummary(0, 0);
        }

        Set<String> emails = rows.stream()
                .map(EmployeePayItemUploadDTO::getEmail)
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(e -> !e.isEmpty())
                .collect(Collectors.toSet());

        Map<String, Employee> employeeByEmail = employeeRepository.findByEmailIn(emails)
                .stream()
                .collect(Collectors.toMap(e -> e.getEmail().toLowerCase(), Function.identity(), (a, b) -> a));

        Map<String, Allowance> allowanceByCode = allowanceRepository
                .findByStatus(RecordStatus.ACTIVE, Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .collect(Collectors.toMap(a -> a.getCode().toUpperCase(), Function.identity(), (a, b) -> a));
        Map<String, Deduction> deductionByCode = deductionRepository
                .findByStatus(RecordStatus.ACTIVE, Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .collect(Collectors.toMap(d -> d.getCode().toUpperCase(), Function.identity(), (a, b) -> a));
        Map<String, TaxRelief> taxReliefByCode = taxReliefRepository
                .findByActiveTrue(Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .collect(Collectors.toMap(t -> t.getCode().toUpperCase(), Function.identity(), (a, b) -> a));

        List<RowError> errors = validateRows(rows, employeeByEmail, allowanceByCode, deductionByCode, taxReliefByCode);
        if (!errors.isEmpty()) {
            throw new PayItemUploadValidationException(errors, rows.size());
        }

        // All rows are validated at this point, so every row resolves to a real employee.
        Set<Long> employeeIds = employeeByEmail.values().stream()
                .map(Employee::getId)
                .collect(Collectors.toSet());

        // Pre-load existing employee pay items to resolve effectiveFrom
        // Key: employeeId -> list of existing records
        Map<Long, List<EmployeeAllowance>> existingAllowancesByEmployee = employeeIds.stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        id -> employeeAllowanceRepository.findByEmployeeId(id)
                ));
        Map<Long, List<EmployeeDeduction>> existingDeductionsByEmployee = employeeIds.stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        id -> employeeDeductionRepository.findByEmployeeId(id)
                ));
        Map<Long, List<EmployeeTaxRelief>> existingTaxReliefsByEmployee = employeeIds.stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        id -> employeeTaxReliefRepository.findByEmployeeId(id)
                ));

        Map<Long, List<AllowanceAttachmentRequest>> allowanceRequests = new LinkedHashMap<>();
        Map<Long, List<DeductionAttachmentRequest>> deductionRequests = new LinkedHashMap<>();
        Map<Long, List<TaxReliefAttachmentRequest>> taxReliefRequests = new LinkedHashMap<>();

        for (EmployeePayItemUploadDTO row : rows) {
            Employee employee = employeeByEmail.get(row.getEmail().trim().toLowerCase());
            Long employeeId = employee.getId();
            String type = row.getItemType().trim().toUpperCase();
            boolean overridden = true;

            if ("ALLOWANCE".equals(type)) {
                Allowance allowance = allowanceByCode.get(row.getItemCode().trim().toUpperCase());

                // Reuse existing effectiveFrom if this allowance is already attached, else use the selected date
                LocalDate itemEffectiveFrom = existingAllowancesByEmployee
                        .getOrDefault(employeeId, List.of())
                        .stream()
                        .filter(ea -> ea.getAllowance().getId().equals(allowance.getId())
                                && ea.getStatus() == RecordStatus.ACTIVE)
                        .map(EmployeeAllowance::getEffectiveFrom)
                        .findFirst()
                        .orElse(resolvedEffectiveFrom);

                AllowanceAttachmentRequest request = new AllowanceAttachmentRequest();
                request.setAllowanceId(allowance.getId());
                request.setOverridden(overridden);
                request.setOverrideAmount(row.getOverrideAmount());
                request.setEffectiveFrom(itemEffectiveFrom);
                request.setEffectiveTo(effectiveTo);
                allowanceRequests.computeIfAbsent(employeeId, k -> new ArrayList<>()).add(request);

            } else if ("DEDUCTION".equals(type)) {
                Deduction deduction = deductionByCode.get(row.getItemCode().trim().toUpperCase());

                LocalDate itemEffectiveFrom = existingDeductionsByEmployee
                        .getOrDefault(employeeId, List.of())
                        .stream()
                        .filter(ed -> ed.getDeduction().getId().equals(deduction.getId())
                                && ed.getStatus() == RecordStatus.ACTIVE)
                        .map(EmployeeDeduction::getEffectiveFrom)
                        .findFirst()
                        .orElse(resolvedEffectiveFrom);

                DeductionAttachmentRequest request = new DeductionAttachmentRequest();
                request.setDeductionId(deduction.getId());
                request.setOverridden(overridden);
                request.setOverrideAmount(row.getOverrideAmount());
                request.setEffectiveFrom(itemEffectiveFrom);
                request.setEffectiveTo(effectiveTo);
                deductionRequests.computeIfAbsent(employeeId, k -> new ArrayList<>()).add(request);

            } else {
                TaxRelief taxRelief = taxReliefByCode.get(row.getItemCode().trim().toUpperCase());

                LocalDate itemEffectiveFrom = existingTaxReliefsByEmployee
                        .getOrDefault(employeeId, List.of())
                        .stream()
                        .filter(et -> et.getTaxRelief().getId().equals(taxRelief.getId())
                                && et.getStatus() == RecordStatus.ACTIVE)
                        .map(EmployeeTaxRelief::getEffectiveFrom)
                        .findFirst()
                        .orElse(resolvedEffectiveFrom);

                TaxReliefAttachmentRequest request = new TaxReliefAttachmentRequest();
                request.setTaxReliefId(taxRelief.getId());
                request.setOverridden(overridden);
                request.setOverrideAmount(row.getOverrideAmount());
                request.setEffectiveFrom(itemEffectiveFrom);
                request.setEffectiveTo(effectiveTo);
                taxReliefRequests.computeIfAbsent(employeeId, k -> new ArrayList<>()).add(request);
            }
        }

        allowanceRequests.forEach(payrollSetupService::addAllowancesToEmployee);
        deductionRequests.forEach(payrollSetupService::addDeductionsToEmployee);
        taxReliefRequests.forEach(payrollSetupService::addTaxReliefsToEmployee);

        return new UploadSummary(rows.size(), rows.size());
    }

    private List<RowError> validateRows(
            List<EmployeePayItemUploadDTO> rows,
            Map<String, Employee> employeeByEmail,
            Map<String, Allowance> allowanceByCode,
            Map<String, Deduction> deductionByCode,
            Map<String, TaxRelief> taxReliefByCode
    ) {
        List<RowError> errors = new ArrayList<>();
        Set<String> duplicateGuard = new HashSet<>();

        for (EmployeePayItemUploadDTO row : rows) {
            String itemType = safe(row.getItemType()).toUpperCase();
            String itemCode = safe(row.getItemCode()).toUpperCase();

            if (row.getEmail() == null || row.getEmail().isBlank()) {
                errors.add(err(row, "email is required"));
                continue;
            }

            Employee employee = employeeByEmail.get(row.getEmail().trim().toLowerCase());
            if (employee == null) {
                errors.add(err(row, "Employee not found for email: " + row.getEmail().trim()));
                continue;
            }

            try {
                jobHrEmployeeAccessService.assertCanAccessEmployee(employee.getId());
            } catch (Exception ex) {
                errors.add(err(row, "No access to employee in this row"));
                continue;
            }

            if (!Set.of("ALLOWANCE", "DEDUCTION", "TAX_RELIEF").contains(itemType)) {
                errors.add(err(row, "itemType must be ALLOWANCE, DEDUCTION or TAX_RELIEF"));
                continue;
            }
            if (itemCode.isBlank()) {
                errors.add(err(row, "itemCode is required"));
                continue;
            }
            if (row.getOverrideAmount() == null || row.getOverrideAmount().compareTo(BigDecimal.ZERO) < 0) {
                errors.add(err(row, "overrideAmount must be provided and >= 0"));
                continue;
            }

            if ("ALLOWANCE".equals(itemType) && !allowanceByCode.containsKey(itemCode)) {
                errors.add(err(row, "Allowance code not found or inactive"));
                continue;
            }
            if ("DEDUCTION".equals(itemType) && !deductionByCode.containsKey(itemCode)) {
                errors.add(err(row, "Deduction code not found or inactive"));
                continue;
            }
            if ("TAX_RELIEF".equals(itemType) && !taxReliefByCode.containsKey(itemCode)) {
                errors.add(err(row, "Tax relief code not found or inactive"));
                continue;
            }

            String dedupeKey = employee.getId() + "|" + itemType + "|" + itemCode;
            if (!duplicateGuard.add(dedupeKey)) {
                errors.add(err(row, "Duplicate row detected for employee + item"));
            }
        }
        return errors;
    }

    private RowError err(EmployeePayItemUploadDTO row, String message) {
        return new RowError(
                row.getRowNumber(),
                safe(row.getEmail()),
                safe(row.getItemType()),
                safe(row.getItemCode()),
                message
        );
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record RowError(
            int rowNumber,
            String email,
            String itemType,
            String itemCode,
            String message
    ) {}

    public static class PayItemUploadValidationException extends RuntimeException {
        private final List<RowError> rowErrors;
        private final int totalRows;

        public PayItemUploadValidationException(List<RowError> rowErrors, int totalRows) {
            super("Pay item upload validation failed");
            this.rowErrors = Collections.unmodifiableList(rowErrors);
            this.totalRows = totalRows;
        }

        public List<RowError> getRowErrors() {
            return rowErrors;
        }

        public int getTotalRows() {
            return totalRows;
        }
    }
}