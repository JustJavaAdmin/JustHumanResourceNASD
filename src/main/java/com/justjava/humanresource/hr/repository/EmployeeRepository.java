package com.justjava.humanresource.hr.repository;

import com.justjava.humanresource.hr.entity.Employee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee,Long> {

    Optional<Employee> findByEmployeeNumber(String employeeNumber);

    @Query("""
   SELECT COUNT(e)
   FROM Employee e
   WHERE e.employmentStatus = 'ACTIVE'
""")
    long countByEmploymentStatusActive();

    List<Employee> findByPayGroup_Id(Long payGroupId);
    List<Employee> findByJobStep_Id(Long jobStepId);

    @Query("""
       SELECT COUNT(e)
       FROM Employee e
       WHERE e.department.id = :departmentId
       """)
    Long countEmployeesByDepartment(@Param("departmentId") Long departmentId);

    @Query("""
       SELECT DISTINCT e
       FROM Employee e
       WHERE EXISTS (
            SELECT 1
            FROM KpiMeasurement m
            WHERE m.employee.id = e.id
       )
       """)
    Page<Employee> findEmployeesWithAnyKpiMeasurement(Pageable pageable);


    @Query("""
       SELECT DISTINCT e
       FROM Employee e
       WHERE EXISTS (
            SELECT 1
            FROM KpiMeasurement m
            WHERE m.employee.id = e.id
            AND m.period BETWEEN :start AND :end
       )
       AND NOT EXISTS (
            SELECT 1
            FROM EmployeeAppraisal a
            WHERE a.employee.id = e.id
            AND a.cycle.id = :cycleId
       )
       """)
    Page<Employee> findEmployeesEligibleForAppraisal(
            @Param("start") java.time.YearMonth start,
            @Param("end") java.time.YearMonth end,
            @Param("cycleId") Long cycleId,
            Pageable pageable
    );

    @Query("""
       SELECT DISTINCT e
       FROM Employee e
       WHERE EXISTS (
            SELECT 1
            FROM KpiMeasurement m
            WHERE m.employee.id = e.id
            AND m.period = :period
       )
       """)
    Page<Employee> findEmployeesWithKpiMeasurementForPeriod(
            @Param("period") java.time.YearMonth period, Pageable pageable
    );

    Optional<Employee> findByEmail(String email);

    @Query("SELECT e FROM Employee e " +
            "LEFT JOIN FETCH e.bankDetails " +
            "WHERE e.id = :id")
    Optional<Employee> findByIdWithBankDetails(@Param("id") Long id);

    @Query("""
       SELECT e.department.id, COUNT(e)
       FROM Employee e
       GROUP BY e.department.id
       """)
    List<Object[]> countEmployeesByDepartmentGroup();

    @Query("""
    SELECT e FROM Employee e
    LEFT JOIN e.bankDetails bd
    WHERE e.department.company.id = :companyId
      AND e.status IN (com.justjava.humanresource.core.enums.RecordStatus.ACTIVE, com.justjava.humanresource.core.enums.RecordStatus.INACTIVE)
      AND (
          e.suspensionFrom IS NULL
          OR e.suspensionFrom > :periodEnd
          OR (e.suspensionTo IS NOT NULL AND e.suspensionTo < :periodStart)
      )
      AND (bd IS NULL OR NOT EXISTS (
          SELECT b FROM EmployeeBankDetail b
          WHERE b.employee = e
          AND b.status = com.justjava.humanresource.core.enums.RecordStatus.ACTIVE
          AND b.accountNumber IS NOT NULL
          AND b.accountNumber != ''
      ))
""")
    List<Employee> findEmployeesMissingBankDetails(@Param("companyId") Long companyId,
                                                   @Param("periodStart") java.time.LocalDate periodStart,
                                                   @Param("periodEnd") java.time.LocalDate periodEnd);

    @Query("""
    SELECT e FROM Employee e
    WHERE e.employmentStatus = com.justjava.humanresource.core.enums.EmploymentStatus.ACTIVE
      AND (e.suspensionFrom IS NULL OR e.suspensionFrom > :payrollDate
           OR (e.suspensionTo IS NOT NULL AND e.suspensionTo < :payrollDate))
""")
    List<Employee> findPayrollEligibleEmployees(@Param("payrollDate") java.time.LocalDate payrollDate);

    // Filtered variant — excludes employees marked as restricted
    @Query("SELECT e FROM Employee e WHERE e.restrictedVisibility = false")
    List<Employee> findAllVisible();


    @Query("""
    SELECT DISTINCT e FROM Employee e
    LEFT JOIN FETCH e.jobStep js
    LEFT JOIN FETCH js.jobGrade
    LEFT JOIN FETCH e.payGroup
    WHERE e.department.id = :departmentId
      AND e.restrictedVisibility = false
    ORDER BY e.lastName, e.firstName
""")
    List<Employee> findByDepartmentIdForStructure(@Param("departmentId") Long departmentId);

    // Employees eligible to be assigned as a Department Head — i.e. those
    // carrying the "departmentHead" group in Keycloak (mirrored locally on
    // Employee.groups by KeycloakUserCreationDelegate). Restricted to
    // ACTIVE, non-restricted-visibility employees only.
    @Query("""
       SELECT e FROM Employee e
       WHERE :groupName MEMBER OF e.groups
         AND e.status = com.justjava.humanresource.core.enums.RecordStatus.ACTIVE
         AND e.restrictedVisibility = false
       ORDER BY e.lastName, e.firstName
       """)
    List<Employee> findActiveEmployeesInGroup(@Param("groupName") String groupName);
}