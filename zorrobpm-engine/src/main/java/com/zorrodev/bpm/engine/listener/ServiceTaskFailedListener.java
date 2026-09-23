package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.contract.exception.TaskNotActiveException;
import com.zorrodev.bpm.engine.dto.RetryOverride;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.exchange.ErrorReport;
import com.zorrodev.bpm.exchange.ServiceTaskFailed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceTaskFailedListener {

    private final ActivityService activityService;

    @Transactional
    @EventListener
    public void on(ServiceTaskFailed serviceTaskFailed) {
        UUID serviceTaskId = serviceTaskFailed.getServiceTaskId();
        try {
            activityService.failServiceTask(serviceTaskId,
                new ErrorReport(serviceTaskFailed.getErrorCode(), serviceTaskFailed.getMessage(), serviceTaskFailed.getDetails()),
                new RetryOverride(serviceTaskFailed.getRetries(), parseRetryTimeout(serviceTaskFailed)));
        } catch (TaskNotActiveException e) {
            // A late failure for a task that is already completed or was interrupted by a boundary timer.
            log.info("Ignoring failure of service task {}: {}", serviceTaskId, e.getMessage());
        }
    }

    /** The queue listener already drops an invalid value; an invalid one here falls back to BPMN too. */
    private static Duration parseRetryTimeout(ServiceTaskFailed serviceTaskFailed) {
        String value = serviceTaskFailed.getRetryTimeout();
        if (value == null) {
            return null;
        }
        try {
            Duration timeout = Duration.parse(value);
            return timeout.isNegative() ? null : timeout;
        } catch (DateTimeParseException e) {
            log.warn("Ignoring invalid retryTimeout '{}' of service task {}", value, serviceTaskFailed.getServiceTaskId());
            return null;
        }
    }
}
