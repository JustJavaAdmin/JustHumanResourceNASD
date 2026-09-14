package com.justjava.humanresource.kpi.service;

import com.justjava.humanresource.dispatcher.PayrollMessageDispatcher;
import com.justjava.humanresource.hr.entity.Employee;
import com.justjava.humanresource.hr.entity.JobStep;
import com.justjava.humanresource.hr.repository.EmployeeRepository;
import com.justjava.humanresource.hr.repository.JobStepRepository;
import com.justjava.humanresource.hr.service.JobHrEmployeeAccessService;
import com.justjava.humanresource.kpi.entity.*;
import com.justjava.humanresource.kpi.repositories.KpiAssignmentRepository;
import com.justjava.humanresource.kpi.repositories.KpiDefinitionRepository;
import com.justjava.humanresource.kpi.repositories.KpiMeasurementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.justjava.humanresource.core.config.AuthenticationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Transactional
public class KpiMeasurementService {

    private final KpiMeasurementRepository measurementRepository;
    private final KpiAssignmentRepository assignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final KpiDefinitionRepository kpiRepository;

    private final JobStepRepository jobStepRepository;
    private final PayrollMessageDispatcher payrollMessageDispatcher;
    private final AuthenticationManager authenticationManager;
    private final JobHrEmployeeAccessService jobHrEmployeeAccessService;

    /* =====================================================
       BULK MEASUREMENT ENTRY
       ===================================================== */

    public List<KpiMeasurementResponseDTO> recordBulkMeasurements(
            KpiBulkMeasurementRequestDTO request
    ) {

        if (request.getEmployeeId() == null)
            throw new IllegalArgumentException("EmployeeId is required");

        if (request.getPeriod() == null)
            throw new IllegalArgumentException("Measurement period is required");

        if (request.getMeasurements() == null || request.getMeasurements().isEmpty())
            throw new IllegalArgumentException("At least one KPI measurement is required");

        Employee employee = employeeRepository.findById(request.getEmployeeId())
                .orElseThrow();

        YearMonth period = request.getPeriod();

        // 🔥 Bulk fetch KPI definitions
        List<Long> kpiIds = request.getMeasurements().stream()
                .map(KpiMeasurementItemRequestDTO::getKpiId)
                .toList();

        List<KpiDefinition> definitions =
                kpiRepository.findAllById(kpiIds);

        var kpiMap = definitions.stream()
                .collect(Collectors.toMap(KpiDefinition::getId, k -> k));

        List<KpiMeasurementResponseDTO> responseList = new ArrayList<>();

        for (KpiMeasurementItemRequestDTO item : request.getMeasurements()) {

            validateActualValue(item.getActualValue());

            KpiDefinition kpi = kpiMap.get(item.getKpiId());

            if (kpi == null)
                throw new IllegalStateException("Invalid KPI id: " + item.getKpiId());

            validateAssignmentExists(employee, kpi);

            boolean exists =
                    measurementRepository.existsByEmployee_IdAndKpi_IdAndPeriod(
                            employee.getId(),
                            kpi.getId(),
                            period
                    );

            if (exists)
                continue;

            BigDecimal score = calculateScore(
                    item.getActualValue(),
                    kpi.getTargetValue()
            );

            KpiMeasurement saved =
                    measurementRepository.save(
                            KpiMeasurement.builder()
                                    .employee(employee)
                                    .kpi(kpi)
                                    .actualValue(item.getActualValue())
                                    .score(score)
                                    .period(period)
                                    .recordedAt(LocalDateTime.now())
                                    .build()
                    );

            responseList.add(
                    KpiMeasurementResponseDTO.builder()
                            .measurementId(saved.getId())
                            .kpiId(kpi.getId())
                            .kpiCode(kpi.getCode())
                            .actualValue(saved.getActualValue())
                            .score(saved.getScore())
                            .period(saved.getPeriod())
                            .build()
            );
        }
        payrollMessageDispatcher.requestPayroll(employee.getId(), LocalDate.now());
        return responseList;
    }

    /* =====================================================
       EDIT A SINGLE MEASUREMENT (triggers payroll recalc)
       ===================================================== */

    public KpiMeasurementResponseDTO updateMeasurement(Long measurementId, BigDecimal newActualValue) {

        validateActualValue(newActualValue);

        KpiMeasurement measurement = measurementRepository.findById(measurementId)
                .orElseThrow(() -> new IllegalArgumentException("Measurement not found: " + measurementId));

        BigDecimal newScore = calculateScore(newActualValue, measurement.getKpi().getTargetValue());

        measurement.setActualValue(newActualValue);
        measurement.setScore(newScore);
        measurement.setRecordedAt(LocalDateTime.now());

        KpiMeasurement saved = measurementRepository.save(measurement);

        // Re-trigger payroll recalculation for the employee
        payrollMessageDispatcher.requestPayroll(
                saved.getEmployee().getId(),
                LocalDate.now()
        );

        return KpiMeasurementResponseDTO.builder()
                .measurementId(saved.getId())
                .kpiId(saved.getKpi().getId())
                .kpiCode(saved.getKpi().getCode())
                .kpiName(saved.getKpi().getName())
                .actualValue(saved.getActualValue())
                .score(saved.getScore())
                .period(saved.getPeriod())
                .build();
    }

    /* =========================================================
       FETCH EFFECTIVE MEASUREMENTS FOR EMPLOYEE
       ========================================================= */

    @Transactional(readOnly = true)
    public List<KpiMeasurement> getEffectiveMeasurementsForEmployee(
            Long employeeId,
            YearMonth period
    ) {

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow();

        LocalDate referenceDate = period.atEndOfMonth();

        List<KpiAssignment> assignments =
                assignmentRepository.findEffectiveAssignmentsForEmployee(
                        employeeId,
                        employee.getJobStep().getId(),
                        employee.getDepartment().getId(),
                        referenceDate
                );

        if (assignments.isEmpty()) {
            return Collections.emptyList();
        }

        List<KpiMeasurement> measurements =
                measurementRepository.findByEmployee_IdAndPeriod(employeeId, period);

        Set<Long> assignedKpiIds = assignments.stream()
                .map(a -> a.getKpi().getId())
                .collect(Collectors.toSet());

        return measurements.stream()
                .filter(m -> assignedKpiIds.contains(m.getKpi().getId()))
                .collect(Collectors.toList());
    }

    /* =========================================================
       FETCH EFFECTIVE MEASUREMENTS FOR JOBSTEP
       ========================================================= */

    @Transactional(readOnly = true)
    public List<KpiMeasurement> getEffectiveMeasurementsForJobStep(
            Long jobStepId,
            YearMonth period
    ) {

        return measurementRepository
                .findByEmployee_JobStep_IdAndPeriod(jobStepId, period);
    }

    @Transactional(readOnly = true)
    public List<KpiMeasurementResponseDTO> getAllEffectiveMeasurements(
            YearMonth period
    ) {

        if (period == null)
            throw new IllegalArgumentException("Period is required");

        System.out.println("Fetching measurements for period: " + period);
        List<KpiMeasurement> measurements =
                measurementRepository.findAllDetailedByPeriod(period);

        if (measurements.isEmpty()) {
            return Collections.emptyList();
        }

        return measurements.stream()
                .filter(m -> {
                    Employee emp = m.getEmployee();
                    if (authenticationManager.isRestrictedHr() && emp.isRestrictedVisibility()) return false;
                    if (jobHrEmployeeAccessService.isJobHrScopedUser()) {
                        Long actorGradeId = jobHrEmployeeAccessService.getLoggedInJobGradeId();
                        Long empGradeId = emp.getJobStep() != null && emp.getJobStep().getJobGrade() != null
                                ? emp.getJobStep().getJobGrade().getId() : null;
                        return java.util.Objects.equals(actorGradeId, empGradeId);
                    }
                    return true;
                })
                .map(m -> KpiMeasurementResponseDTO.builder()
                        .employee(m.getEmployee())
                        .measurementId(m.getId())
                        .kpiId(m.getKpi().getId())
                        .kpiName(m.getKpi().getName())
                        .kpiCode(m.getKpi().getCode())
                        .actualValue(m.getActualValue())
                        .score(m.getScore())
                        .period(m.getPeriod())
                        .build()
                )
                .toList();
    }


    @Transactional(readOnly = true)
    public List<KpiMeasurement> getSalaryImpactingMeasurementsForEmployee(
            Long employeeId,
            YearMonth period
    ) {

        if (employeeId == null)
            throw new IllegalArgumentException("EmployeeId is required");

        if (period == null)
            throw new IllegalArgumentException("Period is required");

        List<KpiMeasurement> measurements =
                measurementRepository.findSalaryImpactingDetailedByEmployeeAndPeriod(employeeId, period);

        if (measurements.isEmpty()) {
            return Collections.emptyList();
        }

        return measurements;
    }


    public BigDecimal calculateSalaryImpactScore(List<KpiMeasurement> salaryImpactingMeasurements) {

        if (salaryImpactingMeasurements == null || salaryImpactingMeasurements.isEmpty()) {
            return BigDecimal.valueOf(100); // neutral fallback – no penalty
        }

        BigDecimal total = BigDecimal.ZERO;

        for (KpiMeasurement m : salaryImpactingMeasurements) {
            total = total.add(m.getScore());
        }

        return total.divide(
                BigDecimal.valueOf(salaryImpactingMeasurements.size()),
                2,
                RoundingMode.HALF_UP
        );
    }


    @Transactional(readOnly = true)
    public BigDecimal getEmployeeKpiScore(
            Long employeeId,
            YearMonth period
    ) {

        List<KpiMeasurement> salaryImpacting =
                getSalaryImpactingMeasurementsForEmployee(employeeId, period);

        return calculateSalaryImpactScore(salaryImpacting);
    }

    /* =====================================================
       INTERNAL VALIDATION
       ===================================================== */

    private void validateAssignmentExists(Employee employee, KpiDefinition kpi) {

        boolean assigned = assignmentRepository.existsActiveAssignment(
                employee.getId(),
                employee.getJobStep().getId(),
                employee.getDepartment().getId(),
                kpi.getId()
        );

        if (!assigned) {
            throw new IllegalStateException(
                    "KPI not assigned to employee or job step."
            );
        }
    }

    private void validateActualValue(BigDecimal actualValue) {

        if (actualValue == null || actualValue.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("Actual value must be zero or positive.");
    }

    private BigDecimal calculateScore(BigDecimal actual, BigDecimal target) {

        if (target == null || target.compareTo(BigDecimal.ZERO) <= 0)
            return BigDecimal.ZERO;

        return actual
                .divide(target, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .min(BigDecimal.valueOf(100));
    }
}



/*
{
        "employeeId": 12,
        "period": "2026-01",
        "measurements": [
            {
            "kpiId": 3,
            "actualValue": 85
            },
            {
            "kpiId": 5,
            "actualValue": 120
            },
            {
            "kpiId": 7,
            "actualValue": 60
            }
        ]
 }
*/