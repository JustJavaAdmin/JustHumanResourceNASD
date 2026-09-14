package com.justjava.humanresource.payroll.workflow.impl;

import com.justjava.humanresource.core.enums.PayrollRunStatus;

import com.justjava.humanresource.core.enums.RecordStatus;
import com.justjava.humanresource.core.exception.ResourceNotFoundException;
import com.justjava.humanresource.hr.entity.Employee;
import com.justjava.humanresource.hr.entity.EmployeePositionHistory;
import com.justjava.humanresource.hr.entity.JobStep;
import com.justjava.humanresource.hr.entity.PayGroup;
import com.justjava.humanresource.hr.repository.EmployeeRepository;
import com.justjava.humanresource.kpi.entity.KpiMeasurement;
import com.justjava.humanresource.kpi.service.KpiMeasurementService;
import com.justjava.humanresource.payroll.calculation.PayGroupResolutionService;
import com.justjava.humanresource.payroll.calculation.dto.ResolvedPayComponents;
import com.justjava.humanresource.payroll.entity.*;
import com.justjava.humanresource.payroll.enums.PayComponentType;
import com.justjava.humanresource.payroll.enums.PayrollRunType;
import com.justjava.humanresource.payroll.repositories.PayrollLineItemRepository;
import com.justjava.humanresource.payroll.repositories.PayrollRunRepository;
import com.justjava.humanresource.payroll.service.EmployeePositionHistoryService;
import com.justjava.humanresource.payroll.service.PayrollAuditService;
import com.justjava.humanresource.payroll.service.PayrollPeriodService;
import com.justjava.humanresource.payroll.service.PayrollRunKpiSnapshotService;
import com.justjava.humanresource.payroll.service.PayrollSetupService;
import com.justjava.humanresource.payroll.statutory.entity.PensionScheme;
import com.justjava.humanresource.payroll.statutory.repositories.PensionSchemeRepository;
import com.justjava.humanresource.payroll.statutory.service.PayeCalculatorService;
import com.justjava.humanresource.payroll.statutory.service.PensionCalculatorService;
import com.justjava.humanresource.payroll.workflow.PayrollOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.justjava.humanresource.payroll.repositories.PayrollPeriodRepository;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayrollOrchestrationServiceImpl implements PayrollOrchestrationService {

    private final PayrollRunRepository payrollRunRepository;
    private final EmployeeRepository employeeRepository;
    private final PayrollSetupService payrollSetupService;
    private final PayrollLineItemRepository payrollLineItemRepository;
    private final PayGroupResolutionService payGroupResolutionService;
    private final PayeCalculatorService payeCalculatorService;
    private final PensionSchemeRepository pensionSchemeRepository;
    private final PensionCalculatorService pensionCalculatorService;
    private final EmployeePositionHistoryService employeePositionHistoryService;
    private final PayrollPeriodService payrollPeriodService;
    private final PayrollPeriodRepository payrollPeriodRepository;
    private final KpiMeasurementService kpiMeasurementService;
    private final PayrollRunKpiSnapshotService payrollRunKpiSnapshotService;
    private final PayrollAuditService payrollAuditService;

    /* ============================================================
       INITIALIZE
       ============================================================ */

    @Override
    @Transactional
    public Long initializePayrollRun(
            Long employeeId,
            LocalDate payrollDate,
            LocalDate retroEffectiveDate,
            String processInstanceId) {

    /* ============================================================
       1. LOAD EMPLOYEE
       ============================================================ */

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Employee", employeeId));

        Long companyId = employee
                .getDepartment()
                .getCompany()
                .getId();

    /* ============================================================
       2. VALIDATIONS
       ============================================================ */

        payrollSetupService.validatePayrollSystemReadiness(payrollDate);

        PayrollPeriod openPeriod =
                payrollPeriodService.getOpenPeriod(companyId);

        payrollPeriodService.validatePayrollDate(companyId, payrollDate);

    /* ============================================================
       3. CHECK EXISTING RUNS (LATEST VERSION IN PERIOD)
       ============================================================ */

        Optional<PayrollRun> latestOpt =
                payrollRunRepository
                        .findTopByEmployee_IdAndPeriodStartAndPeriodEndOrderByVersionNumberDesc(
                                employeeId,
                                openPeriod.getPeriodStart(),
                                openPeriod.getPeriodEnd()
                        );

        if (latestOpt.isPresent()) {

            PayrollRun latest = latestOpt.get();

        /* --------------------------------------------------------
           CASE 1: IN-PROGRESS → REUSE (IDEMPOTENCY)
           -------------------------------------------------------- */
            if (latest.getStatus() == PayrollRunStatus.IN_PROGRESS) {
                return latest.getId();
            }

        /* --------------------------------------------------------
           CASE 2: POSTED → CREATE AMENDMENT (IF PERIOD STILL OPEN)
           -------------------------------------------------------- */
            if (latest.getStatus() == PayrollRunStatus.POSTED) {

                if (!payrollPeriodService.isPayrollDateInOpenPeriod(
                        companyId,
                        payrollDate)) {

                    throw new IllegalStateException(
                            "Cannot amend payroll. Period is CLOSED."
                    );
                }

            /* ----------------------------------------------------
               DETERMINE NEXT VERSION (SAFE VERSIONING)
               ---------------------------------------------------- */

                Integer maxVersion =
                        payrollRunRepository.findMaxVersionForEmployeeAndPeriod(
                                employeeId,
                                openPeriod.getPeriodStart(),
                                openPeriod.getPeriodEnd()
                        );

                int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

            /* ----------------------------------------------------
               CREATE AMENDMENT RUN
               ---------------------------------------------------- */

                PayrollRun amendment = new PayrollRun();
                amendment.setEmployee(employee);
                amendment.setPayrollDate(payrollDate);
                amendment.setStatus(PayrollRunStatus.IN_PROGRESS);
                amendment.setRunType(PayrollRunType.AMENDMENT);
                amendment.setVersionNumber(nextVersion);
                amendment.setParentRun(latest);

                amendment.setPeriodStart(openPeriod.getPeriodStart());
                amendment.setPeriodEnd(openPeriod.getPeriodEnd());

                amendment.setGrossPay(BigDecimal.ZERO);
                amendment.setTotalDeductions(BigDecimal.ZERO);
                amendment.setNetPay(BigDecimal.ZERO);
                amendment.setNonGrossEarnings(BigDecimal.ZERO);
                amendment.setFlowableProcessInstanceId(processInstanceId);
                amendment.setFlowableBusinessKey(employee.getId().toString());
                amendment.setPayrollYear(openPeriod.getPeriodStart().getYear());

                // Store retro date only when the change pre-dates the current period
                if (retroEffectiveDate != null
                        && retroEffectiveDate.isBefore(openPeriod.getPeriodStart())) {
                    amendment.setRetroEffectiveDate(retroEffectiveDate);
                }

                PayrollRun savedAmendment = payrollRunRepository.save(amendment);

                payrollAuditService.log(
                        "PayrollRun",
                        savedAmendment.getId(),
                        "AMENDMENT_CREATED",
                        "Amendment v" + nextVersion + " created for employee " + employeeId
                                + " — period " + openPeriod.getPeriodStart()
                                + " to " + openPeriod.getPeriodEnd()
                                + (savedAmendment.getAmendmentReason() != null
                                ? ". Reason: " + savedAmendment.getAmendmentReason()
                                : "")
                );

                return savedAmendment.getId();
            }
        }

    /* ============================================================
       4. NO EXISTING RUN → CREATE ORIGINAL (VERSION = 1)
       ============================================================ */

        PayrollRun run = new PayrollRun();
        run.setEmployee(employee);
        run.setPayrollDate(payrollDate);
        run.setStatus(PayrollRunStatus.IN_PROGRESS);
        run.setRunType(PayrollRunType.ORIGINAL);
        run.setVersionNumber(1);

        run.setPeriodStart(openPeriod.getPeriodStart());
        run.setPeriodEnd(openPeriod.getPeriodEnd());

        run.setGrossPay(BigDecimal.ZERO);
        run.setTotalDeductions(BigDecimal.ZERO);
        run.setNetPay(BigDecimal.ZERO);
        run.setNonGrossEarnings(BigDecimal.ZERO);
        run.setGrossDifference(BigDecimal.ZERO);

        run.setFlowableProcessInstanceId(processInstanceId);
        run.setFlowableBusinessKey(employee.getId().toString());
        run.setPayrollYear(openPeriod.getPeriodStart().getYear());

        // Store retro date only when the change pre-dates the current period
        if (retroEffectiveDate != null
                && retroEffectiveDate.isBefore(openPeriod.getPeriodStart())) {
            run.setRetroEffectiveDate(retroEffectiveDate);
        }

        return payrollRunRepository.save(run).getId();
    }

    /* ============================================================
       CALCULATE EARNINGS
       ============================================================ */

    @Override
    @Transactional
    public void calculateEarnings(Long payrollRunId) {

        PayrollRun run = getEditableRun(payrollRunId);

        Employee employee = run.getEmployee();
        LocalDate payrollDate = run.getPayrollDate();

        EmployeePositionHistory position =
                employeePositionHistoryService
                        .getCurrentPosition(employee.getId());

        JobStep jobStep = position.getJobStep();
        PayGroup payGroup = position.getPayGroup();

    /* ============================================================
       CLEAN PREVIOUS EARNINGS
       ============================================================ */

        payrollLineItemRepository
                .deleteByPayrollRunIdAndComponentType(
                        payrollRunId,
                        PayComponentType.EARNING
                );

    /* ============================================================
       DETERMINE PAY MODEL
       ============================================================ */

        boolean isGrossBased =
                jobStep.getGrossSalary() != null
                        && jobStep.getGrossSalary().compareTo(BigDecimal.ZERO) > 0;

        BigDecimal grossPay;
        BigDecimal runningGross = BigDecimal.ZERO;
        BigDecimal nonGrossEarnings = BigDecimal.ZERO;
        BigDecimal grossDifference = BigDecimal.ZERO;
        BigDecimal taxableIncome = BigDecimal.ZERO;

    /* ============================================================
       INITIALIZE BASE VALUES
       ============================================================ */

        if (isGrossBased) {

        /* --------------------------------------------------------
           GROSS-BASED PAYROLL (NO BASIC AUTO-INJECTION)
           -------------------------------------------------------- */

            BigDecimal configuredGross = jobStep.getGrossSalary();

            YearMonth payrollPeriod = YearMonth.from(payrollDate);

            List<KpiMeasurement> salaryImpactingMeasurements =
                    kpiMeasurementService.getSalaryImpactingMeasurementsForEmployee(
                            employee.getId(),
                            payrollPeriod
                    );

            BigDecimal kpiScore =
                    kpiMeasurementService.calculateSalaryImpactScore(salaryImpactingMeasurements);

            // Snapshot the exact measurements used, for audit-safe payslip display.
            // Writes salaryKpiScore=null and no rows when the list is empty.
            payrollRunKpiSnapshotService.replaceSnapshots(run, salaryImpactingMeasurements, kpiScore);

            System.out.println(" the KPI Score ===" + kpiScore);
            BigDecimal performanceFactor =
                    kpiScore.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

            grossPay = configuredGross
                    .multiply(performanceFactor)
                    .setScale(2, RoundingMode.HALF_UP);

        } else {

        /* --------------------------------------------------------
           BASIC-BASED PAYROLL
           -------------------------------------------------------- */

            // Basic-based payroll is not affected by KPI. Clear any snapshots
            // that might already exist on this run (e.g. a prior gross-based
            // calculation before a pay-model change, or a stale in-progress
            // recalculation), so the payslip never shows an out-of-date
            // Salary KPI section for a basic-based run.
            payrollRunKpiSnapshotService.replaceSnapshots(run, List.of(), null);

            BigDecimal basicSalary = jobStep.getBasicSalary()
                    .setScale(2, RoundingMode.HALF_UP);

            saveLine(run, employee,
                    "BASIC",
                    "Basic Salary",
                    basicSalary,
                    true,
                    true,
                    true,
                    false,                      // outOfPayroll
                    PayComponentType.EARNING);

            grossPay = basicSalary;
            runningGross = basicSalary;
            taxableIncome = basicSalary;
        }

    /* ============================================================
       RESOLVE ALLOWANCES
       ============================================================ */

        ResolvedPayComponents resolved =
                payGroupResolutionService.resolve(
                        payGroup,
                        employee,
                        payrollDate
                );

        for (Allowance allowance : resolved.getAllowances()) {

            BigDecimal amount = computeAllowanceAmount(
                    allowance,
                    runningGross,
                    grossPay,
                    taxableIncome
            );

            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
                continue;

            if (allowance.isProratable()) {
                amount = applyProration(
                        amount,
                        run.getPeriodStart(),
                        run.getPeriodEnd()
                );
            }

            amount = amount.setScale(2, RoundingMode.HALF_UP);


            saveLine(
                    run, employee,
                    allowance.getCode(),
                    allowance.getName(),
                    amount,
                    allowance.isTaxable(),
                    allowance.isPensionable(),
                    allowance.isPartOfGross(),
                    allowance.isOutOfPayroll(),  // passes the actual flag
                    PayComponentType.EARNING
            );

        /* ============================================================
           KEY SPLIT: GROSS vs NON-GROSS vs OUT-OF-PAYROLL
           ============================================================ */

            if (allowance.isOutOfPayroll()) {
                // Completely excluded from all calculations.
                // saveLine() above recorded it for display only — skip all accumulators.

            } else if (allowance.isPartOfGross()) {
                runningGross = runningGross.add(amount);

            } else {
                // Non-gross earning — adds silently to net only (existing behaviour)
                nonGrossEarnings = nonGrossEarnings.add(amount);
            }

            if (!allowance.isOutOfPayroll() && allowance.isTaxable()) {
                taxableIncome = taxableIncome.add(amount);
            }
        }

    /* ============================================================
       FINAL GROSS VALIDATION (FOR GROSS-BASED ONLY)
       ============================================================ */

        if (isGrossBased) {

            System.out.println(" grossPay====" + grossPay + "  runningGross===" + runningGross + "  " +
                    " nonGrossEarnings===" + nonGrossEarnings);
            BigDecimal difference = grossPay.subtract(runningGross);

            if (difference.compareTo(BigDecimal.ZERO) > 0) {

                saveLine(run, employee,
                        "RESIDUAL",
                        "Residual Adjustment",
                        difference,
                        false,
                        false,
                        false,
                        false,                  // outOfPayroll
                        PayComponentType.EARNING);

                grossDifference = difference;
            }

        } else {
            grossPay = runningGross;
        }

    /* ============================================================
       FINAL SET VALUES
       ============================================================ */

        System.out.println(" The nonGrossEarnings ====" + nonGrossEarnings);
        System.out.println(" The runningGross ====" + runningGross);
        System.out.println(" The grossDifference ====" + grossDifference);
        System.out.println(" The grossPay ====" + grossPay);

        run.setGrossPay(runningGross.setScale(2, RoundingMode.HALF_UP));
        run.setNetPay(runningGross.add(nonGrossEarnings).setScale(2, RoundingMode.HALF_UP));
        run.setNonGrossEarnings(nonGrossEarnings.setScale(2, RoundingMode.HALF_UP));
        run.setGrossDifference(grossDifference.setScale(2, RoundingMode.HALF_UP));

        System.out.println("2 The grossPay ====" + grossPay);
        payrollRunRepository.save(run);
        System.out.println("3 The grossPay ====" + grossPay);

        // ── Retro adjustments (catch-up for closed past periods) ───────────────
        if (run.getRetroEffectiveDate() != null
                && run.getRetroEffectiveDate().isBefore(run.getPeriodStart())) {
            applyRetroAdjustments(run, employee);
        }
    }

    /**
     * Adds per-period gross catch-up line items for every CLOSED period
     * between {@code run.retroEffectiveDate} and the current open period.
     *
     * <p>For each closed period the difference between the employee's
     * current monthly gross and what was actually posted is saved as a
     * non-taxable, non-pensionable EARNING line
     * ({@code RETRO_ADJ_YYYY_MM}).  The total is added to
     * {@code nonGrossEarnings} so it flows through to net pay without
     * triggering a PAYE / pension recalculation.</p>
     */
    @Transactional
    protected void applyRetroAdjustments(PayrollRun run, Employee employee) {

        Long companyId = employee.getDepartment().getCompany().getId();

        List<PayrollPeriod> retroPeriods = payrollPeriodRepository
                .findClosedPeriodsBetween(
                        companyId,
                        run.getRetroEffectiveDate(),
                        run.getPeriodStart()
                );

        if (retroPeriods.isEmpty()) {
            log.info("Retro: no closed periods found between {} and {} for company {}",
                    run.getRetroEffectiveDate(), run.getPeriodStart(), companyId);
            return;
        }

        // Current monthly gross (post-recalculation with new salary / allowances)
        BigDecimal newMonthlyGross = run.getGrossPay();

        BigDecimal totalRetroAdj = BigDecimal.ZERO;
        DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("yyyy-MM");

        for (PayrollPeriod period : retroPeriods) {

            Optional<PayrollRun> prevOpt =
                    payrollRunRepository.findLatestPostedRunForEmployeeAndPeriod(
                            employee.getId(),
                            period.getPeriodStart(),
                            period.getPeriodEnd()
                    );

            if (prevOpt.isEmpty()) {
                // No payroll was run in that period for this employee — skip
                log.debug("Retro: no POSTED run for employee {} in period {} – {}; skipping",
                        employee.getId(), period.getPeriodStart(), period.getPeriodEnd());
                continue;
            }

            BigDecimal oldGross = prevOpt.get().getGrossPay();
            BigDecimal adj = newMonthlyGross.subtract(oldGross).setScale(2, RoundingMode.HALF_UP);

            if (adj.compareTo(BigDecimal.ZERO) == 0) continue; // no difference — nothing to add

            String yearMonth  = period.getPeriodStart().format(labelFmt);
            String code       = "RETRO_ADJ_" + yearMonth.replace("-", "_");
            String description = "Retro Adjustment (" + yearMonth + ")";

            saveLine(run, employee,
                    code,
                    description,
                    adj,
                    false,  // not taxable  — tax was already settled in the original period
                    false,  // not pensionable
                    false,  // not part of gross (catch-up flows to net, not back into gross)
                    false,  // not out-of-payroll
                    PayComponentType.EARNING);

            totalRetroAdj = totalRetroAdj.add(adj);
            log.info("Retro: employee {} — {} adj={}", employee.getId(), yearMonth, adj);
        }

        if (totalRetroAdj.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal updatedNonGross =
                    run.getNonGrossEarnings().add(totalRetroAdj).setScale(2, RoundingMode.HALF_UP);
            run.setNonGrossEarnings(updatedNonGross);
            // Provisional net (will be finalised again in applyStatutoryDeductions)
            run.setNetPay(run.getGrossPay().add(updatedNonGross).setScale(2, RoundingMode.HALF_UP));
            payrollRunRepository.save(run);
            log.info("Retro: employee {} total retro adj={} added to nonGrossEarnings",
                    employee.getId(), totalRetroAdj);
        }
    }

    /* ============================================================
       APPLY DEDUCTIONS
       ============================================================ */

    @Override
    @Transactional
    public void applyStatutoryDeductions(Long payrollRunId) {

        PayrollRun run = getEditableRun(payrollRunId);
        Employee employee = run.getEmployee();

    /* ============================================================
       CLEAN ONLY STATUTORY DEDUCTIONS (SAFE FOR RETRIES)
       ============================================================ */

        payrollLineItemRepository.deleteByPayrollRunIdAndComponentCodeIn(
                payrollRunId,
                List.of("PAYE", "PENSION_EMP", "PENSION_EMPLOYER", "TAX_RELIEF")
        );

        BigDecimal totalDeductions = BigDecimal.ZERO;

    /* ============================================================
       FETCH EARNINGS — exclude out-of-payroll lines from all calculations
       ============================================================ */

        List<PayrollLineItem> earningLines =
                payrollLineItemRepository
                        .findByPayrollRunIdAndComponentType(
                                payrollRunId,
                                PayComponentType.EARNING
                        );

        BigDecimal totalEarnings = earningLines.stream()
                .filter(line -> !line.isOutOfPayroll())   // exclude out-of-payroll
                .map(PayrollLineItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        System.out.println(" The total earning ====" + totalEarnings);

        BigDecimal taxableIncome = earningLines.stream()
                .filter(line -> !line.isOutOfPayroll())   // exclude out-of-payroll
                .filter(PayrollLineItem::isTaxable)
                .map(PayrollLineItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        System.out.println(" The total taxableIncome  ====" + taxableIncome);

    /* ============================================================
       PENSION CALCULATION (ONLY PENSIONABLE EARNINGS)
       ============================================================ */

        PensionScheme scheme =
                pensionSchemeRepository
                        .findEffectiveScheme(
                                run.getPayrollDate(),
                                RecordStatus.ACTIVE
                        )
                        .orElse(null);

        BigDecimal employeePension = BigDecimal.ZERO;
        BigDecimal employerPension = BigDecimal.ZERO;

        if (scheme != null) {

            BigDecimal pensionableEarnings = earningLines.stream()
                    .filter(line -> !line.isOutOfPayroll())   // exclude out-of-payroll
                    .filter(PayrollLineItem::isPensionable)
                    .map(PayrollLineItem::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            employeePension =
                    pensionCalculatorService.calculateEmployeeContribution(
                            pensionableEarnings,
                            scheme.getEmployeeRate()
                    ).setScale(2, RoundingMode.HALF_UP);

            employerPension =
                    pensionCalculatorService.calculateEmployerContribution(
                            pensionableEarnings,
                            scheme.getEmployerRate()
                    ).setScale(2, RoundingMode.HALF_UP);

            if (employeePension.compareTo(BigDecimal.ZERO) > 0) {

                saveLine(run, employee,
                        "PENSION_EMP",
                        "Employee Pension",
                        employeePension,
                        false, false, false, false,
                        PayComponentType.DEDUCTION);

                totalDeductions = totalDeductions.add(employeePension);
            }

            if (employerPension.compareTo(BigDecimal.ZERO) > 0) {

                saveLine(run, employee,
                        "PENSION_EMPLOYER",
                        "Employer Pension",
                        employerPension,
                        false, false, false, false,
                        PayComponentType.EMPLOYER_COST);
            }

            run.setAppliedPensionSchemeName(scheme.getName());
        }

    /* ============================================================
       APPLY TAX RELIEFS
       ============================================================ */

        BigDecimal totalReliefs = BigDecimal.ZERO;
        LocalDate payrollDate = run.getPayrollDate();

        EmployeePositionHistory position =
                employeePositionHistoryService
                        .getCurrentPosition(employee.getId());

        PayGroup payGroup = position.getPayGroup();

        ResolvedPayComponents resolved =
                payGroupResolutionService.resolve(
                        payGroup,
                        employee,
                        payrollDate
                );

        BigDecimal fullGross = run.getGrossPay()
                .add(run.getGrossDifference());

        System.out.println(" The Full Gross 1===" + fullGross);
        System.out.println(" The Full Gross 2===" + fullGross + "  run.getNonGrossEarnings()   "
                + run.getNonGrossEarnings());

        fullGross = fullGross.multiply(BigDecimal.valueOf(12));

        for (TaxRelief relief : resolved.getTaxReliefs()) {
            System.out.println(" The Full Gross Annual Figure ===" + fullGross);
            BigDecimal amount = computeReliefAmount(relief, fullGross);

            System.out.println(" TaxRelief amount ===" + amount + " with gross==" + run.getGrossPay()
                    + " and the taxableIncome===" + taxableIncome);

            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
                continue;

            amount = amount.setScale(2, RoundingMode.HALF_UP);

            saveRelief(run, employee,
                    relief.getCode(),
                    relief.getName(),
                    amount);

            totalReliefs = totalReliefs.add(amount);
        }

        System.out.println(" The Total Relief===" + totalReliefs);
        taxableIncome = fullGross.subtract(totalReliefs);

        System.out.println(" The Final Taxable Income====" + taxableIncome);
        if (taxableIncome.compareTo(BigDecimal.ZERO) < 0) {
            taxableIncome = BigDecimal.ZERO;
        }

    /* ============================================================
       PAYE CALCULATION
       ============================================================ */

        BigDecimal paye =
                payeCalculatorService.calculateMonthlyTax(
                        taxableIncome,
                        run.getPayrollDate()
                );

        if (paye.compareTo(BigDecimal.ZERO) > 0) {

            saveLine(run, employee,
                    "PAYE",
                    "PAYE Tax",
                    paye,
                    false, false, false, false,
                    PayComponentType.DEDUCTION);

            totalDeductions = totalDeductions.add(paye);
        }

    /* ============================================================
       FINALIZE
       ============================================================ */

        run.setTotalDeductions(totalDeductions);

        run.setNetPay(
                run.getGrossPay().add(run.getNonGrossEarnings())
                        .subtract(totalDeductions)
                        .setScale(2, RoundingMode.HALF_UP)
        );

        payrollRunRepository.save(run);
    }

    /* ============================================================
       FINALIZE
       ============================================================ */

    @Override
    @Transactional
    public void finalizePayroll(Long payrollRunId) {

        PayrollRun run = payrollRunRepository.findById(payrollRunId)
                .orElseThrow();

        if (run.getStatus() == PayrollRunStatus.POSTED)
            throw new IllegalStateException("Already POSTED.");

        // ----------------------------------------------------
        // 1. Determine Payroll Year
        // ----------------------------------------------------

        int payrollYear = run.getPayrollDate().getYear();
        run.setPayrollYear(payrollYear);

        // ----------------------------------------------------
        // 2. Fetch Previous YTD Snapshot
        // ----------------------------------------------------

        Optional<PayrollRun> previousOpt =
                payrollRunRepository
                        .findTopByEmployee_IdAndPayrollYearAndStatusOrderByPayrollDateDesc(
                                run.getEmployee().getId(),
                                payrollYear,
                                PayrollRunStatus.POSTED
                        );

        BigDecimal previousYtdGross = BigDecimal.ZERO;
        BigDecimal previousYtdTaxable = BigDecimal.ZERO;
        BigDecimal previousYtdDeductions = BigDecimal.ZERO;
        BigDecimal previousYtdNet = BigDecimal.ZERO;
        BigDecimal previousYtdPaye = BigDecimal.ZERO;

        if (previousOpt.isPresent()) {
            PayrollRun previous = previousOpt.get();
            previousYtdGross = previous.getYtdGross();
            previousYtdTaxable = previous.getYtdTaxable();
            previousYtdDeductions = previous.getYtdDeductions();
            previousYtdNet = previous.getYtdNet();
            previousYtdPaye = previous.getYtdPaye();
        }

        // ----------------------------------------------------
        // 3. Compute Current Taxable + PAYE
        // ----------------------------------------------------

        BigDecimal currentTaxable =
                payrollLineItemRepository
                        .sumTaxableEarnings(run.getId());

        BigDecimal currentPaye =
                payrollLineItemRepository
                        .sumByRunAndCode(run.getId(), "PAYE");

        // ----------------------------------------------------
        // 4. Accumulate YTD
        // ----------------------------------------------------

        run.setYtdGross(previousYtdGross.add(run.getGrossPay()));
        run.setYtdTaxable(previousYtdTaxable.add(currentTaxable));
        run.setYtdDeductions(previousYtdDeductions.add(run.getTotalDeductions()));
        run.setYtdNet(previousYtdNet.add(run.getNetPay()));
        run.setYtdPaye(previousYtdPaye.add(currentPaye));

        // ----------------------------------------------------
        // 5. Mark POSTED
        // ----------------------------------------------------

        run.setStatus(PayrollRunStatus.POSTED);
        payrollRunRepository.save(run);

        payrollAuditService.log(
                "PayrollRun",
                run.getId(),
                "PAYROLL_POSTED",
                "Payroll v" + run.getVersionNumber() + " posted for employee "
                        + run.getEmployee().getId()
                        + " — period " + run.getPeriodStart()
                        + " to " + run.getPeriodEnd()
                        + ", net pay: " + run.getNetPay()
                        + (run.getAmendmentReason() != null
                        ? ". Amendment reason: " + run.getAmendmentReason()
                        : "")
        );
    }

    @Override
    @Transactional
    public void applyOtherDeductions(Long payrollRunId) {

        PayrollRun run = getEditableRun(payrollRunId);
        Employee employee = run.getEmployee();

    /* ============================================================
       CLEAN ONLY OTHER DEDUCTIONS (SAFE)
       ============================================================ */

        payrollLineItemRepository.deleteByPayrollRunIdAndComponentTypeAndComponentCodeNotIn(
                payrollRunId,
                PayComponentType.DEDUCTION,
                List.of("PAYE", "PENSION_EMP")
        );

        BigDecimal totalOtherDeductions = BigDecimal.ZERO;

    /* ============================================================
       FETCH PAY GROUP CONFIG
       ============================================================ */

        EmployeePositionHistory position =
                employeePositionHistoryService
                        .getCurrentPosition(employee.getId());

        PayGroup payGroup = position.getPayGroup();

        ResolvedPayComponents resolved =
                payGroupResolutionService.resolve(
                        payGroup,
                        employee,
                        run.getPayrollDate()
                );

    /* ============================================================
       COMPUTATION BASES
       ============================================================ */

        BigDecimal grossPay = run.getGrossPay();

        BigDecimal netBeforeOtherDeductions =
                run.getGrossPay()
                        .subtract(Optional.ofNullable(run.getTotalDeductions())
                                .orElse(BigDecimal.ZERO));

    /* ============================================================
       APPLY OTHER DEDUCTIONS
       ============================================================ */

        for (Deduction deduction : resolved.getDeductions()) {

            BigDecimal amount = computeDeductionAmount(
                    deduction,
                    grossPay,
                    netBeforeOtherDeductions
            );

            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
                continue;

            amount = amount.setScale(2, RoundingMode.HALF_UP);

            saveLine(run, employee,
                    deduction.getCode(),
                    deduction.getName(),
                    amount,
                    false, false, false, false,
                    PayComponentType.DEDUCTION);

            totalOtherDeductions = totalOtherDeductions.add(amount);
        }

    /* ============================================================
       RECOMPUTE TOTAL DEDUCTIONS (SAFE)
       ============================================================ */

        BigDecimal statutoryDeductions =
                payrollLineItemRepository.sumStatutoryDeductions(run.getId());

        BigDecimal totalDeductions =
                statutoryDeductions.add(totalOtherDeductions);

        run.setTotalDeductions(totalDeductions);

        run.setNetPay(
                run.getGrossPay().add(run.getNonGrossEarnings())
                        .subtract(totalDeductions)
                        .setScale(2, RoundingMode.HALF_UP)
        );

        payrollRunRepository.save(run);
    }

    private BigDecimal computeDeductionAmount(
            Deduction deduction,
            BigDecimal gross,
            BigDecimal net) {

        switch (deduction.getCalculationType()) {

            case FIXED_AMOUNT:
                return deduction.getAmount();

            case PERCENTAGE_OF_GROSS:
                return gross.multiply(deduction.getPercentageRate())
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);

            case PERCENTAGE_OF_BASIC:
                return net.multiply(deduction.getPercentageRate())
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);

            default:
                throw new IllegalStateException("Unsupported deduction type");
        }
    }

    /* ============================================================
       INTERNAL UTILITIES
       ============================================================ */

    private PayrollRun getEditableRun(Long id) {

        PayrollRun run = payrollRunRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("PayrollRun", id));

        if (run.getStatus() == PayrollRunStatus.POSTED)
            throw new IllegalStateException(
                    "POSTED payroll cannot be modified.");

        return run;
    }

    private void saveLine(
            PayrollRun run,
            Employee employee,
            String code,
            String description,
            BigDecimal amount,
            boolean taxable,
            boolean pensionable,
            boolean partOfGross,
            boolean outOfPayroll,
            PayComponentType type) {

        PayrollLineItem line = new PayrollLineItem();
        line.setPayrollRun(run);
        line.setEmployee(employee);
        line.setComponentCode(code);
        line.setDescription(description);
        line.setAmount(amount);
        line.setTaxable(taxable);
        line.setPensionable(pensionable);
        line.setPartOfGross(partOfGross);
        line.setOutOfPayroll(outOfPayroll);
        line.setComponentType(type);

        payrollLineItemRepository.save(line);
    }

    private void saveRelief(
            PayrollRun run,
            Employee employee,
            String code,
            String description,
            BigDecimal amount) {

        PayrollLineItem line = new PayrollLineItem();
        line.setPayrollRun(run);
        line.setEmployee(employee);
        line.setComponentCode(code);
        line.setDescription(description);
        line.setAmount(amount);
        line.setTaxable(false);
        line.setPensionable(false);
        line.setTaxRelief(true);
        line.setOutOfPayroll(false);
        line.setComponentType(PayComponentType.TAX_RELIEF);

        payrollLineItemRepository.save(line);
    }

    private BigDecimal computeAllowanceAmount(
            Allowance allowance,
            BigDecimal basic,
            BigDecimal gross,
            BigDecimal taxable) {

        switch (allowance.getCalculationType()) {

            case FIXED_AMOUNT:
                return allowance.getAmount();

            case PERCENTAGE_OF_BASIC:
                return basic.multiply(allowance.getPercentageRate())
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);

            case PERCENTAGE_OF_GROSS:
                return gross.multiply(allowance.getPercentageRate())
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);

            case PERCENTAGE_OF_TAXABLE:
                return taxable.multiply(allowance.getPercentageRate())
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);

            case FORMULA:
                return evaluateFormula(
                        allowance.getFormulaExpression(),
                        basic,
                        gross,
                        taxable
                );

            default:
                throw new IllegalStateException("Unsupported type");
        }
    }

    private BigDecimal evaluateFormula(
            String expression,
            BigDecimal basic,
            BigDecimal gross,
            BigDecimal taxable) {

        try {

        /* ============================================================
           1. PREPROCESS EXPRESSION
           ============================================================ */

            String processed = preprocessExpression(expression);

        /* ============================================================
           2. INIT ENGINE
           ============================================================ */

            ScriptEngine engine =
                    new ScriptEngineManager()
                            .getEngineByName("JavaScript");

        /* ============================================================
           3. VARIABLES
           ============================================================ */

            engine.put("BASIC", basic);
            engine.put("GROSS", gross);
            engine.put("TAXABLE", taxable);

        /* ============================================================
           4. HELPER FUNCTIONS (Payroll DSL)
           ============================================================ */

            engine.eval("""
            function MIN(a,b){ return Math.min(a,b); }
            function MAX(a,b){ return Math.max(a,b); }
            function CAP(value, cap){ return Math.min(value, cap); }
            function FLOOR(value, floor){ return Math.max(value, floor); }
            function IF(cond, a, b){ return cond ? a : b; }
        """);

        /* ============================================================
           5. EXECUTE
           ============================================================ */

            Object result = engine.eval(processed);

            return new BigDecimal(result.toString());

        } catch (Exception e) {
            throw new IllegalStateException("Invalid formula: " + expression, e);
        }
    }

    private String preprocessExpression(String expr) {

        if (expr == null) return null;

        String processed = expr.trim();

    /* ============================================================
       1. NORMALIZE CASE
       ============================================================ */

        processed = processed.toUpperCase();

    /* ============================================================
       2. HANDLE "OF" SYNTAX
       e.g. 10% OF GROSS → (10%)*(GROSS)
       ============================================================ */

        processed = processed.replaceAll(
                "(\\d+(\\.\\d+)?%?)\\s+OF\\s+\\(",
                "($1)*("
        );

        processed = processed.replaceAll(
                "(\\d+(\\.\\d+)?%?)\\s+OF\\s+([A-Z_]+)",
                "($1)*($3)"
        );

    /* ============================================================
       3. HANDLE PERCENTAGES
       e.g. 10% → (10/100)
       ============================================================ */

        processed = processed.replaceAll(
                "(\\d+(\\.\\d+)?)%",
                "($1/100)"
        );

    /* ============================================================
       4. HANDLE LOGICAL OPERATORS
       ============================================================ */

        processed = processed
                .replaceAll("\\bAND\\b", "&&")
                .replaceAll("\\bOR\\b", "||")
                .replaceAll("\\bNOT\\b", "!");

        return processed;
    }

    private BigDecimal applyProration(
            BigDecimal amount,
            LocalDate periodStart,
            LocalDate periodEnd) {

        long totalDays =
                ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
        if (totalDays <= 0) return amount;

        return amount
                .divide(BigDecimal.valueOf(totalDays), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(totalDays));
    }

    private PayrollRun createAmendmentRun(PayrollRun original) {

        PayrollRun amendment = new PayrollRun();
        amendment.setEmployee(original.getEmployee());
        amendment.setPayrollDate(original.getPayrollDate());
        amendment.setStatus(PayrollRunStatus.IN_PROGRESS);
        amendment.setRunType(PayrollRunType.AMENDMENT);
        amendment.setParentRun(original);
        amendment.setVersionNumber(original.getVersionNumber() + 1);
        amendment.setPeriodStart(original.getPeriodStart());
        amendment.setPeriodEnd(original.getPeriodEnd());
        amendment.setGrossPay(BigDecimal.ZERO);
        amendment.setNetPay(BigDecimal.ZERO);

        return payrollRunRepository.save(amendment);
    }

    private BigDecimal computeReliefAmount(
            TaxRelief relief,
            BigDecimal gross) {

        switch (relief.getCalculationType()) {

            case FIXED_AMOUNT:
                return relief.getAmount();

            case PERCENTAGE_OF_GROSS:
                BigDecimal result = gross.multiply(relief.getPercentageRate())
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
                System.out.println(" Inside PERCENTAGE_OF_GROSS GROSS==" + gross +
                        " the relief percent===" + relief.getPercentageRate() +
                        " and the result sending out===" + result);
                return result;

            case FORMULA:
                BigDecimal formulaResult = evaluateFormula(
                        relief.getFormulaExpression(),
                        BigDecimal.ZERO,
                        gross,
                        gross
                );
                System.out.println(" Inside FORMULA  GROSS==" + gross +
                        " the relief percent===" + relief.getFormulaExpression() +
                        " and the result sending out===" + formulaResult);
                return formulaResult;

            default:
                throw new IllegalStateException("Unsupported relief type");
        }
    }
}