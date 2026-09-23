package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.contract.exception.ServiceTaskNotFoundException;
import com.zorrodev.bpm.contract.exception.TaskNotActiveException;
import com.zorrodev.bpm.engine.service.ServiceTaskResultService;
import com.zorrodev.bpm.exchange.ServiceTaskFailed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceTaskFailedListener {

    private final ServiceTaskResultService resultService;

    @Transactional
    @EventListener
    public void on(ServiceTaskFailed serviceTaskFailed) {
        UUID serviceTaskId = serviceTaskFailed.getServiceTaskId();
        try {
            resultService.fail(serviceTaskFailed);
        } catch (TaskNotActiveException e) {
            // A late failure for a task that is already completed or was interrupted by a boundary timer.
            log.info("Ignoring failure of service task {}: {}", serviceTaskId, e.getMessage());
        } catch (ServiceTaskNotFoundException e) {
            // Nothing to fail: retrying the message would block the queue for every job.
            log.warn("Ignoring failure of unknown service task {}: {} ({})", serviceTaskId, serviceTaskFailed.getMessage(), serviceTaskFailed.getErrorCode());
        }
    }
}
