package com.justjava.humanresource.payroll.entity;

import com.justjava.humanresource.core.entity.BaseEntity;
import com.justjava.humanresource.hr.entity.Employee;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.YearMonth;


@Getter
@Setter
@Entity
@Table(
        name = "payroll_run_kpi_snapshots",
        indexes = {
                @Index(name = "idx_kpi_snapshot_payroll_run", columnList = "payroll_run_id"),
                @Index(name = "idx_kpi_snapshot_employee_period", columnList = "employee_id, period")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payroll_run_kpi_snapshot",
                        columnNames = {"payroll_run_id", "kpi_id_snapshot"}
                )
        }
)
public class PayrollRunKpiSnapshot extends BaseEntity {



    @ManyToOne(optional = false)
    @JoinColumn(name = "payroll_run_id", nullable = false)
    private PayrollRun payrollRun;

    @ManyToOne(optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "period", nullable = false)
    private YearMonth period;


    @Column(name = "kpi_id_snapshot")
    private Long kpiIdSnapshot;

    @Column(name = "kpi_code")
    private String kpiCode;

    @Column(name = "kpi_name", nullable = false)
    private String kpiName;

    @Column(name = "kpi_unit")
    private String kpiUnit;

    @Column(name = "target_value", precision = 19, scale = 4)
    private BigDecimal targetValue;

    @Column(name = "actual_value", precision = 19, scale = 4)
    private BigDecimal actualValue;


    @Column(name = "score", precision = 7, scale = 2, nullable = false)
    private BigDecimal score;


    @Column(name = "impact_salary", nullable = false)
    private boolean impactSalary = true;


    @Column(name = "snapshot_source", nullable = false)
    private String snapshotSource = "CALCULATION";

}