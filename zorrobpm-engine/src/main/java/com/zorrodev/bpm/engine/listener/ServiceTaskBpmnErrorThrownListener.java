package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.contract.exception.ServiceTaskNotFoundException;
import com.zorrodev.bpm.contract.exception.TaskNotActiveException;
import com.zorrodev.bpm.engine.service.ServiceTaskResultService;
import com.zorrodev.bpm.exchange.ServiceTaskBpmnErrorThrown;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceTaskBpmnErrorThrownListener {

    private final ServiceTaskResultService resultService;

    @Transactional
    @EventListener
    public void on(ServiceTaskBpmnErrorThrown event) {
        UUID serviceTaskId = event.getServiceTaskId();
        try {
            resultService.throwBpmnError(event);
        } catch (TaskNotActiveException e) {
            // A late error for a task that is already completed, interrupted or waiting for a retry.
            log.info("Ignoring BPMN error {} of service task {}: {}", event.getErrorCode(), serviceTaskId, e.getMessage());
        } catch (ServiceTaskNotFoundException e) {
            // Nothing to throw on: retrying the message would block the queue for every job.
            log.warn("Ignoring BPMN error {} of unknown service task {}", event.getErrorCode(), serviceTaskId);
        }
    }
}
