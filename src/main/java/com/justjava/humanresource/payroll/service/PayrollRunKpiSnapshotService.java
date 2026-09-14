package com.justjava.humanresource.payroll.service;

import com.justjava.humanresource.kpi.entity.KpiDefinition;
import com.justjava.humanresource.kpi.entity.KpiMeasurement;
import com.justjava.humanresource.payroll.dto.SalaryImpactKpiSnapshotDTO;
import com.justjava.humanresource.payroll.entity.PayrollRun;
import com.justjava.humanresource.payroll.entity.PayrollRunKpiSnapshot;
import com.justjava.humanresource.payroll.repositories.PayrollRunKpiSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class PayrollRunKpiSnapshotService {

    private final PayrollRunKpiSnapshotRepository payrollRunKpiSnapshotRepository;


    @Transactional
    public void replaceSnapshots(PayrollRun run, List<KpiMeasurement> measurements, BigDecimal appliedSalaryKpiScore) {
        payrollRunKpiSnapshotRepository.deleteByPayrollRunId(run.getId());

        if (measurements == null || measurements.isEmpty()) {
            run.setSalaryKpiScore(null);
            return;
        }

        List<PayrollRunKpiSnapshot> snapshots = measurements.stream()
                .map(measurement -> toSnapshot(run, measurement))
                .collect(Collectors.toList());

        payrollRunKpiSnapshotRepository.saveAll(snapshots);

        run.setSalaryKpiScore(appliedSalaryKpiScore);
    }


    @Transactional(readOnly = true)
    public List<SalaryImpactKpiSnapshotDTO> getSnapshotsForPayrollRun(Long payrollRunId) {
        if (payrollRunId == null) {
            return Collections.emptyList();
        }
        return payrollRunKpiSnapshotRepository.findByPayrollRunIdOrderByKpiNameAsc(payrollRunId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private PayrollRunKpiSnapshot toSnapshot(PayrollRun run, KpiMeasurement measurement) {
        KpiDefinition kpi = measurement.getKpi();

        PayrollRunKpiSnapshot snapshot = new PayrollRunKpiSnapshot();
        snapshot.setPayrollRun(run);
        snapshot.setEmployee(measurement.getEmployee());
        snapshot.setPeriod(measurement.getPeriod());
        snapshot.setKpiIdSnapshot(kpi.getId());
        snapshot.setKpiCode(kpi.getCode());
        snapshot.setKpiName(kpi.getName());
        snapshot.setKpiUnit(kpi.getUnit() != null ? kpi.getUnit().name() : null);
        snapshot.setTargetValue(kpi.getTargetValue());
        snapshot.setActualValue(measurement.getActualValue());
        snapshot.setScore(measurement.getScore());
        snapshot.setImpactSalary(true);
        snapshot.setSnapshotSource("CALCULATION");
        return snapshot;
    }

    private SalaryImpactKpiSnapshotDTO toDto(PayrollRunKpiSnapshot snapshot) {
        return SalaryImpactKpiSnapshotDTO.builder()
                .kpiId(snapshot.getKpiIdSnapshot())
                .kpiCode(snapshot.getKpiCode())
                .kpiName(snapshot.getKpiName())
                .kpiUnit(snapshot.getKpiUnit())
                .targetValue(snapshot.getTargetValue())
                .actualValue(snapshot.getActualValue())
                .score(snapshot.getScore())
                .snapshotSource(snapshot.getSnapshotSource())
                .build();
    }
}