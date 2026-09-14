package com.justjava.humanresource.payroll.repositories;

import com.justjava.humanresource.core.enums.PayrollRunStatus;
import com.justjava.humanresource.payroll.dto.EmployeeReportItemDTO;
import com.justjava.humanresource.payroll.dto.PayrollRunDTO;
import com.justjava.humanresource.payroll.entity.PayrollRun;
import com.justjava.humanresource.payroll.report.dto.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;



import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PayrollRunRepository
        extends JpaRepository<PayrollRun, Long> {

    /* ============================================================
       DUPLICATE PREVENTION
       ============================================================ */

    Optional<PayrollRun>
    findTopByEmployeeIdAndPeriodEndOrderByVersionNumberDesc(
            Long employeeId,
            LocalDate payrollDate
    );

    /* ============================================================
       COMPANY-SCOPED PERIOD VALIDATION
       ============================================================ */

    @Query("""
    SELECT COUNT(p)
    FROM PayrollRun p
    WHERE p.employee.department.company.id = :companyId
      AND p.payrollDate BETWEEN :start AND :end
      AND p.status = :status
      AND p.versionNumber = (
          SELECT MAX(p2.versionNumber)
          FROM PayrollRun p2
          WHERE p2.employee.id = p.employee.id
          AND p2.payrollDate = p.payrollDate
      )
""")
    long countLatestByCompanyAndPayrollDateBetweenAndStatus(
            @Param("companyId") Long companyId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("status") PayrollRunStatus status
    );

    @Query("""
    SELECT COUNT(p)
    FROM PayrollRun p
    WHERE p.employee.department.company.id = :companyId
      AND p.payrollDate BETWEEN :start AND :end
      AND (
          p.employee.suspensionFrom IS NULL
          OR p.employee.suspensionFrom > :end
          OR (p.employee.suspensionTo IS NOT NULL AND p.employee.suspensionTo < :start)
      )
      AND p.versionNumber = (
          SELECT MAX(p2.versionNumber)
          FROM PayrollRun p2
          WHERE p2.employee.id = p.employee.id
          AND p2.payrollDate = p.payrollDate
      )
      AND p.status <> :status
""")
    long countLatestByCompanyAndPayrollDateBetweenAndStatusNot(
            @Param("companyId") Long companyId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("status") PayrollRunStatus status
    );

    /* ============================================================
       COMPANY-SCOPED FETCH
       ============================================================ */

    @Query("""
    SELECT p
    FROM PayrollRun p
    WHERE p.employee.department.company.id = :companyId
      AND p.periodStart = :start
      AND p.periodEnd = :end
      AND p.status = :status
      AND p.versionNumber = (
          SELECT MAX(p2.versionNumber)
          FROM PayrollRun p2
          WHERE p2.employee.id = p.employee.id
          AND p2.periodStart = p.periodStart
          AND p2.periodEnd = p.periodEnd
      )
""")
    List<PayrollRun> findLatestByCompanyAndPeriodAndStatus(
            Long companyId,
            LocalDate start,
            LocalDate end,
            PayrollRunStatus status
    );

    List<PayrollRun>
    findByEmployee_Department_Company_IdAndPayrollDateBetween(
            Long companyId,
            LocalDate start,
            LocalDate end
    );

    /* ============================================================
       FINANCIAL AGGREGATION (COMPANY SAFE)
       ============================================================ */

    @Query("""
    SELECT COALESCE(SUM(p.grossPay), 0)
    FROM PayrollRun p
    WHERE p.employee.department.company.id = :companyId
      AND p.periodStart = :start
      AND p.periodEnd = :end
      AND p.status = :status
      AND p.versionNumber = (
          SELECT MAX(p2.versionNumber)
          FROM PayrollRun p2
          WHERE p2.employee.id = p.employee.id
          AND p2.periodStart = p.periodStart
          AND p2.periodEnd = p.periodEnd
      )
""")
    BigDecimal sumGrossByCompanyAndPeriodAndStatus(
            Long companyId,
            LocalDate start,
            LocalDate end,
            PayrollRunStatus status
    );

    @Query("""
    SELECT COALESCE(SUM(p.totalDeductions), 0)
    FROM PayrollRun p
    WHERE p.employee.department.company.id = :companyId
      AND p.periodStart = :start
      AND p.periodEnd = :end
      AND p.status = :status
      AND p.versionNumber = (
          SELECT MAX(p2.versionNumber)
          FROM PayrollRun p2
          WHERE p2.employee.id = p.employee.id
          AND p2.periodStart = p.periodStart
          AND p2.periodEnd = p.periodEnd
      )
""")
    BigDecimal sumDeductionsByCompanyAndPeriodAndStatus(
            Long companyId,
            LocalDate start,
            LocalDate end,
            PayrollRunStatus status
    );

    @Query("""
    SELECT COALESCE(SUM(p.netPay), 0)
    FROM PayrollRun p
    WHERE p.employee.department.company.id = :companyId
      AND p.payrollDate BETWEEN :start AND :end
      AND p.status = :status
      AND p.versionNumber = (
          SELECT MAX(p2.versionNumber)
          FROM PayrollRun p2
          WHERE p2.employee.id = p.employee.id
          AND p2.payrollDate = p.payrollDate
      )
""")
    BigDecimal sumNetByCompanyAndPeriodAndStatus(
            @Param("companyId") Long companyId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("status") PayrollRunStatus status
    );

    @Query("""
    SELECT COUNT(p)
    FROM PayrollRun p
    WHERE p.employee.department.company.id = :companyId
      AND p.payrollDate BETWEEN :start AND :end
      AND p.status <> :status
""")
    long countByCompanyIdAndPayrollDateBetweenAndStatusNot(
            @Param("companyId") Long companyId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("status") PayrollRunStatus status
    );

    @Query("""
    SELECT COUNT(p)
    FROM PayrollRun p
    WHERE p.employee.department.company.id = :companyId
      AND p.payrollDate BETWEEN :start AND :end
      AND p.status = :status
""")
    long countByCompanyIdAndPayrollDateBetweenAndStatus(
            @Param("companyId") Long companyId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("status") PayrollRunStatus status
    );

    @Modifying
    @Query(value = """
INSERT INTO payroll_runs (
    employee_id,
    payroll_date,
    period_start,
    period_end,
    status,
    flowable_process_instance_id,
    applied_pension_scheme_name,
    payroll_year,
    run_type,
    version_number,
    gross_pay,
    non_gross_earnings,
    gross_difference,
    total_deductions,
    net_pay,
    ytd_deductions,
    ytd_gross,
    ytd_net,
    ytd_paye,
    ytd_taxable,
    parent_run_id,
    created_at,
    updated_at
)
SELECT
    pr.employee_id,
    :newPeriodEnd,
    :newPeriodStart,
    :newPeriodEnd,
    'POSTED',
    pr.flowable_process_instance_id,
    pr.applied_pension_scheme_name,
    pr.payroll_year,
    'ORIGINAL',
    1,
    pr.gross_pay,
    pr.non_gross_earnings,
    pr.gross_difference,
    pr.total_deductions,
    pr.net_pay,
    pr.ytd_deductions,
    pr.ytd_gross,
    pr.ytd_net,
    pr.ytd_paye,
    pr.ytd_taxable,
    pr.id,
    NOW(),
    NOW()
FROM payroll_runs pr
JOIN employees e ON e.id = pr.employee_id
JOIN departments d ON d.id = e.department_id
WHERE d.company_id = :companyId
AND pr.period_start = :oldPeriodStart
AND pr.period_end = :oldPeriodEnd
AND pr.status = 'POSTED'
AND pr.version_number = (
    SELECT MAX(pr2.version_number)
    FROM payroll_runs pr2
    WHERE pr2.employee_id = pr.employee_id
    AND pr2.period_start = pr.period_start
    AND pr2.period_end = pr.period_end
)
LIMIT :limit OFFSET :offset
""", nativeQuery = true)
    int bulkCarryForwardChunk(
            Long companyId,
            LocalDate oldPeriodStart,
            LocalDate oldPeriodEnd,
            LocalDate newPeriodStart,
            LocalDate newPeriodEnd,
            int limit,
            int offset
    );

    long countByEmployee_Department_Company_IdAndPayrollDateBetween(
            Long companyId,
            LocalDate start,
            LocalDate end
    );

    Optional<PayrollRun>
    findTopByEmployee_IdAndPayrollYearAndStatusOrderByPayrollDateDesc(
            Long employeeId,
            Integer payrollYear,
            PayrollRunStatus status
    );

    @Query("""
SELECT new com.justjava.humanresource.payroll.dto.PayrollRunDTO(
    pr.id,
    e.id,
    e.employeeNumber,
    CONCAT(e.firstName,' ',e.lastName),
    pr.payrollDate,
    pr.periodStart,
    pr.periodEnd,
    pr.grossPay,
    pr.totalDeductions,
    pr.netPay
)
FROM PayrollRun pr
JOIN pr.employee e
WHERE e.department.company.id = :companyId
AND pr.periodStart = :periodStart
AND pr.periodEnd = :periodEnd
AND pr.versionNumber = (
        SELECT MAX(pr2.versionNumber)
        FROM PayrollRun pr2
        WHERE pr2.employee.id = pr.employee.id
        AND pr2.payrollDate = pr.payrollDate
)
""")
    List<PayrollRunDTO> findLatestPayrollRunsForPeriod(
            Long companyId,
            LocalDate periodStart,
            LocalDate periodEnd
    );

    /* ============================================================
       PAYROLL SUMMARY
       ============================================================ */

    @Query("""
SELECT new com.justjava.humanresource.payroll.report.dto.PayrollSummaryDTO(
    d.name,
    SUM(pr.grossPay),
    SUM(pr.totalDeductions),
    SUM(pr.netPay),
    (
        SELECT COALESCE(SUM(li1.amount), 0)
        FROM PayrollLineItem li1
        WHERE li1.payrollRun.id IN (
            SELECT prx.id FROM PayrollRun prx
            JOIN PayrollPeriod pp1
                ON pp1.periodStart = prx.periodStart
               AND pp1.periodEnd = prx.periodEnd
            WHERE prx.employee.department.id = d.id
            AND prx.periodStart >= :start
            AND prx.periodEnd <= :end
            AND prx.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
            AND pp1.status = com.justjava.humanresource.payroll.enums.PayrollPeriodStatus.CLOSED
            AND prx.versionNumber = (
                SELECT MAX(pr2.versionNumber)
                FROM PayrollRun pr2
                WHERE pr2.employee.id = prx.employee.id
                AND pr2.periodStart = prx.periodStart
                AND pr2.periodEnd = prx.periodEnd
            )
        )
        AND li1.componentCode = 'PAYE'
    ),
    (
        SELECT COALESCE(SUM(li2.amount), 0)
        FROM PayrollLineItem li2
        WHERE li2.payrollRun.id IN (
            SELECT pry.id FROM PayrollRun pry
            JOIN PayrollPeriod pp2
                ON pp2.periodStart = pry.periodStart
               AND pp2.periodEnd = pry.periodEnd
            WHERE pry.employee.department.id = d.id
            AND pry.periodStart >= :start
            AND pry.periodEnd <= :end
            AND pry.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
            AND pp2.status = com.justjava.humanresource.payroll.enums.PayrollPeriodStatus.CLOSED
            AND pry.versionNumber = (
                SELECT MAX(pr3.versionNumber)
                FROM PayrollRun pr3
                WHERE pr3.employee.id = pry.employee.id
                AND pr3.periodStart = pry.periodStart
                AND pr3.periodEnd = pry.periodEnd
            )
        )
        AND li2.componentCode = 'PENSION_EMP'
    )
)
FROM PayrollRun pr
JOIN pr.employee e
JOIN e.department d
JOIN PayrollPeriod pp
    ON pp.periodStart = pr.periodStart
   AND pp.periodEnd = pr.periodEnd
WHERE d.company.id = :companyId
AND pr.periodStart >= :start
AND pr.periodEnd <= :end
AND pr.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
AND pp.status = com.justjava.humanresource.payroll.enums.PayrollPeriodStatus.CLOSED
AND pr.versionNumber = (
    SELECT MAX(pr2.versionNumber)
    FROM PayrollRun pr2
    WHERE pr2.employee.id = pr.employee.id
    AND pr2.periodStart = pr.periodStart
    AND pr2.periodEnd = pr.periodEnd
)
GROUP BY d.id, d.name
""")
    List<PayrollSummaryDTO> getPayrollSummary(
            Long companyId,
            LocalDate start,
            LocalDate end
    );

    // ── Scoped variant of getPayrollSummary ──────────────────────────────────
    @Query("""
SELECT new com.justjava.humanresource.payroll.report.dto.PayrollSummaryDTO(
    d.name,
    SUM(pr.grossPay),
    SUM(pr.totalDeductions),
    SUM(pr.netPay),
    (
        SELECT COALESCE(SUM(li1.amount), 0)
        FROM PayrollLineItem li1
        WHERE li1.payrollRun.id IN (
            SELECT prx.id FROM PayrollRun prx
            JOIN PayrollPeriod pp1
                ON pp1.periodStart = prx.periodStart
               AND pp1.periodEnd = prx.periodEnd
            WHERE prx.employee.department.id = d.id
            AND prx.employee.id IN :employeeIds
            AND prx.periodStart >= :start
            AND prx.periodEnd <= :end
            AND prx.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
            AND pp1.status = com.justjava.humanresource.payroll.enums.PayrollPeriodStatus.CLOSED
            AND prx.versionNumber = (
                SELECT MAX(pr2.versionNumber)
                FROM PayrollRun pr2
                WHERE pr2.employee.id = prx.employee.id
                AND pr2.periodStart = prx.periodStart
                AND pr2.periodEnd = prx.periodEnd
            )
        )
        AND li1.componentCode = 'PAYE'
    ),
    (
        SELECT COALESCE(SUM(li2.amount), 0)
        FROM PayrollLineItem li2
        WHERE li2.payrollRun.id IN (
            SELECT pry.id FROM PayrollRun pry
            JOIN PayrollPeriod pp2
                ON pp2.periodStart = pry.periodStart
               AND pp2.periodEnd = pry.periodEnd
            WHERE pry.employee.department.id = d.id
            AND pry.employee.id IN :employeeIds
            AND pry.periodStart >= :start
            AND pry.periodEnd <= :end
            AND pry.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
            AND pp2.status = com.justjava.humanresource.payroll.enums.PayrollPeriodStatus.CLOSED
            AND pry.versionNumber = (
                SELECT MAX(pr3.versionNumber)
                FROM PayrollRun pr3
                WHERE pr3.employee.id = pry.employee.id
                AND pr3.periodStart = pry.periodStart
                AND pr3.periodEnd = pry.periodEnd
            )
        )
        AND li2.componentCode = 'PENSION_EMP'
    )
)
FROM PayrollRun pr
JOIN pr.employee e
JOIN e.department d
JOIN PayrollPeriod pp
    ON pp.periodStart = pr.periodStart
   AND pp.periodEnd = pr.periodEnd
WHERE d.company.id = :companyId
AND e.id IN :employeeIds
AND pr.periodStart >= :start
AND pr.periodEnd <= :end
AND pr.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
AND pp.status = com.justjava.humanresource.payroll.enums.PayrollPeriodStatus.CLOSED
AND pr.versionNumber = (
    SELECT MAX(pr2.versionNumber)
    FROM PayrollRun pr2
    WHERE pr2.employee.id = pr.employee.id
    AND pr2.periodStart = pr.periodStart
    AND pr2.periodEnd = pr.periodEnd
)
GROUP BY d.id, d.name
""")
    List<PayrollSummaryDTO> getPayrollSummaryForEmployees(
            @Param("companyId") Long companyId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("employeeIds") List<Long> employeeIds
    );

    /* ============================================================
       EARNINGS BREAKDOWN
       ============================================================ */

    @Query("""
SELECT new com.justjava.humanresource.payroll.report.dto.ComponentBreakdownDTO(
    li.componentCode,
    li.description,
    SUM(li.amount)
)
FROM PayrollLineItem li
JOIN li.payrollRun pr
JOIN pr.employee e
JOIN PayrollPeriod pp
    ON pp.periodStart = pr.periodStart
   AND pp.periodEnd = pr.periodEnd
WHERE e.department.company.id = :companyId
AND pr.periodStart >= :start
AND pr.periodEnd <= :end
AND li.componentType = com.justjava.humanresource.payroll.enums.PayComponentType.EARNING
AND pr.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
AND pp.status = com.justjava.humanresource.payroll.enums.PayrollPeriodStatus.CLOSED
AND pr.versionNumber = (
    SELECT MAX(pr2.versionNumber)
    FROM PayrollRun pr2
    WHERE pr2.employee.id = pr.employee.id
    AND pr2.periodStart = pr.periodStart
    AND pr2.periodEnd = pr.periodEnd
)
GROUP BY li.componentCode, li.description
""")
    List<ComponentBreakdownDTO> getEarningsBreakdown(
            Long companyId,
            LocalDate start,
            LocalDate end
    );

    // ── Scoped variant of getEarningsBreakdown ───────────────────────────────
    @Query("""
SELECT new com.justjava.humanresource.payroll.report.dto.ComponentBreakdownDTO(
    li.componentCode,
    li.description,
    SUM(li.amount)
)
FROM PayrollLineItem li
JOIN li.payrollRun pr
JOIN pr.employee e
JOIN PayrollPeriod pp
    ON pp.periodStart = pr.periodStart
   AND pp.periodEnd = pr.periodEnd
WHERE e.department.company.id = :companyId
AND e.id IN :employeeIds
AND pr.periodStart >= :start
AND pr.periodEnd <= :end
AND li.componentType = com.justjava.humanresource.payroll.enums.PayComponentType.EARNING
AND pr.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
AND pp.status = com.justjava.humanresource.payroll.enums.PayrollPeriodStatus.CLOSED
AND pr.versionNumber = (
    SELECT MAX(pr2.versionNumber)
    FROM PayrollRun pr2
    WHERE pr2.employee.id = pr.employee.id
    AND pr2.periodStart = pr.periodStart
    AND pr2.periodEnd = pr.periodEnd
)
GROUP BY li.componentCode, li.description
""")
    List<ComponentBreakdownDTO> getEarningsBreakdownForEmployees(
            @Param("companyId") Long companyId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("employeeIds") List<Long> employeeIds
    );

    /* ============================================================
       COMPONENT TREND
       ============================================================ */

    @Query(value = """
SELECT
    TO_CHAR(pr.payroll_date, 'YYYY-MM') AS period,
    li.component_code,
    SUM(li.amount) AS total_amount
FROM payroll_line_items li
JOIN payroll_runs pr ON pr.id = li.payroll_run_id
JOIN employees e ON e.id = pr.employee_id
JOIN departments d ON d.id = e.department_id
JOIN payroll_periods pp
    ON pp.period_start = pr.period_start
   AND pp.period_end = pr.period_end
WHERE d.company_id = :companyId
AND pr.status = 'POSTED'
AND pp.status = 'CLOSED'
AND pr.version_number = (
    SELECT MAX(pr2.version_number)
    FROM payroll_runs pr2
    WHERE pr2.employee_id = pr.employee_id
    AND pr2.period_start = pr.period_start
    AND pr2.period_end = pr.period_end
)
GROUP BY
    TO_CHAR(pr.payroll_date, 'YYYY-MM'),
    li.component_code
ORDER BY
    period
""", nativeQuery = true)
    List<Object[]> getComponentTrendRaw(Long companyId);

    // ── Scoped variant of getComponentTrendRaw ───────────────────────────────
    @Query(value = """
SELECT
    TO_CHAR(pr.payroll_date, 'YYYY-MM') AS period,
    li.component_code,
    SUM(li.amount) AS total_amount
FROM payroll_line_items li
JOIN payroll_runs pr ON pr.id = li.payroll_run_id
JOIN employees e ON e.id = pr.employee_id
JOIN departments d ON d.id = e.department_id
JOIN payroll_periods pp
    ON pp.period_start = pr.period_start
   AND pp.period_end = pr.period_end
WHERE d.company_id = :companyId
AND e.id IN :employeeIds
AND pr.status = 'POSTED'
AND pp.status = 'CLOSED'
AND pr.version_number = (
    SELECT MAX(pr2.version_number)
    FROM payroll_runs pr2
    WHERE pr2.employee_id = pr.employee_id
    AND pr2.period_start = pr.period_start
    AND pr2.period_end = pr.period_end
)
GROUP BY
    TO_CHAR(pr.payroll_date, 'YYYY-MM'),
    li.component_code
ORDER BY period
""", nativeQuery = true)
    List<Object[]> getComponentTrendRawForEmployees(
            @Param("companyId") Long companyId,
            @Param("employeeIds") List<Long> employeeIds
    );

    /* ============================================================
       PENSION REPORT
       ============================================================ */

    @Query("""
SELECT new com.justjava.humanresource.payroll.report.dto.PensionReportDTO(
    e.id,
    CONCAT(e.firstName,' ',e.lastName),
    SUM(CASE WHEN li.componentCode = 'PENSION_EMP' THEN li.amount END),
    SUM(CASE WHEN li.componentCode = 'PENSION_EMPLOYER' THEN li.amount END),
    COALESCE(MAX(pr.appliedPensionSchemeName), '')
)
    FROM PayrollLineItem li
    JOIN li.payrollRun pr
    JOIN pr.employee e
    JOIN PayrollPeriod pp
        ON pp.periodStart = pr.periodStart
       AND pp.periodEnd = pr.periodEnd
    WHERE e.department.company.id = :companyId
    AND pr.periodStart >= :start
    AND pr.periodEnd <= :end
    AND pr.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
    AND pp.status = com.justjava.humanresource.payroll.enums.PayrollPeriodStatus.CLOSED
    AND pr.versionNumber = (
        SELECT MAX(pr2.versionNumber)
        FROM PayrollRun pr2
        WHERE pr2.employee.id = pr.employee.id
        AND pr2.periodStart = pr.periodStart
        AND pr2.periodEnd = pr.periodEnd
    )
    GROUP BY e.id, e.firstName, e.lastName
    """)
    List<PensionReportDTO> getPensionReport(
            Long companyId,
            LocalDate start,
            LocalDate end
    );

    /* ============================================================
       DEDUCTION BREAKDOWN
       ============================================================ */

    @Query("""
SELECT new com.justjava.humanresource.payroll.report.dto.ComponentBreakdownDTO(
    li.componentCode,
    li.description,
    SUM(li.amount)
)
FROM PayrollLineItem li
JOIN li.payrollRun pr
JOIN pr.employee e
JOIN PayrollPeriod pp
    ON pp.periodStart = pr.periodStart
   AND pp.periodEnd = pr.periodEnd
WHERE e.department.company.id = :companyId
AND pr.periodStart >= :start
AND pr.periodEnd <= :end
AND li.componentType = com.justjava.humanresource.payroll.enums.PayComponentType.DEDUCTION
AND pr.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
AND pp.status = com.justjava.humanresource.payroll.enums.PayrollPeriodStatus.CLOSED
AND pr.versionNumber = (
    SELECT MAX(pr2.versionNumber)
    FROM PayrollRun pr2
    WHERE pr2.employee.id = pr.employee.id
    AND pr2.periodStart = pr.periodStart
    AND pr2.periodEnd = pr.periodEnd
)
GROUP BY li.componentCode, li.description
""")
    List<ComponentBreakdownDTO> getDeductionBreakdown(
            Long companyId,
            LocalDate start,
            LocalDate end
    );

    // ── Scoped variant of getDeductionBreakdown ──────────────────────────────
    @Query("""
SELECT new com.justjava.humanresource.payroll.report.dto.ComponentBreakdownDTO(
    li.componentCode,
    li.description,
    SUM(li.amount)
)
FROM PayrollLineItem li
JOIN li.payrollRun pr
JOIN pr.employee e
JOIN PayrollPeriod pp
    ON pp.periodStart = pr.periodStart
   AND pp.periodEnd = pr.periodEnd
WHERE e.department.company.id = :companyId
AND e.id IN :employeeIds
AND pr.periodStart >= :start
AND pr.periodEnd <= :end
AND li.componentType = com.justjava.humanresource.payroll.enums.PayComponentType.DEDUCTION
AND pr.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
AND pp.status = com.justjava.humanresource.payroll.enums.PayrollPeriodStatus.CLOSED
AND pr.versionNumber = (
    SELECT MAX(pr2.versionNumber)
    FROM PayrollRun pr2
    WHERE pr2.employee.id = pr.employee.id
    AND pr2.periodStart = pr.periodStart
    AND pr2.periodEnd = pr.periodEnd
)
GROUP BY li.componentCode, li.description
""")
    List<ComponentBreakdownDTO> getDeductionBreakdownForEmployees(
            @Param("companyId") Long companyId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("employeeIds") List<Long> employeeIds
    );

    /* ============================================================
       PAYE REPORT
       ============================================================ */

    @Query("""
SELECT new com.justjava.humanresource.payroll.report.dto.PayeReportDTO(
    e.id,
    CONCAT(e.firstName,' ',e.lastName),
    SUM(CASE WHEN li.taxable = true THEN li.amount END),
    SUM(CASE WHEN li.componentCode = 'PAYE' THEN li.amount END),
    MAX(pr.ytdPaye)
)
FROM PayrollLineItem li
JOIN li.payrollRun pr
JOIN pr.employee e
JOIN PayrollPeriod pp
    ON pp.periodStart = pr.periodStart
   AND pp.periodEnd = pr.periodEnd
WHERE e.department.company.id = :companyId
AND pr.periodStart >= :start
AND pr.periodEnd <= :end
AND pr.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
AND pp.status = com.justjava.humanresource.payroll.enums.PayrollPeriodStatus.CLOSED
AND pr.versionNumber = (
    SELECT MAX(pr2.versionNumber)
    FROM PayrollRun pr2
    WHERE pr2.employee.id = pr.employee.id
    AND pr2.periodStart = pr.periodStart
    AND pr2.periodEnd = pr.periodEnd
)
GROUP BY e.id, e.firstName, e.lastName
""")
    List<PayeReportDTO> getPayeReport(
            Long companyId,
            LocalDate start,
            LocalDate end
    );

    /* ============================================================
       VERSION / PERIOD UTILITIES
       ============================================================ */

    Optional<PayrollRun>
    findTopByEmployee_IdAndPeriodStartAndPeriodEndOrderByVersionNumberDesc(
            Long employeeId,
            LocalDate periodStart,
            LocalDate periodEnd
    );

    @Query("""
SELECT COALESCE(MAX(pr.versionNumber), 0)
FROM PayrollRun pr
WHERE pr.employee.id = :employeeId
AND pr.periodStart = :periodStart
AND pr.periodEnd = :periodEnd
""")
    Integer findMaxVersionForEmployeeAndPeriod(
            Long employeeId,
            LocalDate periodStart,
            LocalDate periodEnd
    );

    @Query("""
SELECT pr
FROM PayrollRun pr
JOIN pr.employee e
WHERE e.department.company.id = :companyId
AND pr.periodStart = :periodStart
AND pr.periodEnd = :periodEnd
AND pr.versionNumber = (
    SELECT MAX(pr2.versionNumber)
    FROM PayrollRun pr2
    WHERE pr2.employee.id = pr.employee.id
    AND pr2.periodStart = pr.periodStart
    AND pr2.periodEnd = pr.periodEnd
)
""")
    List<PayrollRun> findLatestRunsPerEmployeeForPeriod(
            @Param("companyId") Long companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd
    );

    /* ============================================================
       VARIANCE REPORT
       ============================================================ */

    /**
     * Returns the highest-version POSTED run for a specific employee in a
     * specific period. Used by the Payroll Variance Report to load the
     * previous-period baseline for each employee.
     */
    @Query("""
    SELECT pr FROM PayrollRun pr
    WHERE pr.employee.id = :employeeId
      AND pr.periodStart = :periodStart
      AND pr.periodEnd   = :periodEnd
      AND pr.status      = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
      AND pr.versionNumber = (
          SELECT MAX(pr2.versionNumber)
          FROM PayrollRun pr2
          WHERE pr2.employee.id  = :employeeId
            AND pr2.periodStart  = :periodStart
            AND pr2.periodEnd    = :periodEnd
            AND pr2.status       = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
      )
    """)
    Optional<PayrollRun> findLatestPostedRunForEmployeeAndPeriod(
            @Param("employeeId")   Long      employeeId,
            @Param("periodStart")  LocalDate periodStart,
            @Param("periodEnd")    LocalDate periodEnd
    );

    @Query("""
    SELECT pr FROM PayrollRun pr
    WHERE pr.employee.id = :employeeId
      AND pr.periodStart = :periodStart
      AND pr.periodEnd = :periodEnd
      AND pr.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
    ORDER BY pr.versionNumber ASC
    """)
    List<PayrollRun> findPostedVersionsForEmployeeAndPeriod(
            @Param("employeeId") Long employeeId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd
    );

    @Query("""
    SELECT pr FROM PayrollRun pr
    WHERE pr.employee.id = :employeeId
      AND pr.periodStart = :periodStart
      AND pr.periodEnd = :periodEnd
      AND pr.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
      AND pr.versionNumber = 1
    """)
    Optional<PayrollRun> findOriginalPostedRunForEmployeeAndPeriod(
            @Param("employeeId") Long employeeId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd
    );

    @Query("""
    SELECT pr
    FROM PayrollRun pr
    JOIN pr.employee e
    WHERE e.department.company.id = :companyId
      AND pr.periodStart = :periodStart
      AND pr.periodEnd = :periodEnd
      AND pr.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED
      AND pr.versionNumber = 1
    """)
    List<PayrollRun> findOriginalPostedRunsForCompanyAndPeriod(
            @Param("companyId") Long companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd
    );

    /* ============================================================
       EMPLOYEE REPORT
       ============================================================ */

    @Query("""
SELECT new com.justjava.humanresource.payroll.dto.EmployeeReportItemDTO(
    e.id,
    e.firstName,
    e.lastName,

    pr.grossPay,
    pr.netPay,
    SUM(CASE WHEN li.componentCode = 'PAYE' THEN li.amount END),
    SUM(CASE WHEN li.componentCode = 'PENSION_EMP' THEN li.amount END),
    CASE
        WHEN :groupBy = 'GRADE' THEN jg.name
        WHEN :groupBy = 'PAYGROUP' THEN pg.name
        WHEN :groupBy = 'COMPANY' THEN c.name
    END,
    MAX(bd.accountNumber),
    MAX(bd.bankName)
)
FROM PayrollRun pr
JOIN pr.employee e
JOIN e.department d
JOIN d.company c
JOIN EmployeePositionHistory eph ON eph.employee.id = e.id
JOIN eph.jobStep js
JOIN js.jobGrade jg
JOIN eph.payGroup pg
LEFT JOIN PayrollLineItem li ON li.payrollRun.id = pr.id
LEFT JOIN EmployeeBankDetail bd ON bd.employee.id = e.id
    AND bd.primaryAccount = true
    AND bd.status = com.justjava.humanresource.core.enums.RecordStatus.ACTIVE

WHERE c.id = :companyId
AND pr.periodStart >= :start
AND pr.periodEnd <= :end
AND pr.status = com.justjava.humanresource.core.enums.PayrollRunStatus.POSTED


AND pr.versionNumber = (
    SELECT MAX(pr2.versionNumber)
    FROM PayrollRun pr2
    WHERE pr2.employee.id = pr.employee.id
    AND pr2.periodStart = pr.periodStart
    AND pr2.periodEnd = pr.periodEnd
)

AND eph.current = true

GROUP BY
    e.id, e.firstName, e.lastName,
    pr.grossPay, pr.netPay,
    jg.name, pg.name, c.name
""")
    List<EmployeeReportItemDTO> getEmployeeReportRaw(
            Long companyId,
            LocalDate start,
            LocalDate end,
            String groupBy
    );


}
