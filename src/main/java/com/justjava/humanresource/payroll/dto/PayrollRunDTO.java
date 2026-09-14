package com.justjava.humanresource.payroll.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollRunDTO {

    /* =========================
       CORE PAYROLL RUN INFO
       ========================= */

    private Long payrollRunId;
    private Integer versionNumber;

    /* =========================
       EMPLOYEE DETAILS
       ========================= */

    private Long employeeId;
    private String employeeNumber;
    private String employeeName;

    /* =========================
       PAYROLL PERIOD
       ========================= */

    private LocalDate payrollDate;
    private LocalDate periodStart;
    private LocalDate periodEnd;

    /* =========================
       PAYROLL TOTALS
       ========================= */

    private BigDecimal grossPay = BigDecimal.ZERO;
    private BigDecimal totalDeductions = BigDecimal.ZERO;
    private BigDecimal netPay = BigDecimal.ZERO;

    /* =========================
       STATUTORY BREAKDOWN
       ========================= */

    private BigDecimal paye = BigDecimal.ZERO;
    private BigDecimal pension = BigDecimal.ZERO;
    private BigDecimal employerPension = BigDecimal.ZERO;   // ✅ NEW — display only, not part of any deduction/total
    private BigDecimal basicSalary = BigDecimal.ZERO;
    private String pensionScheme;

    /* =========================
       YEAR-TO-DATE SNAPSHOT
       ========================= */

    private BigDecimal ytdGross = BigDecimal.ZERO;
    private BigDecimal ytdNet = BigDecimal.ZERO;
    private BigDecimal ytdPaye = BigDecimal.ZERO;

    /* =========================
       SALARY KPI (audit snapshot display only — never part of totals)
       ========================= */


    private BigDecimal salaryKpiScore;

    private List<SalaryImpactKpiSnapshotDTO> salaryImpactKpis;

    /* =========================
       PAYROLL COMPONENTS
       ========================= */

    private List<PayrollItemDTO> allowances;
    private List<PayrollItemDTO> deductions;

    /* =========================
       JPQL PROJECTION CONSTRUCTOR
       Used by repository queries
       ========================= */

    public PayrollRunDTO(
            Long payrollRunId,
            Long employeeId,
            String employeeNumber,
            String employeeName,
            LocalDate payrollDate,
            LocalDate periodStart,
            LocalDate periodEnd,
            BigDecimal grossPay,
            BigDecimal totalDeductions,
            BigDecimal netPay
    ) {
        this.payrollRunId = payrollRunId;
        this.employeeId = employeeId;
        this.employeeNumber = employeeNumber;
        this.employeeName = employeeName;
        this.payrollDate = payrollDate;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.grossPay = grossPay;
        this.totalDeductions = totalDeductions;
        this.netPay = netPay;

    }


    public boolean hasSalaryImpactKpi() {
        return salaryImpactKpis != null && !salaryImpactKpis.isEmpty();
    }
}