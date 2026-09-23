package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.contract.exception.ServiceTaskNotFoundException;
import com.zorrodev.bpm.contract.exception.TaskNotActiveException;
import com.zorrodev.bpm.engine.service.ServiceTaskResultService;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceTaskCompleteListener {

    private final ServiceTaskResultService resultService;

    @Transactional
    @EventListener
    public void on(ServiceTaskCompleted serviceTaskCompleted) {
        UUID serviceTaskId = serviceTaskCompleted.getServiceTaskId();
        try {
            resultService.complete(serviceTaskCompleted);
        } catch (TaskNotActiveException e) {
            // A late reply for a task that is already completed or was interrupted by a boundary timer.
            log.info("Ignoring completion of service task {}: {}", serviceTaskId, e.getMessage());
        } catch (ServiceTaskNotFoundException e) {
            // Nothing to complete: retrying the message would block the queue for every job.
            log.warn("Ignoring completion of unknown service task {}", serviceTaskId);
        }
    }
}
