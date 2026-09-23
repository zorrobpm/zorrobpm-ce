package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.exception.ServiceTaskNotFoundException;
import com.zorrodev.bpm.contract.exception.TaskNotActiveException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.dto.RetryOverride;
import com.zorrodev.bpm.exchange.ErrorReport;
import com.zorrodev.bpm.exchange.ServiceTaskBpmnErrorThrown;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import com.zorrodev.bpm.exchange.ServiceTaskFailed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies a result reported by a worker to its service task, whatever transport brought it. Runs in
 * the transaction of the caller and has none of its own, so that a caller can catch its exceptions
 * without the transaction being marked for rollback.
 * <p>
 * Every method throws {@link ServiceTaskNotFoundException} when there is no such service task and
 * {@link TaskNotActiveException} when the service task is already completed, interrupted, waiting for
 * a retry or has an incident: such a result is late and changes nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceTaskResultService {

    private final RuntimeService runtimeService;
    private final ActivityService activityService;
    private final DBService dbService;

    /** Applies a {@link ServiceTaskCompleted}, {@link ServiceTaskFailed} or {@link ServiceTaskBpmnErrorThrown}. */
    public void apply(Object result) {
        if (result instanceof ServiceTaskCompleted completed) {
            complete(completed);
        } else if (result instanceof ServiceTaskFailed failed) {
            fail(failed);
        } else if (result instanceof ServiceTaskBpmnErrorThrown thrown) {
            throwBpmnError(thrown);
        } else {
            throw new IllegalArgumentException("Not a service task result: " + result);
        }
    }

    public void complete(ServiceTaskCompleted event) {
        UUID serviceTaskId = event.getServiceTaskId();
        releaseLock(serviceTaskId);
        runtimeService.completeServiceTask(serviceTaskId, variables(event.getVariables()));
    }

    public void fail(ServiceTaskFailed event) {
        UUID serviceTaskId = event.getServiceTaskId();
        releaseLock(serviceTaskId);
        activityService.failServiceTask(serviceTaskId,
            new ErrorReport(event.getErrorCode(), event.getMessage(), event.getDetails()),
            new RetryOverride(event.getRetries(), parseRetryTimeout(event)));
    }

    public void throwBpmnError(ServiceTaskBpmnErrorThrown event) {
        UUID serviceTaskId = event.getServiceTaskId();
        releaseLock(serviceTaskId);
        activityService.throwBpmnError(serviceTaskId, event.getErrorCode(), event.getMessage(), variables(event.getVariables()));
    }

    /** A result ends the attempt: the job is not held for its worker any more (gRPC transport). */
    private void releaseLock(UUID serviceTaskId) {
        if (!dbService.hasServiceTask(serviceTaskId)) {
            throw new ServiceTaskNotFoundException("Service task " + serviceTaskId + " not found");
        }
        dbService.releaseServiceTaskLock(serviceTaskId);
    }

    private static List<ProcessVariable> variables(List<com.zorrodev.bpm.exchange.ProcessVariable> variables) {
        return Optional.ofNullable(variables).orElse(List.of()).stream()
            .map(v -> {
                ProcessVariable pv = new ProcessVariable();
                pv.setName(v.getName());
                pv.setValue(v.getValue());
                pv.setType(ProcessVariableType.valueOf(v.getType()));
                return pv;
            })
            .toList();
    }

    /** The transports already drop an invalid value; an invalid one here falls back to BPMN too. */
    private static Duration parseRetryTimeout(ServiceTaskFailed event) {
        String value = event.getRetryTimeout();
        if (value == null) {
            return null;
        }
        try {
            Duration timeout = Duration.parse(value);
            return timeout.isNegative() ? null : timeout;
        } catch (DateTimeParseException e) {
            log.warn("Ignoring invalid retryTimeout '{}' of service task {}", value, event.getServiceTaskId());
            return null;
        }
    }
}
