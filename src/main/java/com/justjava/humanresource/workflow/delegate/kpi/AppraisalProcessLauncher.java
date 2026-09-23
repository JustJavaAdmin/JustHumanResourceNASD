package com.justjava.humanresource.workflow.delegate.kpi;

import lombok.RequiredArgsConstructor;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AppraisalProcessLauncher {

    private final RuntimeService runtimeService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startAppraisal(String businessKey, Map<String, Object> variables) {
        runtimeService.startProcessInstanceByKey(
                "employeeAppraisalProcess",
                businessKey,
                variables
        );
    }
}