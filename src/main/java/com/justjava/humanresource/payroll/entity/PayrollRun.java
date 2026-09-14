package com.justjava.humanresource.payroll.entity;

import com.justjava.humanresource.core.entity.BaseEntity;
import com.justjava.humanresource.core.enums.PayrollRunStatus;
import com.justjava.humanresource.hr.entity.Employee;
import com.justjava.humanresource.payroll.enums.PayrollRunType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(
        name = "payroll_runs",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_employee_payroll_version",
                        columnNames = {"employee_id", "payroll_date", "version_number"}
                )
        }
)
public class PayrollRun extends BaseEntity {

    /* =========================
     * CORE RELATIONSHIP
     * ========================= */

    @ManyToOne(optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /* =========================
     * PAYROLL PERIOD
     * ========================= */

    @Column(name = "payroll_date", nullable = false)
    private LocalDate payrollDate;

    /*
     * Optional: supports period-based payroll later
     */
    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    /* =========================
     * STATUS MANAGEMENT
     * ========================= */

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayrollRunStatus status;

    /* =========================
     * FLOWABLE TRACEABILITY
     * ========================= */

    @Column(name = "flowable_process_instance_id", nullable = false)
    private String flowableProcessInstanceId;

    /*
     * Business key used for per-employee supervisor process
     */
    @Column(name = "flowable_business_key")
    private String flowableBusinessKey;

    /* =========================
     * SUMMARY SNAPSHOT (IMMUTABLE AFTER POSTED)
     * ========================= */

    @Column(precision = 19, scale = 2)
    private BigDecimal grossPay = BigDecimal.ZERO;

    @Column(precision = 19, scale = 2)
    private BigDecimal totalDeductions = BigDecimal.ZERO;

    @Column(precision = 19, scale = 2)
    private BigDecimal netPay = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    private PayrollRunType runType = PayrollRunType.ORIGINAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_run_id")
    private PayrollRun parentRun;

    @Column(nullable = false)
    private Integer versionNumber = 1;
    private String appliedTaxBandSummary;
    private String appliedPensionSchemeName;

    @Column(precision = 19, scale = 2)
    private BigDecimal ytdGross = BigDecimal.ZERO;

    @Column(precision = 19, scale = 2)
    private BigDecimal ytdTaxable = BigDecimal.ZERO;

    @Column(precision = 19, scale = 2)
    private BigDecimal ytdDeductions = BigDecimal.ZERO;

    @Column(precision = 19, scale = 2)
    private BigDecimal ytdNet = BigDecimal.ZERO;

    @Column(precision = 19, scale = 2)
    private BigDecimal ytdPaye = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer payrollYear;

    @Column(nullable = false)
    private BigDecimal nonGrossEarnings;

    @Column(precision = 19, scale = 2)
    private BigDecimal grossDifference;


    @Column(name = "salary_kpi_score", precision = 7, scale = 2)
    private BigDecimal salaryKpiScore;

    /**
     * Free-text reason recorded when this run is an AMENDMENT.
     * Null for ORIGINAL runs. Populated by the initiating action (e.g.
     * salary change, allowance update, manual correction).
     */
    @Column(length = 1000)
    private String amendmentReason;

    /**
     * Set when this run includes retro-period catch-up adjustments.
     * Records the salary/allowance change effective date that triggered
     * the retro calculation. Null for non-retro runs.
     *
     * <p>Example: salary effective 2026-01-01 entered in May 2026 →
     * {@code retroEffectiveDate = 2026-01-01}, adjustments for Jan–Apr
     * are added as RETRO_ADJ_YYYY_MM EARNING line items in this run.</p>
     */
    @Column(name = "retro_effective_date")
    private LocalDate retroEffectiveDate;

}