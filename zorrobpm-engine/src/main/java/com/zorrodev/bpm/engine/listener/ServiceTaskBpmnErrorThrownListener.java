package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.contract.exception.TaskNotActiveException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.exchange.ServiceTaskBpmnErrorThrown;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceTaskBpmnErrorThrownListener {

    private final ActivityService activityService;

    @Transactional
    @EventListener
    public void on(ServiceTaskBpmnErrorThrown event) {
        UUID serviceTaskId = event.getServiceTaskId();
        List<ProcessVariable> variables = Optional.ofNullable(event.getVariables()).orElse(List.of()).stream()
            .map(v -> {
                ProcessVariable pv = new ProcessVariable();
                pv.setName(v.getName());
                pv.setValue(v.getValue());
                pv.setType(ProcessVariableType.valueOf(v.getType()));
                return pv;
            })
            .toList();
        try {
            activityService.throwBpmnError(serviceTaskId, event.getErrorCode(), event.getMessage(), variables);
        } catch (TaskNotActiveException e) {
            // A late error for a task that is already completed, interrupted or waiting for a retry.
            log.info("Ignoring BPMN error {} of service task {}: {}", event.getErrorCode(), serviceTaskId, e.getMessage());
        }
    }
}
