package com.justjava.humanresource.workflow.delegate.kpi;

import com.justjava.humanresource.hr.entity.Employee;
import com.justjava.humanresource.hr.repository.EmployeeRepository;
import com.justjava.humanresource.kpi.entity.AppraisalCycle;
import com.justjava.humanresource.kpi.repositories.AppraisalCycleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component("batchStartAppraisalDelegate")
@RequiredArgsConstructor
@Transactional
public class BatchStartAppraisalDelegate implements JavaDelegate {

    private static final int BATCH_SIZE = 200;

    private final EmployeeRepository employeeRepository;
    private final RuntimeService runtimeService;
    private final AppraisalCycleRepository cycleRepository;
    private final AppraisalProcessLauncher appraisalProcessLauncher;

    @Override
    public void execute(DelegateExecution execution) {

        Long cycleId = Long.valueOf(
                execution.getVariable("cycleId").toString()
        );
        AppraisalCycle cycle =
                cycleRepository.findById(cycleId)
                        .orElseThrow();

        log.info("Starting appraisal batch for cycle id={} ({}, {} - {})",
                cycleId, cycle.getName(), cycle.getStartPeriod(), cycle.getEndPeriod());

        int page = 0;
        int startedCount = 0;
        int skippedNotEnabled = 0;
        int skippedAlreadyActive = 0;
        int failedCount = 0;
        Page<Employee> result;

        do {

            result = employeeRepository.findEmployeesEligibleForAppraisal(
                    cycle.getStartPeriod(),
                    cycle.getEndPeriod(),
                    cycleId,
                    PageRequest.of(page, BATCH_SIZE)
            );

            for (Employee employee : result.getContent()) {

                if (!employee.isKpiEnabled()) {
                    skippedNotEnabled++;
                    log.debug("Skipping employee {} - kpiEnabled is false", employee.getId());
                    continue;
                }

                String businessKey =
                        "APPRAISAL_" + employee.getId()
                                + "_Y" + cycle.getYear()
                                + "Q" + cycle.getQuarter();

                boolean exists =
                        runtimeService.createProcessInstanceQuery()
                                .processDefinitionKey("employeeAppraisalProcess")
                                .processInstanceBusinessKey(businessKey)
                                .active()
                                .count() > 0;

                if (exists) {
                    skippedAlreadyActive++;
                    log.debug("Active appraisal process already exists for employee {} (businessKey={})",
                            employee.getId(), businessKey);
                    continue;
                }

                try {
                    appraisalProcessLauncher.startAppraisal(
                            businessKey,
                            Map.of(
                                    "employeeId", employee.getId(),
                                    "cycleId", cycleId,
                                    "managerComplete", false,
                                    "selfComplete", false
                            )
                    );

                    cycle.setProcessedEmployees(cycle.getProcessedEmployees() + 1);
                    startedCount++;

                } catch (Exception ex) {
                    failedCount++;
                    log.error("Failed to start appraisal for employee {} in cycle {}: {}",
                            employee.getId(), cycleId, ex.getMessage(), ex);
                }
            }

            page++;

        } while (result.hasNext());

        cycle.setCompleted(true);
        cycle.setCompletedAt(LocalDateTime.now());
        cycleRepository.save(cycle);

        log.info("Appraisal batch finished for cycle id={}. started={}, skippedNotEnabled={}, " +
                        "skippedAlreadyActive={}, failed={}, processedEmployees={}",
                cycleId, startedCount, skippedNotEnabled, skippedAlreadyActive, failedCount,
                cycle.getProcessedEmployees());
    }
}