package com.justjava.humanresource.payroll.entity;

import com.justjava.humanresource.core.enums.PayrollRunStatus;
import com.justjava.humanresource.payroll.dto.FutureEmployeeAllowanceDTO;
import com.justjava.humanresource.payroll.dto.SalaryImpactKpiSnapshotDTO;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Value
@Builder
public class PaySlipDTO {
    Long id;
    Long employeeId;
    String employeeName;
    Long payrollRunId;
    LocalDate payDate;
    Integer versionNumber;

    /* ======================
       SUMMARY SNAPSHOT
       ====================== */

    BigDecimal basicSalary;
    BigDecimal grossPay;
    BigDecimal totalDeductions;
    BigDecimal netPay;

    /* ======================
       BREAKDOWN
       ====================== */

    List<PaySlipLineDTO> allowances;
    List<PaySlipLineDTO> deductions;
    List<FutureEmployeeAllowanceDTO> futureAllowances;

    /* ======================
       STATUTORY SNAPSHOT
       ====================== */

    String appliedTaxBandSummary;
    String appliedPensionSchemeName;
    BigDecimal pensionAmount;
    BigDecimal employerPensionAmount;   // ✅ NEW — display only, not part of any deduction/total
    PayrollRunStatus status;
    String bankName;
    String bankAccountNumber;


    BigDecimal salaryKpiScore;

    List<SalaryImpactKpiSnapshotDTO> salaryImpactKpis;

    /* ======================
       PDF HEADER (job grade + company logo)
       ====================== */

    String jobGradeName;
    byte[] companyLogoData;
    String companyLogoContentType;


    public boolean hasSalaryImpactKpi() {
        return salaryImpactKpis != null && !salaryImpactKpis.isEmpty();
    }
}