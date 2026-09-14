package com.justjava.humanresource.payroll.repositories;

import com.justjava.humanresource.payroll.entity.PayrollRunKpiSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.YearMonth;
import java.util.List;

public interface PayrollRunKpiSnapshotRepository
        extends JpaRepository<PayrollRunKpiSnapshot, Long> {


    List<PayrollRunKpiSnapshot> findByPayrollRunIdOrderByKpiNameAsc(Long payrollRunId);

    void deleteByPayrollRunId(Long payrollRunId);

    boolean existsByPayrollRunId(Long payrollRunId);


    List<PayrollRunKpiSnapshot> findByEmployeeIdAndPeriodOrderByKpiNameAsc(
            Long employeeId,
            YearMonth period
    );
}