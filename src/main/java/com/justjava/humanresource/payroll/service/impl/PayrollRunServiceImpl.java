package com.justjava.humanresource.payroll.service.impl;

import com.justjava.humanresource.core.enums.PayrollRunStatus;
import com.justjava.humanresource.payroll.dto.PayrollItemDTO;
import com.justjava.humanresource.payroll.dto.PayrollRunDTO;
import com.justjava.humanresource.payroll.dto.SalaryImpactKpiSnapshotDTO;
import com.justjava.humanresource.payroll.entity.PayrollLineItem;
import com.justjava.humanresource.payroll.entity.PayrollPeriod;
import com.justjava.humanresource.payroll.entity.PayrollRun;
import com.justjava.humanresource.payroll.enums.PayComponentType;
import com.justjava.humanresource.payroll.enums.PayrollPeriodStatus;
import com.justjava.humanresource.payroll.report.dto.*;
import com.justjava.humanresource.payroll.repositories.PayrollLineItemRepository;
import com.justjava.humanresource.payroll.repositories.PayrollPeriodRepository;
import com.justjava.humanresource.payroll.repositories.PayrollRunRepository;
import com.justjava.humanresource.payroll.service.PayrollRunKpiSnapshotService;
import com.justjava.humanresource.payroll.service.PayrollRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PayrollRunServiceImpl implements PayrollRunService {

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollPeriodRepository payrollPeriodRepository;
    private final PayrollLineItemRepository payrollLineItemRepository;
    private final PayrollRunKpiSnapshotService payrollRunKpiSnapshotService;

    @Override
    public PayrollRunDTO getPayrollRun(Long payrollRunId) {

        PayrollRun run = payrollRunRepository.findById(payrollRunId)
                .orElseThrow(() -> new IllegalStateException("PayrollRun not found"));

        return buildDto(run);
    }

    @Override
    public PayrollRunDTO getEmployeePayrollRun(Long employeeId,Long companyId) {
        PayrollPeriod current =
                payrollPeriodRepository
                        .findByCompanyIdAndStatusIn(
                                companyId,
                                List.of(
                                        PayrollPeriodStatus.OPEN,
                                        PayrollPeriodStatus.LOCKED
                                )
                        )
                        .orElse(null);
        System.out.println("Current period for company " + companyId + ": " + (current != null ? current.getId() : "None"));

/*        if (current == null) {
            return Collections.emptyList();
        }*/
        PayrollRun run = payrollRunRepository
                .findTopByEmployeeIdAndPeriodEndOrderByVersionNumberDesc(
                        employeeId,
                        current.getPeriodEnd()
                )
                .orElseThrow(() -> new IllegalStateException("PayrollRun not found"));

        return buildDto(run);
    }
    @Override
    public List<PayrollRunDTO> getCurrentPeriodPayrollRuns(Long companyId) {

        PayrollPeriod current =
                payrollPeriodRepository
                        .findByCompanyIdAndStatusIn(
                                companyId,
                                List.of(
                                        PayrollPeriodStatus.OPEN,
                                        PayrollPeriodStatus.LOCKED
                                )
                        )
                        .orElse(null);

        if (current == null) {
            return Collections.emptyList();
        }

        return payrollRunRepository
                .findLatestPayrollRunsForPeriod(
                        companyId,
                        current.getPeriodStart(),
                        current.getPeriodEnd()
                );
    }
    @Override
    public List<PayrollRunDTO> getPayrollRunsForPeriod(
            Long companyId,
            LocalDate periodStart,
            LocalDate periodEnd) {

        List<PayrollRun> runs =
                payrollRunRepository
                        .findLatestByCompanyAndPeriodAndStatus(
                                companyId,
                                periodStart,
                                periodEnd,
                                PayrollRunStatus.POSTED
                        );

        return runs.stream()
                .map(this::buildDto)
                .collect(Collectors.toList());
    }

    private PayrollRunDTO buildDto(PayrollRun run) {

        List<PayrollLineItem> items =
                payrollLineItemRepository.findByPayrollRunId(run.getId());

        List<PayrollItemDTO> allowances =
                items.stream()
                        .filter(i -> i.getComponentType() == PayComponentType.EARNING)
                        .map(this::mapItem)
                        .collect(Collectors.toList());


        List<PayrollItemDTO> deductions =
                items.stream()
                        .filter(i -> i.getComponentType() == PayComponentType.DEDUCTION)
                        .map(this::mapItem)
                        .collect(Collectors.toList());

        double paye =
                items.stream()
                        .filter(i -> "PAYE".equals(i.getComponentCode()))
                        .mapToDouble(i -> i.getAmount().doubleValue())
                        .sum();

        // Match resiliently — same fallback the PAYE & Pension report already
        // uses elsewhere in this codebase, since the exact component code can
        // vary (PENSION, PENSION_EMP, or just a description containing "pension").
        double pension =
                items.stream()
                        .filter(i -> i.getComponentType() == PayComponentType.DEDUCTION)
                        .filter(i -> {
                            String code = i.getComponentCode() != null ? i.getComponentCode().trim() : "";
                            String desc = i.getDescription() != null ? i.getDescription().toLowerCase() : "";
                            return "PENSION".equalsIgnoreCase(code)
                                    || "PENSION_EMP".equalsIgnoreCase(code)
                                    || desc.contains("pension");
                        })
                        .mapToDouble(i -> i.getAmount().doubleValue())
                        .sum();

        // Display-only figure — not a calculation, just the employer-side pension
        // contribution mirrored the same way the PAYE & Pension report derives it
        // (employer pension = employee pension x 1.25).
        double employerPension = pension * 1.25;

        // Audit-safe KPI display: read from the payroll-run KPI snapshot only,
        // never from live KpiMeasurement/KpiDefinition data. This is what keeps
        // the employee self-service current payroll page showing exactly what
        // was used at calculation time, even for past runs.
        List<SalaryImpactKpiSnapshotDTO> salaryImpactKpis =
                payrollRunKpiSnapshotService.getSnapshotsForPayrollRun(run.getId());

        return PayrollRunDTO.builder()
                .payrollRunId(run.getId())
                .employeeId(run.getEmployee().getId())
                .employeeNumber(run.getEmployee().getEmployeeNumber())
                .employeeName(run.getEmployee().getFirstName() + " " + run.getEmployee().getLastName())
                .payrollDate(run.getPayrollDate())
                .periodStart(run.getPeriodStart())
                .periodEnd(run.getPeriodEnd())
                .grossPay(run.getGrossPay())
                .totalDeductions(run.getTotalDeductions())
                .netPay(run.getNetPay())
                .paye(java.math.BigDecimal.valueOf(paye))
                .pension(java.math.BigDecimal.valueOf(pension))
                .employerPension(java.math.BigDecimal.valueOf(employerPension))
                .ytdGross(run.getYtdGross())
                .ytdNet(run.getYtdNet())
                .ytdPaye(run.getYtdPaye())
                .pensionScheme(run.getAppliedPensionSchemeName())
                .salaryKpiScore(run.getSalaryKpiScore())
                .salaryImpactKpis(salaryImpactKpis)
                .allowances(allowances)
                .deductions(deductions)
                .build();

    }

    private PayrollItemDTO mapItem(PayrollLineItem item) {

        return PayrollItemDTO.builder()
                .code(item.getComponentCode())
                .description(item.getDescription())
                .amount(item.getAmount())
                .taxable(item.isTaxable())
                .outOfPayroll(item.isOutOfPayroll())
                .build();
    }
    @Override
    public List<PayrollSummaryDTO> getPayrollSummary(
            Long companyId,
            LocalDate start,
            LocalDate end) {


        System.out.println(" start date ==="+start + " end date ==="+end);

        return payrollRunRepository.getPayrollSummary(companyId, start, end);
    }

    @Override
    public List<ComponentBreakdownDTO> getEarningsBreakdown(
            Long companyId,
            LocalDate start,
            LocalDate end) {

        return payrollRunRepository.getEarningsBreakdown(companyId, start, end);
    }

    @Override
    public List<ComponentBreakdownDTO> getDeductionBreakdown(
            Long companyId,
            LocalDate start,
            LocalDate end) {

        return payrollRunRepository.getDeductionBreakdown(companyId, start, end);
    }

    public List<ComponentTrendDTO> getComponentTrend(Long companyId) {

        List<Object[]> rows = payrollRunRepository.getComponentTrendRaw(companyId);

        return rows.stream()
                .map(r -> new ComponentTrendDTO(
                        (String) r[0],
                        (String) r[1],
                        (BigDecimal) r[2]
                ))
                .toList();
    }

    @Override
    public List<PayeReportDTO> getPayeReport(
            Long companyId,
            LocalDate start,
            LocalDate end) {

        return payrollRunRepository.getPayeReport(companyId, start, end);
    }

    @Override
    public List<PensionReportDTO> getPensionReport(
            Long companyId,
            LocalDate start,
            LocalDate end) {

        return payrollRunRepository.getPensionReport(companyId, start, end);
    }


    //  FOR THE NEW JOBHR SORTING
    @Override
    public List<PayrollSummaryDTO> getPayrollSummaryForEmployees(
            Long companyId, LocalDate start, LocalDate end, List<Long> employeeIds) {
        return payrollRunRepository.getPayrollSummaryForEmployees(companyId, start, end, employeeIds);
    }

    @Override
    public List<ComponentBreakdownDTO> getEarningsBreakdownForEmployees(
            Long companyId, LocalDate start, LocalDate end, List<Long> employeeIds) {
        return payrollRunRepository.getEarningsBreakdownForEmployees(companyId, start, end, employeeIds);
    }

    @Override
    public List<ComponentBreakdownDTO> getDeductionBreakdownForEmployees(
            Long companyId, LocalDate start, LocalDate end, List<Long> employeeIds) {
        return payrollRunRepository.getDeductionBreakdownForEmployees(companyId, start, end, employeeIds);
    }

    @Override
    public List<ComponentTrendDTO> getComponentTrendForEmployees(
            Long companyId, List<Long> employeeIds) {
        List<Object[]> rows = payrollRunRepository.getComponentTrendRawForEmployees(companyId, employeeIds);
        return rows.stream()
                .map(r -> new ComponentTrendDTO(
                        (String) r[0],
                        (String) r[1],
                        (BigDecimal) r[2]
                ))
                .toList();
    }
}