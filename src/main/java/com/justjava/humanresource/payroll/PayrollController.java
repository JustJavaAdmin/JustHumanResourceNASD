package com.justjava.humanresource.payroll;

import com.justjava.humanresource.core.config.AuthenticationManager;
import com.justjava.humanresource.hr.dto.CreatePayGroupCommand;
import com.justjava.humanresource.hr.entity.Employee;
import com.justjava.humanresource.hr.entity.PayGroup;
import com.justjava.humanresource.hr.service.JobHrEmployeeAccessService;
import com.justjava.humanresource.hr.service.SetupService;
import com.justjava.humanresource.onboarding.service.EmployeeOnboardingService;
import com.justjava.humanresource.payroll.dto.PastPayslipEmailRequest;
import com.justjava.humanresource.payroll.dto.PayslipEmailRequest;
import com.justjava.humanresource.payroll.dto.PayslipEmailResponse;
import com.justjava.humanresource.payroll.entity.*;
import com.justjava.humanresource.payroll.service.PayGroupService;
import com.justjava.humanresource.payroll.service.PaySlipService;
import com.justjava.humanresource.payroll.service.PayrollPeriodService;
import com.justjava.humanresource.payroll.service.PayrollSetupService;
import com.justjava.humanresource.utils.PaySlipEmailService;
import com.justjava.humanresource.payroll.dto.EmployeeGroupedReportDTO;
import com.justjava.humanresource.payroll.dto.EmployeeReportItemDTO;
import com.justjava.humanresource.payroll.dto.AllowanceGroupReportDTO;
import com.justjava.humanresource.payroll.dto.AllowanceReportLineDTO;
import com.justjava.humanresource.payroll.enums.EmployeeGroupBy;
import com.justjava.humanresource.payroll.report.services.ReportingService;
import com.justjava.humanresource.core.enums.PayrollRunStatus;
import com.justjava.humanresource.payroll.dto.FutureEmployeeAllowanceDTO;
import com.justjava.humanresource.payroll.dto.PayePensionGroupDTO;
import com.justjava.humanresource.payroll.dto.PayePensionLineDTO;

import java.math.RoundingMode;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import com.justjava.humanresource.workflow.dto.FlowableTaskDTO;
import com.justjava.humanresource.workflow.service.FlowableTaskService;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.Set;

@Controller
public class PayrollController {
    @Autowired
    PayGroupService payGroupService;

    @Autowired
    private PayrollPeriodService payrollPeriodService;

    @Autowired
    private SetupService setupService;

    @Autowired
    private PayrollSetupService payrollSetupService;

    @Autowired
    private PaySlipService paySlipService;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private EmployeeOnboardingService employeeOnboardingService;

    @Autowired
    private ReportingService reportingService;

    @Autowired
    private PaySlipEmailService paySlipEmailService;

    @Autowired
    FlowableTaskService flowableTaskService;

    @Autowired
    JobHrEmployeeAccessService jobHrEmployeeAccessService;



    @GetMapping("/payroll")
    public String getPayroll(Model model) {
        List<Employee> employees = employeeOnboardingService.getAllOnboardings();
        List<Allowance> allowances = payrollSetupService.getActiveAllowances();
        List<Deduction> deductions = payrollSetupService.getActiveDeductions();
        List<TaxRelief> taxReliefs = payrollSetupService.getActiveTaxReliefs();
        List<PayGroup> payGroups = payrollSetupService.getAllPayGroups();
        List<Allowance> taxableAllowances = allowances
                .stream()
                .filter(Allowance::isTaxable)
                .toList();
        List<Deduction> saturatoryDeductions = deductions
                .stream()
                .filter(Deduction::isStatutory)
                .toList();

        PayrollPeriod currentPeriod = payrollPeriodService.getCurrentPeriod(1L);
        model.addAttribute("payrollStatus", currentPeriod != null ? currentPeriod.getStatus() : null);
        model.addAttribute("currentPayrollPeriod", currentPeriod);
        model.addAttribute("allowances", allowances.size());
        model.addAttribute("employees", employees.size());
        model.addAttribute("taxableAllowances", taxableAllowances.size());
        model.addAttribute("saturatoryDeductions", saturatoryDeductions.size());
        model.addAttribute("deductions", deductions.size());
        model.addAttribute("taxReliefs", taxReliefs.size());
        model.addAttribute("payGroups", payGroups.size());
        model.addAttribute("title", "Payroll Management");
        model.addAttribute("subTitle", "Manage employee payroll, salary details, and payment history");
        return "payroll/main";
    }

    @GetMapping("/payroll/historical-adjustments")
    public String getHistoricalPayrollAdjustments(Model model) {
        model.addAttribute("employees", employeeOnboardingService.getAllOnboardings());
        model.addAttribute("closedPeriods", payrollPeriodService.getClosedPeriods(1L));
        model.addAttribute("title", "Historical Payroll Adjustments");
        model.addAttribute("subTitle", "Create and review versioned corrections for closed payroll periods");
        return "payroll/historical-adjustments";
    }

    @GetMapping("/payroll/items")
    public String getPayrollItems(Model model) {
        List<Allowance> allowances = payrollSetupService.getActiveAllowances();
        List<Deduction> deductions = payrollSetupService.getActiveDeductions();
        List<TaxRelief> taxReliefs = payrollSetupService.getActiveTaxReliefs();

        model.addAttribute("allowances", allowances);
        model.addAttribute("deductions", deductions);
        model.addAttribute("taxReliefs", taxReliefs);
        model.addAttribute("title", "Payroll Management");
        model.addAttribute("subTitle", "Manage employee payroll, salary details, and payment history");
        return "payroll/fragments/payroll-items";
    }

    @GetMapping("/payroll/pay-group")
    public String getPayGroup(Model model) {
        List<Deduction> deductions = payrollSetupService.getActiveDeductions();
        List<Allowance> allowances = payrollSetupService.getActiveAllowances();
        List<TaxRelief> taxReliefs = payrollSetupService.getActiveTaxReliefs();
        List<PayGroup> payGroups = payrollSetupService.getAllPayGroups();

        LocalDate currentDate = LocalDate.now();

        List<PayGroupFullViewDTO> payGroupFullList = payGroups.stream()
                .map(payGroup -> PayGroupFullViewDTO.builder()
                        .payGroup(payGroup)
                        .allowances(payGroupService.getAllAssignedAllowances(payGroup.getId(), currentDate))
                        .deductions(payGroupService.getAllAssignedDeductions(payGroup.getId(), currentDate))
                        .taxReliefs(payGroupService.getAllAssignedTaxReliefs(payGroup.getId(), currentDate))
                        .employees(payGroupService.getEmployees(payGroup.getId(), currentDate))
                        .build())
                .toList();

        model.addAttribute("taxReliefs", taxReliefs);
        model.addAttribute("payGroups", payGroupFullList);
        model.addAttribute("allowances", allowances);
        model.addAttribute("deductions", deductions);
        model.addAttribute("title", "Payroll Management");
        model.addAttribute("subTitle", "Manage employee payroll, salary details, and payment history");

        return "payroll/fragments/paygroups";
    }

    /**
     * API endpoint to get currently assigned pay items for a paygroup
     */
    @GetMapping("/api/paygroup/{payGroupId}/pay-items")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getPayGroupPayItems(@PathVariable Long payGroupId) {
        LocalDate currentDate = LocalDate.now();

        List<PayGroupAllowanceViewDTO> allowances = payGroupService.getAllAssignedAllowances(payGroupId, currentDate);
        List<PayGroupDeductionViewDTO> deductions = payGroupService.getAllAssignedDeductions(payGroupId, currentDate);
        List<PayGroupTaxReliefViewDTO> taxReliefs = payGroupService.getAllAssignedTaxReliefs(payGroupId, currentDate);

        return ResponseEntity.ok(Map.of(
                "allowances", allowances.stream().map(PayGroupAllowanceViewDTO::getAllowanceId).toList(),
                "deductions", deductions.stream().map(PayGroupDeductionViewDTO::getDeductionId).toList(),
                "taxReliefs", taxReliefs.stream().map(PayGroupTaxReliefViewDTO::getTaxReliefId).toList()
        ));
    }

    @PostMapping("/setup/paygroup/allowances")
    public String attachAllowancesToPayGroup(
            @RequestParam Long payGroupId,
            @RequestParam(required = false) List<Long> allowanceIds,
            @RequestParam Map<String, String> allParams,
            RedirectAttributes redirectAttributes) {

        // Soft-delete allowances that were unchecked (not in the submitted list)
        List<Long> submittedIds = allowanceIds != null ? allowanceIds : List.of();
        payrollSetupService.deactivateRemovedAllowancesFromPayGroup(payGroupId, submittedIds);

        if (submittedIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("info", "All allowances removed from pay group");
            return "redirect:/payroll/pay-group";
        }

        // Get all allowances to lookup amounts
        Map<Long, Allowance> allowanceMap = payrollSetupService.getActiveAllowances()
                .stream()
                .collect(Collectors.toMap(Allowance::getId, a -> a));

        List<AllowanceAttachmentRequest> requests = submittedIds.stream()
                .map(id -> {
                    Allowance allowance = allowanceMap.get(id);
                    if (allowance == null) return null;

                    AllowanceAttachmentRequest req = new AllowanceAttachmentRequest();
                    req.setAllowanceId(id);
                    req.setOverridden(false);
                    req.setOverrideAmount(allowance.getAmount());

                    // Check for start date parameter
                    String startDateStr = allParams.get("startDate_" + id);
                    if (startDateStr != null && !startDateStr.isEmpty()) {
                        req.setEffectiveFrom(LocalDate.parse(startDateStr));
                    } else {
                        req.setEffectiveFrom(LocalDate.now());
                    }

                    return req;
                })
                .filter(Objects::nonNull)
                .toList();

        if (!requests.isEmpty()) {
            payrollSetupService.addAllowancesToPayGroup(payGroupId, requests);
            redirectAttributes.addFlashAttribute("success", "Allowances updated successfully");
        }

        return "redirect:/payroll/pay-group";
    }

    @PostMapping("/setup/paygroup/deductions")
    public String attachDeductionsToPayGroup(
            @RequestParam Long payGroupId,
            @RequestParam(required = false) List<Long> deductionIds,
            @RequestParam Map<String, String> allParams,
            RedirectAttributes redirectAttributes) {

        // Soft-delete deductions that were unchecked (not in the submitted list)
        List<Long> submittedIds = deductionIds != null ? deductionIds : List.of();
        payrollSetupService.deactivateRemovedDeductionsFromPayGroup(payGroupId, submittedIds);

        if (submittedIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("info", "All deductions removed from pay group");
            return "redirect:/payroll/pay-group";
        }

        // Get all deductions to lookup amounts
        Map<Long, Deduction> deductionMap = payrollSetupService.getActiveDeductions()
                .stream()
                .collect(Collectors.toMap(Deduction::getId, d -> d));

        List<DeductionAttachmentRequest> requests = submittedIds.stream()
                .map(id -> {
                    Deduction deduction = deductionMap.get(id);
                    if (deduction == null) return null;

                    DeductionAttachmentRequest req = new DeductionAttachmentRequest();
                    req.setDeductionId(id);
                    req.setOverridden(false);
                    req.setOverrideAmount(deduction.getAmount());

                    // Check for start date parameter
                    String startDateStr = allParams.get("startDate_" + id);
                    if (startDateStr != null && !startDateStr.isEmpty()) {
                        req.setEffectiveFrom(LocalDate.parse(startDateStr));
                    } else {
                        req.setEffectiveFrom(LocalDate.now());
                    }

                    return req;
                })
                .filter(Objects::nonNull)
                .toList();

        if (!requests.isEmpty()) {
            payrollSetupService.addDeductionsToPayGroup(payGroupId, requests);
            redirectAttributes.addFlashAttribute("success", "Deductions updated successfully");
        }

        return "redirect:/payroll/pay-group";
    }

    @PostMapping("/setup/paygroup/taxreliefs")
    public String attachTaxReliefsToPayGroup(
            @RequestParam Long payGroupId,
            @RequestParam(required = false) List<Long> taxReliefIds,
            @RequestParam Map<String, String> allParams,
            RedirectAttributes redirectAttributes) {

        // Soft-delete tax reliefs that were unchecked (not in the submitted list)
        List<Long> submittedIds = taxReliefIds != null ? taxReliefIds : List.of();
        payrollSetupService.deactivateRemovedTaxReliefsFromPayGroup(payGroupId, submittedIds);

        if (submittedIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("info", "All tax reliefs removed from pay group");
            return "redirect:/payroll/pay-group";
        }

        // Get all tax reliefs to lookup amounts
        Map<Long, TaxRelief> taxReliefMap = payrollSetupService.getActiveTaxReliefs()
                .stream()
                .collect(Collectors.toMap(TaxRelief::getId, t -> t));

        List<TaxReliefAttachmentRequest> requests = submittedIds.stream()
                .map(id -> {
                    TaxRelief taxRelief = taxReliefMap.get(id);
                    if (taxRelief == null) return null;

                    TaxReliefAttachmentRequest req = new TaxReliefAttachmentRequest();
                    req.setTaxReliefId(id);
                    req.setOverridden(false);
                    req.setOverrideAmount(taxRelief.getAmount());

                    // Check for start date parameter
                    String startDateStr = allParams.get("startDate_" + id);
                    if (startDateStr != null && !startDateStr.isEmpty()) {
                        req.setEffectiveFrom(LocalDate.parse(startDateStr));
                    } else {
                        req.setEffectiveFrom(LocalDate.now());
                    }

                    return req;
                })
                .filter(Objects::nonNull)
                .toList();

        if (!requests.isEmpty()) {
            payrollSetupService.addTaxReliefsToPayGroup(payGroupId, requests);
            redirectAttributes.addFlashAttribute("success", "Tax reliefs updated successfully");
        }

        return "redirect:/payroll/pay-group";
    }



    @GetMapping("/payroll/employee-payroll")
    public String getEmployeePayroll(Model model) {

        boolean isRestrictedHr = authenticationManager.isRestrictedHr();

        // Fetch and sort current payroll runs by Employee ID,
        // excluding restricted employees if caller is restrictedHr
        List<PayrollRun> payrollRuns = paySlipService.getCurrentPeriodPayrollRuns(1L).stream()
                .filter(r -> {
                    Employee emp = r.getEmployee();
                    if (isRestrictedHr && emp.isRestrictedVisibility()) return false;
                    if (jobHrEmployeeAccessService.isJobHrScopedUser()) {
                        Long actorGradeId = jobHrEmployeeAccessService.getLoggedInJobGradeId();
                        Long empGradeId = emp.getJobStep() != null && emp.getJobStep().getJobGrade() != null
                                ? emp.getJobStep().getJobGrade().getId() : null;
                        return java.util.Objects.equals(actorGradeId, empGradeId);
                    }
                    return true;
                })
                .sorted((a, b) -> a.getEmployee().getId().compareTo(b.getEmployee().getId()))
                .toList();

        // Build a set of visible employee IDs for fast DTO filtering
        boolean needsFiltering = isRestrictedHr || jobHrEmployeeAccessService.isJobHrScopedUser();
        Set<Long> visibleEmployeeIds = needsFiltering
                ? payrollRuns.stream()
                .map(r -> r.getEmployee().getId())
                .collect(Collectors.toSet())
                : null;

        // Fetch and sort previous payslips, excluding restricted employees
        Map<String, List<PaySlipDTO>> previousPeriods = paySlipService.getAllClosedPeriodPaySlips(1L).stream()
                .filter(ps -> visibleEmployeeIds == null || visibleEmployeeIds.contains(ps.getEmployeeId()))
                .sorted((a, b) -> {
                    // Sort within each group by employee
                    int cmp = b.getPayDate().compareTo(a.getPayDate()); // newest periods first
                    return cmp != 0 ? cmp : a.getEmployeeId().compareTo(b.getEmployeeId());
                })
                .collect(Collectors.groupingBy(
                        ps -> YearMonth.from(ps.getPayDate()).toString(), // key: "2025-01"
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

// Sort so newest period comes first (LinkedHashMap preserves insertion order)
        Map<String, List<PaySlipDTO>> sortedPreviousPeriods = previousPeriods.entrySet().stream()
                .sorted(Map.Entry.<String, List<PaySlipDTO>>comparingByKey().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));


        // Auto-generate payslips for any POSTED run that doesn't have one yet
        for (PayrollRun run : payrollRuns) {
            if (run.getStatus() == PayrollRunStatus.POSTED &&
                    !paySlipService.existsForPayrollRun(run.getId())) {
                try {
                    paySlipService.generatePaySlip(run.getId());
                } catch (Exception ignored) {}
            }
        }

        List<PaySlipDTO> currentPaySlips = List.of();
        try {
            currentPaySlips = paySlipService.getCurrentPeriodPaySlips(1L).stream()
                    .filter(ps -> visibleEmployeeIds == null || visibleEmployeeIds.contains(ps.getEmployeeId()))
                    .sorted((a, b) -> a.getEmployeeId().compareTo(b.getEmployeeId()))
                    .map(ps -> {
                        List<FutureEmployeeAllowanceDTO> futureAllowances = List.of();
                        try {
                            futureAllowances = payrollSetupService
                                    .getFutureAllowancesForEmployee(ps.getEmployeeId());
                        } catch (Exception ignored) {}
                        return PaySlipDTO.builder()
                                .id(ps.getId())
                                .employeeId(ps.getEmployeeId())
                                .employeeName(ps.getEmployeeName())
                                .payrollRunId(ps.getPayrollRunId())
                                .payDate(ps.getPayDate())
                                .versionNumber(ps.getVersionNumber())
                                .basicSalary(ps.getBasicSalary())
                                .grossPay(ps.getGrossPay())
                                .totalDeductions(ps.getTotalDeductions())
                                .netPay(ps.getNetPay())
                                .allowances(ps.getAllowances())
                                .deductions(ps.getDeductions())
                                .futureAllowances(futureAllowances)
                                .appliedTaxBandSummary(ps.getAppliedTaxBandSummary())
                                .appliedPensionSchemeName(ps.getAppliedPensionSchemeName())
                                .pensionAmount(ps.getPensionAmount())
                                .employerPensionAmount(ps.getEmployerPensionAmount())
                                .status(ps.getStatus())
                                .bankName(ps.getBankName())
                                .bankAccountNumber(ps.getBankAccountNumber())
                                .salaryKpiScore(ps.getSalaryKpiScore())
                                .salaryImpactKpis(ps.getSalaryImpactKpis())
                                .jobGradeName(ps.getJobGradeName())
                                .companyLogoData(ps.getCompanyLogoData())
                                .companyLogoContentType(ps.getCompanyLogoContentType())
                                .build();
                    })
                    .toList();
        } catch (Exception e) {}

        model.addAttribute("payrollRuns", payrollRuns);
        model.addAttribute("currentPaySlips", currentPaySlips);
        model.addAttribute("previousPeriods", sortedPreviousPeriods);
        model.addAttribute("title", "Payroll Management");
        model.addAttribute("subTitle", "Manage employee payroll, salary details, and payment history");

        List<FlowableTaskDTO> pendingLockTasks = flowableTaskService
                .getTasksByTaskDefinition("financeOfficer", "payrollPeriodCloseProcess");

        model.addAttribute("lockPending", !pendingLockTasks.isEmpty());
        model.addAttribute("isRestrictedHr", isRestrictedHr);                                    // ADD THIS
        model.addAttribute("isJobHr", jobHrEmployeeAccessService.isJobHrScopedUser());

        return "payroll/fragments/employee-payroll";
    }

    @PostMapping("/api/payroll/current-payslips/email")
    @ResponseBody
    public ResponseEntity<PayslipEmailResponse> emailCurrentPayslips(@RequestBody PayslipEmailRequest request) {
        PayslipEmailResponse response = paySlipEmailService.emailCurrentPayslips(1L, request);
        return response.failed() > 0 && response.requested() == 0
                ? ResponseEntity.badRequest().body(response)
                : ResponseEntity.ok(response);
    }

    @PostMapping("/api/payroll/past-payslips/email")
    @ResponseBody
    public ResponseEntity<PayslipEmailResponse> emailPastPayslips(@RequestBody PastPayslipEmailRequest request) {
        PayslipEmailResponse response = paySlipEmailService.emailPastPayslips(1L, request);
        return response.failed() > 0 && response.requested() == 0
                ? ResponseEntity.badRequest().body(response)
                : ResponseEntity.ok(response);
    }


    @PostMapping("/createPayGroup")
    public String createPayGroup(CreatePayGroupCommand command, RedirectAttributes redirectAttributes) {
        setupService.createPayGroup(command);
        redirectAttributes.addFlashAttribute("success", "Pay group created successfully");
        return "redirect:/payroll/pay-group";
    }

    @PostMapping("/setup/allowance")
    public String createAllowance(Allowance allowance, RedirectAttributes redirectAttributes) {
        if (allowance.getAmount() == null)
            allowance.setAmount(BigDecimal.ZERO);
        payrollSetupService.createAllowance(allowance);
        redirectAttributes.addFlashAttribute("success", "Allowance created successfully");
        return "redirect:/payroll/items";
    }

    @PostMapping("/setup/deduction")
    public String createDeduction(Deduction deduction, RedirectAttributes redirectAttributes) {
        if (deduction.getAmount() == null)
            deduction.setAmount(BigDecimal.ZERO);
        payrollSetupService.createDeduction(deduction);
        redirectAttributes.addFlashAttribute("success", "Deduction created successfully");
        return "redirect:/payroll/items";
    }

    @PostMapping("/setup/taxrelief")
    public String createTaxRelief(TaxRelief taxRelief, RedirectAttributes redirectAttributes) {
        if (taxRelief.getAmount() == null)
            taxRelief.setAmount(BigDecimal.ZERO);
        payrollSetupService.createTaxRelief(taxRelief);
        redirectAttributes.addFlashAttribute("success", "Tax relief created successfully");
        return "redirect:/payroll/items";
    }

    @PostMapping("/setup/payroll/open")
    public String openPayroll(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd,
            RedirectAttributes redirectAttributes) {
        try {
            YearMonth yearMonth = YearMonth.now();
            LocalDate start = periodStart != null ? periodStart : yearMonth.atDay(1);
            LocalDate end = periodEnd != null ? periodEnd : yearMonth.atEndOfMonth();
            payrollPeriodService.openInitialPeriod(1L, start, end);
            redirectAttributes.addFlashAttribute("success", "Payroll period opened");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/payroll";
    }

    @PostMapping("/setup/payroll/close")
    public String closePayroll(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate actualPeriodEnd,
            RedirectAttributes redirectAttributes) {
        try {
            PayrollPeriod currentPeriod = payrollPeriodService.getOpenPeriod(1L);
            LocalDate closeThrough = actualPeriodEnd != null && currentPeriod != null
                    ? actualPeriodEnd
                    : currentPeriod != null ? currentPeriod.getPeriodEnd() : null;
            payrollPeriodService.initiatePeriodCloseApproval(1L, closeThrough);
            redirectAttributes.addFlashAttribute("success", "Payroll locked!");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/payroll/employee-payroll";
    }

    @PostMapping("/setup/payroll/extend")
    public String extendPayrollPeriod(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate actualPeriodEnd,
            RedirectAttributes redirectAttributes) {
        try {
            payrollPeriodService.extendOpenPeriodEnd(1L, actualPeriodEnd);
            redirectAttributes.addFlashAttribute("success", "Payroll period extended");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/payroll";
    }


    @GetMapping("/payroll/grouped-report")
    public String getReport(
            @RequestParam(defaultValue = "GRADE") String groupBy,
            Model model) {

        if (authenticationManager.isRestrictedHr()) {
            return "redirect:/payroll/employee-payroll";
        }

        // Use the actual open period's exact dates from the DB — this correctly handles
        // periods that span across calendar months (boss's change).
        PayrollPeriod openPeriod = payrollPeriodService.getCurrentPeriod(1L);
        LocalDate periodStart;
        LocalDate periodEnd;
        if (openPeriod != null) {
            periodStart = openPeriod.getPeriodStart();
            periodEnd = openPeriod.getPeriodEnd();
        } else {
            YearMonth fallback = YearMonth.now();
            periodStart = fallback.atDay(1);
            periodEnd = fallback.atEndOfMonth();
        }
        YearMonth currentMonth = YearMonth.from(periodEnd);

        // Build scoped employee ID set for jobHR
        final Set<Long> scopedIds;
        if (jobHrEmployeeAccessService.isJobHrScopedUser()) {
            Long actorGradeId = jobHrEmployeeAccessService.getLoggedInJobGradeId();
            scopedIds = employeeOnboardingService.getAllOnboardings().stream()
                    .filter(e -> e.getJobStep() != null
                            && e.getJobStep().getJobGrade() != null
                            && Objects.equals(e.getJobStep().getJobGrade().getId(), actorGradeId))
                    .map(Employee::getId)
                    .collect(Collectors.toSet());
        } else {
            scopedIds = null;
        }

        List<EmployeeGroupedReportDTO> report = reportingService.getGroupedReport(
                        1L,
                        periodStart,
                        periodEnd,
                        EmployeeGroupBy.valueOf(groupBy)
                ).stream()
                .map(group -> {
                    if (scopedIds != null) {
                        group.getEmployees().removeIf(e -> !scopedIds.contains(e.getEmployeeId()));
                    }
                    group.getEmployees().sort(Comparator.comparing(e -> e.getEmployeeId()));
                    return group;
                })
                .filter(group -> !group.getEmployees().isEmpty()) // remove empty groups
                .toList();

        Map<String, Object> grandTotals = reportingService.calculateGrandTotals(report);

        // ── Past periods: group closed payslips by YearMonth, then by the chosen groupBy ──
        Map<Long, Employee> employeeMap = employeeOnboardingService.getAllOnboardings().stream()
                .collect(Collectors.toMap(Employee::getId, e -> e, (a, b) -> a));

        // Deduplicate: for each (employeeId, yearMonth) pair, prefer the payslip that has deduction lines.
        // getAllClosedPeriodPaySlips returns duplicate records — one shell (no lines) and one with lines.
        List<PaySlipDTO> closedPaySlips = paySlipService.getAllClosedPeriodPaySlips(1L).stream()
                .filter(ps -> scopedIds == null || scopedIds.contains(ps.getEmployeeId()))
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        list -> {
                            Map<String, PaySlipDTO> best = new LinkedHashMap<>();
                            for (PaySlipDTO ps : list) {
                                String key = ps.getEmployeeId() + "_" + YearMonth.from(ps.getPayDate());
                                PaySlipDTO existing = best.get(key);
                                boolean hasLines = ps.getDeductions() != null && !ps.getDeductions().isEmpty();
                                boolean existingHasLines = existing != null && existing.getDeductions() != null && !existing.getDeductions().isEmpty();
                                if (existing == null || (hasLines && !existingHasLines)) {
                                    best.put(key, ps);
                                }
                            }
                            return new ArrayList<>(best.values());
                        }
                ));

        // Group by YearMonth key, newest first
        Map<String, List<PaySlipDTO>> byPeriod = closedPaySlips.stream()
                .collect(Collectors.groupingBy(
                        ps -> YearMonth.from(ps.getPayDate()).toString(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        Map<String, List<EmployeeGroupedReportDTO>> pastGroupedReports = byPeriod.entrySet().stream()
                .sorted(Map.Entry.<String, List<PaySlipDTO>>comparingByKey().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        periodEntry -> {
                            // Group by chosen dimension within this period
                            Map<String, List<PaySlipDTO>> grouped = new LinkedHashMap<>();
                            for (PaySlipDTO ps : periodEntry.getValue()) {
                                Employee emp = employeeMap.get(ps.getEmployeeId());
                                String key = resolvePayItemsGroupName(emp, groupBy);
                                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(ps);
                            }
                            // Build EmployeeGroupedReportDTO list from the payslips
                            return grouped.entrySet().stream()
                                    .map(g -> {
                                        EmployeeGroupedReportDTO dto = new EmployeeGroupedReportDTO();
                                        dto.setGroupName(g.getKey());
                                        BigDecimal totalGross = BigDecimal.ZERO;
                                        BigDecimal totalDeductions = BigDecimal.ZERO;
                                        BigDecimal totalNet = BigDecimal.ZERO;
                                        List<EmployeeReportItemDTO> lines = new ArrayList<>();
                                        for (PaySlipDTO ps : g.getValue()) {
                                            totalGross = totalGross.add(ps.getGrossPay() != null ? ps.getGrossPay() : BigDecimal.ZERO);
                                            totalDeductions = totalDeductions.add(ps.getTotalDeductions() != null ? ps.getTotalDeductions() : BigDecimal.ZERO);
                                            totalNet = totalNet.add(ps.getNetPay() != null ? ps.getNetPay() : BigDecimal.ZERO);
                                            Employee emp = employeeMap.get(ps.getEmployeeId());
                                            // Resolve bank details from the employee's bankDetails list (primary active account)
                                            String bankName = "";
                                            String bankAccountNumber = "";
                                            if (emp != null && emp.getBankDetails() != null) {
                                                com.justjava.humanresource.hr.entity.EmployeeBankDetail primaryBank = emp.getBankDetails().stream()
                                                        .filter(b -> b.isPrimaryAccount()
                                                                && b.getStatus() == com.justjava.humanresource.core.enums.RecordStatus.ACTIVE)
                                                        .findFirst()
                                                        .orElse(emp.getBankDetails().stream()
                                                                .filter(b -> b.getStatus() == com.justjava.humanresource.core.enums.RecordStatus.ACTIVE)
                                                                .findFirst()
                                                                .orElse(null));
                                                if (primaryBank != null) {
                                                    bankName = primaryBank.getBankName();
                                                    bankAccountNumber = primaryBank.getAccountNumber();
                                                }
                                            }
                                            // Use summary-level fields first (most reliable for closed periods)
                                            BigDecimal paye = BigDecimal.ZERO;
                                            BigDecimal pension = ps.getPensionAmount() != null ? ps.getPensionAmount() : BigDecimal.ZERO;
                                            if (ps.getDeductions() != null) {
                                                for (PaySlipLineDTO d : ps.getDeductions()) {
                                                    String dCode = d.getCode() != null ? d.getCode().trim() : "";
                                                    String dDesc = d.getDescription() != null ? d.getDescription().toLowerCase() : "";
                                                    BigDecimal dAmt = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
                                                    // PAYE: match by code or description
                                                    if ("PAYE".equalsIgnoreCase(dCode) || dDesc.contains("paye") || dDesc.contains("pay as you earn")) {
                                                        paye = paye.add(dAmt);
                                                    }
                                                    // Pension: only scan lines if the summary field was empty
                                                    if (pension.compareTo(BigDecimal.ZERO) == 0) {
                                                        if ("PENSION".equalsIgnoreCase(dCode) || "PENSION_EMP".equalsIgnoreCase(dCode) || dDesc.contains("pension")) {
                                                            pension = pension.add(dAmt);
                                                        }
                                                    }
                                                }
                                            }
                                            EmployeeReportItemDTO line = new EmployeeReportItemDTO(
                                                    emp != null ? emp.getId() : null,
                                                    emp != null ? emp.getFirstName() : "",
                                                    emp != null ? emp.getLastName() : "",
                                                    ps.getGrossPay(),
                                                    ps.getNetPay(),
                                                    paye,
                                                    pension,
                                                    g.getKey(),
                                                    bankAccountNumber,
                                                    bankName
                                            );
                                            lines.add(line);
                                        }
                                        dto.setEmployees(lines);
                                        dto.setEmployeeCount((long) lines.size());
                                        dto.setTotalGross(totalGross);
                                        dto.setTotalDeductions(totalDeductions);
                                        dto.setTotalNet(totalNet);
                                        return dto;
                                    })
                                    .filter(g -> !g.getEmployees().isEmpty())
                                    .sorted(Comparator.comparing(EmployeeGroupedReportDTO::getGroupName))
                                    .collect(Collectors.toList());
                        },
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        // Pre-compute per-period grand totals in Java (Thymeleaf SpEL cannot use lambda syntax)
        Map<String, Map<String, Object>> pastPeriodTotals = new LinkedHashMap<>();
        for (Map.Entry<String, List<EmployeeGroupedReportDTO>> entry : pastGroupedReports.entrySet()) {
            List<EmployeeGroupedReportDTO> groups = entry.getValue();
            BigDecimal periodGross = groups.stream()
                    .map(g -> g.getTotalGross() != null ? g.getTotalGross() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal periodDeductions = groups.stream()
                    .map(g -> g.getTotalDeductions() != null ? g.getTotalDeductions() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal periodNet = groups.stream()
                    .map(g -> g.getTotalNet() != null ? g.getTotalNet() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long periodEmployees = groups.stream()
                    .mapToLong(g -> g.getEmployeeCount() != null ? g.getEmployeeCount() : 0L)
                    .sum();
            pastPeriodTotals.put(entry.getKey(), Map.of(
                    "totalGross", periodGross,
                    "totalDeductions", periodDeductions,
                    "totalNet", periodNet,
                    "totalEmployees", periodEmployees,
                    "totalGroups", (long) groups.size()
            ));
        }

        model.addAttribute("report", report);
        model.addAttribute("totals", grandTotals);
        model.addAttribute("groupBy", groupBy);
        model.addAttribute("pastGroupedReports", pastGroupedReports);
        model.addAttribute("pastPeriodTotals", pastPeriodTotals);
        model.addAttribute("isRestrictedHr", authenticationManager.isRestrictedHr());
        model.addAttribute("isJobHr", jobHrEmployeeAccessService.isJobHrScopedUser());
        model.addAttribute("title", "Grouped Payroll Report");
        model.addAttribute("subTitle", "Grouped summary of employee payroll by selected category");
        return "payroll/fragments/grouped-report";
    }



    @GetMapping("/payroll/payitems-report")
    public String getPayItemsReport(
            @RequestParam(defaultValue = "GRADE") String groupBy,
            Model model) {

        if (authenticationManager.isRestrictedHr()) {
            return "redirect:/payroll/employee-payroll";
        }

        YearMonth currentMonth = YearMonth.now();

        // ── 1. Scope filter (jobHR) ───────────────────────────────────
        final Set<Long> scopedIds;
        if (jobHrEmployeeAccessService.isJobHrScopedUser()) {
            Long actorGradeId = jobHrEmployeeAccessService.getLoggedInJobGradeId();
            scopedIds = employeeOnboardingService.getAllOnboardings().stream()
                    .filter(e -> e.getJobStep() != null
                            && e.getJobStep().getJobGrade() != null
                            && Objects.equals(e.getJobStep().getJobGrade().getId(), actorGradeId))
                    .map(Employee::getId)
                    .collect(Collectors.toSet());
        } else {
            scopedIds = null;
        }

        Map<Long, Employee> employeeMap = employeeOnboardingService.getAllOnboardings().stream()
                .collect(Collectors.toMap(Employee::getId, e -> e, (a, b) -> a));

        // ── 2. CURRENT PERIOD ─────────────────────────────────────────
        List<PaySlipDTO> paySlips = paySlipService.getCurrentPeriodPaySlips(1L);
        if (scopedIds != null) {
            paySlips = paySlips.stream()
                    .filter(ps -> scopedIds.contains(ps.getEmployeeId()))
                    .collect(Collectors.toList());
        }

        Map<String, List<PaySlipDTO>> grouped = new LinkedHashMap<>();
        for (PaySlipDTO ps : paySlips) {
            Employee emp = employeeMap.get(ps.getEmployeeId());
            String key = resolvePayItemsGroupName(emp, groupBy);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(ps);
        }

        List<AllowanceGroupReportDTO> payitemsReport = new ArrayList<>();
        for (Map.Entry<String, List<PaySlipDTO>> entry : grouped.entrySet()) {
            AllowanceGroupReportDTO group = new AllowanceGroupReportDTO();
            group.setGroupName(entry.getKey());
            group.setEmployeeCount((long) entry.getValue().size());

            Map<String, AllowanceReportLineDTO> codeMap = new LinkedHashMap<>();
            for (PaySlipDTO slip : entry.getValue()) {
                if (slip.getAllowances() == null) continue;
                for (PaySlipLineDTO line : slip.getAllowances()) {
                    if ("RESIDUAL".equals(line.getCode()) || "Residual Adjustment".equals(line.getDescription())) continue;
                    codeMap.compute(line.getCode(), (code, existing) -> {
                        BigDecimal amt = line.getAmount() != null ? line.getAmount() : BigDecimal.ZERO;
                        if (existing == null) {
                            return new AllowanceReportLineDTO(line.getCode(), line.getDescription(), amt, line.isTaxable(), !line.isOutOfPayroll());
                        }
                        existing.setTotalAmount(existing.getTotalAmount().add(amt));
                        return existing;
                    });
                }
            }

            List<AllowanceReportLineDTO> lines = new ArrayList<>(codeMap.values());
            BigDecimal groupTotal = lines.stream().map(AllowanceReportLineDTO::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            group.setAllowances(lines);
            group.setGroupTotal(groupTotal);
            payitemsReport.add(group);
        }
        payitemsReport.sort(Comparator.comparing(AllowanceGroupReportDTO::getGroupName));

        Map<String, AllowanceReportLineDTO> grandCodeMap = new LinkedHashMap<>();
        for (AllowanceGroupReportDTO group : payitemsReport) {
            for (AllowanceReportLineDTO line : group.getAllowances()) {
                grandCodeMap.compute(line.getCode(), (code, existing) -> {
                    if (existing == null) return new AllowanceReportLineDTO(line.getCode(), line.getDescription(), line.getTotalAmount(), line.isTaxable(), line.isPartOfGross());
                    existing.setTotalAmount(existing.getTotalAmount().add(line.getTotalAmount()));
                    return existing;
                });
            }
        }
        BigDecimal grandTotal = grandCodeMap.values().stream().map(AllowanceReportLineDTO::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── 3. PAST PERIODS ───────────────────────────────────────────
        // Deduplicate: best payslip per (employeeId, yearMonth)
        List<PaySlipDTO> closedPaySlips = paySlipService.getAllClosedPeriodPaySlips(1L).stream()
                .filter(ps -> scopedIds == null || scopedIds.contains(ps.getEmployeeId()))
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        list -> {
                            Map<String, PaySlipDTO> best = new LinkedHashMap<>();
                            for (PaySlipDTO ps : list) {
                                String key = ps.getEmployeeId() + "_" + YearMonth.from(ps.getPayDate());
                                PaySlipDTO existing = best.get(key);
                                boolean hasLines = ps.getAllowances() != null && !ps.getAllowances().isEmpty();
                                boolean existingHasLines = existing != null && existing.getAllowances() != null && !existing.getAllowances().isEmpty();
                                if (existing == null || (hasLines && !existingHasLines)) {
                                    best.put(key, ps);
                                }
                            }
                            return new ArrayList<>(best.values());
                        }
                ));

        // Group by yearMonth, newest first
        Map<String, List<PaySlipDTO>> byPeriod = closedPaySlips.stream()
                .collect(Collectors.groupingBy(
                        ps -> YearMonth.from(ps.getPayDate()).toString(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        // Build past AllowanceGroupReportDTO per period
        Map<String, List<AllowanceGroupReportDTO>> pastPayitemsReports = byPeriod.entrySet().stream()
                .sorted(Map.Entry.<String, List<PaySlipDTO>>comparingByKey().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        periodEntry -> {
                            Map<String, List<PaySlipDTO>> periodGrouped = new LinkedHashMap<>();
                            for (PaySlipDTO ps : periodEntry.getValue()) {
                                Employee emp = employeeMap.get(ps.getEmployeeId());
                                String key = resolvePayItemsGroupName(emp, groupBy);
                                periodGrouped.computeIfAbsent(key, k -> new ArrayList<>()).add(ps);
                            }

                            List<AllowanceGroupReportDTO> periodReport = new ArrayList<>();
                            for (Map.Entry<String, List<PaySlipDTO>> g : periodGrouped.entrySet()) {
                                AllowanceGroupReportDTO dto = new AllowanceGroupReportDTO();
                                dto.setGroupName(g.getKey());
                                dto.setEmployeeCount((long) g.getValue().size());

                                Map<String, AllowanceReportLineDTO> cm = new LinkedHashMap<>();
                                for (PaySlipDTO slip : g.getValue()) {
                                    if (slip.getAllowances() == null) continue;
                                    for (PaySlipLineDTO line : slip.getAllowances()) {
                                        if ("RESIDUAL".equals(line.getCode()) || "Residual Adjustment".equals(line.getDescription())) continue;
                                        cm.compute(line.getCode(), (code, existing) -> {
                                            BigDecimal amt = line.getAmount() != null ? line.getAmount() : BigDecimal.ZERO;
                                            if (existing == null) return new AllowanceReportLineDTO(line.getCode(), line.getDescription(), amt, line.isTaxable(), !line.isOutOfPayroll());
                                            existing.setTotalAmount(existing.getTotalAmount().add(amt));
                                            return existing;
                                        });
                                    }
                                }

                                List<AllowanceReportLineDTO> dtoLines = new ArrayList<>(cm.values());
                                BigDecimal dtoTotal = dtoLines.stream().map(AllowanceReportLineDTO::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                                dto.setAllowances(dtoLines);
                                dto.setGroupTotal(dtoTotal);
                                periodReport.add(dto);
                            }
                            periodReport.sort(Comparator.comparing(AllowanceGroupReportDTO::getGroupName));
                            return periodReport;
                        },
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        // Pre-compute per-period totals (grand allowances chips + counts) for Thymeleaf
        Map<String, Map<String, Object>> pastPayitemsPeriodTotals = new LinkedHashMap<>();
        for (Map.Entry<String, List<AllowanceGroupReportDTO>> entry : pastPayitemsReports.entrySet()) {
            List<AllowanceGroupReportDTO> groups = entry.getValue();

            long periodEmployees = groups.stream().mapToLong(AllowanceGroupReportDTO::getEmployeeCount).sum();

            // Aggregate grand allowance chips for this period
            Map<String, AllowanceReportLineDTO> periodGrandMap = new LinkedHashMap<>();
            for (AllowanceGroupReportDTO grp : groups) {
                for (AllowanceReportLineDTO line : grp.getAllowances()) {
                    periodGrandMap.compute(line.getCode(), (code, existing) -> {
                        if (existing == null) return new AllowanceReportLineDTO(line.getCode(), line.getDescription(), line.getTotalAmount(), line.isTaxable(), line.isPartOfGross());
                        existing.setTotalAmount(existing.getTotalAmount().add(line.getTotalAmount()));
                        return existing;
                    });
                }
            }

            Map<String, Object> periodTotals = new LinkedHashMap<>();
            periodTotals.put("totalGroups",     (long) groups.size());
            periodTotals.put("totalEmployees",  periodEmployees);
            periodTotals.put("grandAllowances", new ArrayList<>(periodGrandMap.values()));
            pastPayitemsPeriodTotals.put(entry.getKey(), periodTotals);
        }

        // ── 4. Model attributes ───────────────────────────────────────
        model.addAttribute("payitemsReport",          payitemsReport);
        model.addAttribute("grandAllowances",         new ArrayList<>(grandCodeMap.values()));
        model.addAttribute("grandTotal",              grandTotal);
        model.addAttribute("totalGroups",             (long) payitemsReport.size());
        model.addAttribute("totalEmployees",          payitemsReport.stream().mapToLong(AllowanceGroupReportDTO::getEmployeeCount).sum());
        model.addAttribute("groupBy",                 groupBy);
        model.addAttribute("reportMonth",             currentMonth.toString());
        model.addAttribute("pastPayitemsReports",     pastPayitemsReports);
        model.addAttribute("pastPayitemsPeriodTotals",pastPayitemsPeriodTotals);
        model.addAttribute("title",                   "Payroll Management");
        model.addAttribute("subTitle",                "Manage employee payroll, salary details, and payment history");
        return "payroll/fragments/payitems-report";
    }

    private String resolvePayItemsGroupName(Employee emp, String groupBy) {
        if (emp == null) return "Unknown";
        switch (groupBy.toUpperCase()) {
            case "GRADE":
                return (emp.getJobStep() != null && emp.getJobStep().getJobGrade() != null)
                        ? emp.getJobStep().getJobGrade().getName() : "No Grade";
            case "PAYGROUP":
                return emp.getPayGroup() != null ? emp.getPayGroup().getName() : "No Pay Group";
            default:
                return "Unknown";
        }
    }

    @PostMapping("/setup/allowance/update")
    public String updateAllowance(Allowance allowance, RedirectAttributes redirectAttributes) {
        if (allowance.getAmount() == null)
            allowance.setAmount(BigDecimal.ZERO);
        payrollSetupService.updateAllowance(allowance);
        redirectAttributes.addFlashAttribute("success", "Allowance updated successfully");
        return "redirect:/payroll/items";
    }

    @PostMapping("/setup/deduction/update")
    public String updateDeduction(Deduction deduction, RedirectAttributes redirectAttributes) {
        if (deduction.getAmount() == null)
            deduction.setAmount(BigDecimal.ZERO);
        payrollSetupService.updateDeduction(deduction);
        redirectAttributes.addFlashAttribute("success", "Deduction updated successfully");
        return "redirect:/payroll/items";
    }

    @PostMapping("/setup/taxrelief/update")
    public String updateTaxRelief(TaxRelief taxRelief, RedirectAttributes redirectAttributes) {
        if (taxRelief.getAmount() == null)
            taxRelief.setAmount(BigDecimal.ZERO);
        payrollSetupService.updateTaxRelief(taxRelief);
        redirectAttributes.addFlashAttribute("success", "Tax relief updated successfully");
        return "redirect:/payroll/items";
    }




    @GetMapping("/payroll/paye-pension-report")
    public String getPayePensionReport(@RequestParam(defaultValue = "GRADE") String groupBy, Model model) {
        if (authenticationManager.isRestrictedHr()) {
            return "redirect:/payroll/employee-payroll";
        }

        YearMonth currentMonth = YearMonth.now();

        // ── Scope filter (Job-HR) ──────────────────────────────────────
        final Set<Long> scopedIds;
        if (jobHrEmployeeAccessService.isJobHrScopedUser()) {
            Long actorGradeId = jobHrEmployeeAccessService.getLoggedInJobGradeId();
            scopedIds = employeeOnboardingService.getAllOnboardings().stream()
                    .filter(e -> e.getJobStep() != null
                            && e.getJobStep().getJobGrade() != null
                            && Objects.equals(e.getJobStep().getJobGrade().getId(), actorGradeId))
                    .map(Employee::getId)
                    .collect(Collectors.toSet());
        } else {
            scopedIds = null;
        }

        Map<Long, Employee> employeeMap = employeeOnboardingService.getAllOnboardings().stream()
                .collect(Collectors.toMap(Employee::getId, e -> e, (a, b) -> a));

        // ── CURRENT PERIOD ─────────────────────────────────────────────
        List<PaySlipDTO> paySlips = paySlipService.getCurrentPeriodPaySlips(1L);

        Map<String, PayePensionGroupDTO> groupedData = new LinkedHashMap<>();
        BigDecimal grandPaye = BigDecimal.ZERO;
        BigDecimal grandEmpPension = BigDecimal.ZERO;
        BigDecimal grandEmployerPension = BigDecimal.ZERO;
        int grandEmployeeCount = 0;

        for (PaySlipDTO ps : paySlips) {
            if (scopedIds != null && !scopedIds.contains(ps.getEmployeeId())) continue;

            Employee emp = employeeMap.get(ps.getEmployeeId());
            if (emp == null) continue;

            String groupName = resolvePayItemsGroupName(emp, groupBy);
            PayePensionGroupDTO group = groupedData.computeIfAbsent(groupName, k -> PayePensionGroupDTO.builder()
                    .groupName(k)
                    .employees(new ArrayList<>())
                    .build());

            BigDecimal paye = BigDecimal.ZERO;
            BigDecimal empPension = ps.getPensionAmount() != null ? ps.getPensionAmount() : BigDecimal.ZERO;

            if (ps.getDeductions() != null) {
                for (PaySlipLineDTO deduction : ps.getDeductions()) {
                    String code = deduction.getCode() != null ? deduction.getCode().trim() : "";
                    String desc = deduction.getDescription() != null ? deduction.getDescription().toLowerCase() : "";
                    BigDecimal amount = deduction.getAmount() != null ? deduction.getAmount() : BigDecimal.ZERO;

                    if ("PAYE".equalsIgnoreCase(code)) {
                        paye = paye.add(amount);
                    }
                    if (empPension.compareTo(BigDecimal.ZERO) == 0) {
                        if ("PENSION".equalsIgnoreCase(code) || desc.contains("pension")) {
                            empPension = empPension.add(amount);
                        }
                    }
                }
            }

            BigDecimal employerPension = empPension.multiply(new BigDecimal("1.25")).setScale(2, RoundingMode.HALF_UP);

            PayePensionLineDTO line = PayePensionLineDTO.builder()
                    .employeeName(ps.getEmployeeName())
                    .employeeId(ps.getEmployeeId())
                    .paye(paye)
                    .employeePension(empPension)
                    .employerPension(employerPension)
                    .tinNumber(emp.getTinNumber() != null ? emp.getTinNumber() : "—")
                    .rsaPin(emp.getRsaPin() != null ? emp.getRsaPin() : "—")
                    .pfa(emp.getPfa() != null ? emp.getPfa() : "—")
                    .build();

            group.getEmployees().add(line);
            group.setTotalPaye(group.getTotalPaye().add(paye));
            group.setTotalEmployeePension(group.getTotalEmployeePension().add(empPension));
            group.setTotalEmployerPension(group.getTotalEmployerPension().add(employerPension));
            group.setEmployeeCount(group.getEmployees().size());

            grandPaye = grandPaye.add(paye);
            grandEmpPension = grandEmpPension.add(empPension);
            grandEmployerPension = grandEmployerPension.add(employerPension);
            grandEmployeeCount++;
        }

        List<PayePensionGroupDTO> reportList = new ArrayList<>(groupedData.values());
        reportList.sort(Comparator.comparing(PayePensionGroupDTO::getGroupName));

        Map<String, Object> totals = Map.of(
                "totalGroups", reportList.size(),
                "totalEmployees", grandEmployeeCount,
                "totalPaye", grandPaye,
                "totalEmployeePension", grandEmpPension,
                "totalEmployerPension", grandEmployerPension
        );

        // ── PAST PERIODS ───────────────────────────────────────────────
        // Deduplicate closed payslips: one best entry per (employeeId, yearMonth)
        List<PaySlipDTO> closedPaySlips = paySlipService.getAllClosedPeriodPaySlips(1L).stream()
                .filter(ps -> scopedIds == null || scopedIds.contains(ps.getEmployeeId()))
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        list -> {
                            Map<String, PaySlipDTO> best = new LinkedHashMap<>();
                            for (PaySlipDTO ps : list) {
                                String key = ps.getEmployeeId() + "_" + YearMonth.from(ps.getPayDate());
                                PaySlipDTO existing = best.get(key);
                                boolean hasLines = ps.getDeductions() != null && !ps.getDeductions().isEmpty();
                                boolean existingHasLines = existing != null && existing.getDeductions() != null && !existing.getDeductions().isEmpty();
                                if (existing == null || (hasLines && !existingHasLines)) {
                                    best.put(key, ps);
                                }
                            }
                            return new ArrayList<>(best.values());
                        }
                ));

        // Group by YearMonth, newest first
        Map<String, List<PaySlipDTO>> byPeriod = closedPaySlips.stream()
                .collect(Collectors.groupingBy(
                        ps -> YearMonth.from(ps.getPayDate()).toString(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        // Build past PAYE/Pension groups per period
        Map<String, List<PayePensionGroupDTO>> pastPayePensionReports = byPeriod.entrySet().stream()
                .sorted(Map.Entry.<String, List<PaySlipDTO>>comparingByKey().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        periodEntry -> {
                            Map<String, List<PaySlipDTO>> grouped = new LinkedHashMap<>();
                            for (PaySlipDTO ps : periodEntry.getValue()) {
                                Employee emp = employeeMap.get(ps.getEmployeeId());
                                String key = resolvePayItemsGroupName(emp, groupBy);
                                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(ps);
                            }
                            return grouped.entrySet().stream()
                                    .map(g -> {
                                        PayePensionGroupDTO dto = PayePensionGroupDTO.builder()
                                                .groupName(g.getKey())
                                                .employees(new ArrayList<>())
                                                .build();
                                        BigDecimal grpPaye = BigDecimal.ZERO;
                                        BigDecimal grpEmpPen = BigDecimal.ZERO;
                                        BigDecimal grpEmplyrPen = BigDecimal.ZERO;

                                        for (PaySlipDTO ps : g.getValue()) {
                                            Employee emp = employeeMap.get(ps.getEmployeeId());
                                            if (emp == null) continue;

                                            BigDecimal linePaye = BigDecimal.ZERO;
                                            BigDecimal lineEmpPen = ps.getPensionAmount() != null ? ps.getPensionAmount() : BigDecimal.ZERO;

                                            if (ps.getDeductions() != null) {
                                                for (PaySlipLineDTO d : ps.getDeductions()) {
                                                    String dCode = d.getCode() != null ? d.getCode().trim() : "";
                                                    String dDesc = d.getDescription() != null ? d.getDescription().toLowerCase() : "";
                                                    BigDecimal dAmt = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
                                                    if ("PAYE".equalsIgnoreCase(dCode) || dDesc.contains("paye") || dDesc.contains("pay as you earn")) {
                                                        linePaye = linePaye.add(dAmt);
                                                    }
                                                    if (lineEmpPen.compareTo(BigDecimal.ZERO) == 0) {
                                                        if ("PENSION".equalsIgnoreCase(dCode) || "PENSION_EMP".equalsIgnoreCase(dCode) || dDesc.contains("pension")) {
                                                            lineEmpPen = lineEmpPen.add(dAmt);
                                                        }
                                                    }
                                                }
                                            }

                                            BigDecimal lineEmplyrPen = lineEmpPen.multiply(new BigDecimal("1.25")).setScale(2, RoundingMode.HALF_UP);

                                            PayePensionLineDTO pastLine = PayePensionLineDTO.builder()
                                                    .employeeName(ps.getEmployeeName())
                                                    .employeeId(ps.getEmployeeId())
                                                    .paye(linePaye)
                                                    .employeePension(lineEmpPen)
                                                    .employerPension(lineEmplyrPen)
                                                    .tinNumber(emp.getTinNumber() != null ? emp.getTinNumber() : "—")
                                                    .rsaPin(emp.getRsaPin() != null ? emp.getRsaPin() : "—")
                                                    .pfa(emp.getPfa() != null ? emp.getPfa() : "—")
                                                    .build();

                                            dto.getEmployees().add(pastLine);
                                            grpPaye = grpPaye.add(linePaye);
                                            grpEmpPen = grpEmpPen.add(lineEmpPen);
                                            grpEmplyrPen = grpEmplyrPen.add(lineEmplyrPen);
                                        }

                                        dto.setEmployeeCount(dto.getEmployees().size());
                                        dto.setTotalPaye(grpPaye);
                                        dto.setTotalEmployeePension(grpEmpPen);
                                        dto.setTotalEmployerPension(grpEmplyrPen);
                                        return dto;
                                    })
                                    .filter(g -> !g.getEmployees().isEmpty())
                                    .sorted(Comparator.comparing(PayePensionGroupDTO::getGroupName))
                                    .collect(Collectors.toList());
                        },
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        // Pre-compute per-period totals for Thymeleaf
        Map<String, Map<String, Object>> pastPayePeriodTotals = new LinkedHashMap<>();
        for (Map.Entry<String, List<PayePensionGroupDTO>> entry : pastPayePensionReports.entrySet()) {
            List<PayePensionGroupDTO> groups = entry.getValue();
            BigDecimal periodPaye = groups.stream()
                    .map(g -> g.getTotalPaye() != null ? g.getTotalPaye() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal periodEmpPen = groups.stream()
                    .map(g -> g.getTotalEmployeePension() != null ? g.getTotalEmployeePension() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal periodEmplyrPen = groups.stream()
                    .map(g -> g.getTotalEmployerPension() != null ? g.getTotalEmployerPension() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long periodEmployees = groups.stream()
                    .mapToLong(g -> g.getEmployeeCount())
                    .sum();
            pastPayePeriodTotals.put(entry.getKey(), Map.of(
                    "totalGroups",          (long) groups.size(),
                    "totalEmployees",       periodEmployees,
                    "totalPaye",            periodPaye,
                    "totalEmployeePension", periodEmpPen,
                    "totalEmployerPension", periodEmplyrPen
            ));
        }

        // ── Model attributes ───────────────────────────────────────────
        model.addAttribute("report",                  reportList);
        model.addAttribute("totals",                  totals);
        model.addAttribute("groupBy",                 groupBy);
        model.addAttribute("reportMonth",             currentMonth.toString());
        model.addAttribute("pastPayePensionReports",  pastPayePensionReports);
        model.addAttribute("pastPayePeriodTotals",    pastPayePeriodTotals);
        model.addAttribute("title",    "PAYE & Pension Report");
        model.addAttribute("subTitle", "Statutory deductions and pension scheme reporting");

        return "payroll/fragments/paye-pension";
    }


}