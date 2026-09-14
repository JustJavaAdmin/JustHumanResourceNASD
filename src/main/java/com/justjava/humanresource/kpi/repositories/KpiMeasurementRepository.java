package com.justjava.humanresource.kpi.repositories;

import com.justjava.humanresource.kpi.entity.KpiMeasurement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

@Repository
public interface KpiMeasurementRepository
        extends JpaRepository<KpiMeasurement, Long> {

    /* =========================================================
       CORE LOOKUPS
       ========================================================= */

    List<KpiMeasurement> findByEmployee_IdAndPeriod(
            Long employeeId,
            YearMonth period
    );

    boolean existsByEmployee_IdAndKpi_IdAndPeriod(
            Long employeeId,
            Long kpiId,
            YearMonth period
    );

    List<KpiMeasurement> findByEmployee_IdAndPeriodBetween(
            Long employeeId,
            YearMonth start,
            YearMonth end
    );

    /* =========================================================
       JOBSTEP-SCOPED LOOKUP (FIXED — NO findAll())
       ========================================================= */

    List<KpiMeasurement> findByEmployee_JobStep_IdAndPeriod(
            Long jobStepId,
            YearMonth period
    );
    @Query("""
       SELECT m
       FROM KpiMeasurement m
       JOIN KpiAssignment a
            ON a.kpi.id = m.kpi.id
       WHERE m.period = :period
       AND a.active = true
       AND (a.validFrom IS NULL OR a.validFrom <= :referenceDate)
       AND (a.validTo IS NULL OR a.validTo >= :referenceDate)
       """)
    List<KpiMeasurement> findAllEffectiveMeasurementsForPeriod(
            @Param("period") YearMonth period,
            @Param("referenceDate") LocalDate referenceDate
    );
    @Query("""
       SELECT m
       FROM KpiMeasurement m
       JOIN FETCH m.kpi k
       JOIN FETCH m.employee e
       WHERE e.id = :employeeId
       AND m.period = :period
       """)
    List<KpiMeasurement> findDetailedByEmployeeAndPeriod(
            @Param("employeeId") Long employeeId,
            @Param("period") YearMonth period
    );


    @Query("""
       SELECT m
       FROM KpiMeasurement m
       JOIN FETCH m.kpi k
       JOIN FETCH m.employee e
       WHERE e.id = :employeeId
       AND m.period = :period
       AND k.impactSalary = true
       ORDER BY k.name ASC
       """)
    List<KpiMeasurement> findSalaryImpactingDetailedByEmployeeAndPeriod(
            @Param("employeeId") Long employeeId,
            @Param("period") YearMonth period
    );

    @Query("""
       SELECT m
       FROM KpiMeasurement m
       JOIN FETCH m.employee e
       JOIN FETCH m.kpi k
       WHERE m.period = :period
       ORDER BY m.recordedAt DESC, e.lastName ASC, e.firstName ASC, k.name ASC
       """)
    List<KpiMeasurement> findAllDetailedByPeriod(
            @Param("period") YearMonth period
    );
    @Query("""
       SELECT m.employee.id, m.employee.lastName, AVG(m.score)
       FROM KpiMeasurement m
       WHERE m.period = :period
       GROUP BY m.employee.id, m.employee.lastName
       ORDER BY AVG(m.score) DESC
       """)
    List<Object[]> findTopPerformersByKpi(
            @Param("period") YearMonth period,
            Pageable pageable
    );
    @Query("""
       SELECT m.employee.id, m.employee.lastName, AVG(m.score)
       FROM KpiMeasurement m
       WHERE m.period = :period
       GROUP BY m.employee.id, m.employee.lastName
       ORDER BY AVG(m.score) ASC
       """)
    List<Object[]> findBottomPerformersByKpi(
            @Param("period") YearMonth period,
            Pageable pageable
    );

    @Query(value = """
       SELECT
           COALESCE(SUM(CASE WHEN score >= 85 THEN 1 ELSE 0 END),0) AS excellent,
           COALESCE(SUM(CASE WHEN score >= 70 AND score < 85 THEN 1 ELSE 0 END),0) AS good,
           COALESCE(SUM(CASE WHEN score >= 50 AND score < 70 THEN 1 ELSE 0 END),0) AS average,
           COALESCE(SUM(CASE WHEN score < 50 THEN 1 ELSE 0 END),0) AS poor
       FROM kpi_measurement
       WHERE period = :period
       """, nativeQuery = true)
    Object[] getScoreDistribution(@Param("period") String period);


    Optional<KpiMeasurement> findByEmployee_IdAndKpi_IdAndPeriod(
            Long employeeId,
            Long kpiId,
            YearMonth period
    );
}