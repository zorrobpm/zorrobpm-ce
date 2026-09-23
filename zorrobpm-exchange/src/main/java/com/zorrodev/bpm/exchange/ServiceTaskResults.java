package com.zorrodev.bpm.exchange;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Turns a result reported by a worker into the event the engine processes, with the same rules on
 * every transport: a negative retry count means no retries, an invalid retry delay falls back to the
 * interval from BPMN, a BPMN error without a code and a status the engine does not know stop the
 * process on the task with an incident instead of being lost or taken for a success.
 */
@Slf4j
public final class ServiceTaskResults {

    public static final String UNSUPPORTED_RESULT_STATUS = "UNSUPPORTED_RESULT_STATUS";
    public static final String INVALID_BPMN_ERROR = "INVALID_BPMN_ERROR";

    private ServiceTaskResults() {
    }

    /**
     * @return a {@link ServiceTaskCompleted}, {@link ServiceTaskFailed} or {@link ServiceTaskBpmnErrorThrown}
     */
    public static Object toEvent(ServiceTaskCompleteData data) {
        if (data.getStatus() == ServiceTaskResultStatus.FAILURE) {
            log.info("Service task failure received - {}: {} ({})", data.getServiceTaskId(), data.getMessage(), data.getErrorCode());
            ServiceTaskFailed failed = failed(data.getServiceTaskId(), data.getErrorCode(), data.getMessage(), data.getDetails());
            failed.setRetries(retries(data));
            failed.setRetryTimeout(retryTimeout(data));
            return failed;
        }
        if (data.getStatus() == ServiceTaskResultStatus.BPMN_ERROR) {
            if (data.getErrorCode() == null || data.getErrorCode().isBlank()) {
                // Nothing to match a boundary event against; stop the process on the task instead of losing the result.
                log.warn("Service task BPMN error without a code received - {}", data.getServiceTaskId());
                ServiceTaskFailed failed = failed(data.getServiceTaskId(), INVALID_BPMN_ERROR,
                    "BPMN error without an error code", data.getMessage());
                failed.setRetries(0);
                return failed;
            }
            log.info("Service task BPMN error received - {}: {} ({})", data.getServiceTaskId(), data.getErrorCode(), data.getMessage());
            ServiceTaskBpmnErrorThrown thrown = new ServiceTaskBpmnErrorThrown();
            thrown.setServiceTaskId(data.getServiceTaskId());
            thrown.setErrorCode(data.getErrorCode());
            thrown.setMessage(data.getMessage());
            thrown.setVariables(data.getVariables());
            return thrown;
        }
        if (data.getStatus() == ServiceTaskResultStatus.UNSUPPORTED) {
            // A status this engine does not know must not pass for a success: stop the process on the task.
            log.warn("Service task result with an unsupported status received - {}", data.getServiceTaskId());
            // A protocol error, not a handler failure: no retries.
            ServiceTaskFailed failed = failed(data.getServiceTaskId(), UNSUPPORTED_RESULT_STATUS,
                "Unsupported service task result status", null);
            failed.setRetries(0);
            return failed;
        }
        log.info("Service task completion received - {}", data.getServiceTaskId());
        ServiceTaskCompleted completed = new ServiceTaskCompleted();
        completed.setServiceTaskId(data.getServiceTaskId());
        completed.setVariables(data.getVariables());
        return completed;
    }

    /** A negative count from a worker means no retries rather than a lost result. */
    private static Integer retries(ServiceTaskCompleteData data) {
        Integer retries = data.getRetries();
        if (retries != null && retries < 0) {
            log.warn("Service task {}: negative retries {} treated as 0", data.getServiceTaskId(), retries);
            return 0;
        }
        return retries;
    }

    /** An invalid delay from a worker is dropped: the interval from BPMN applies. */
    private static String retryTimeout(ServiceTaskCompleteData data) {
        String value = data.getRetryTimeout();
        if (value == null) {
            return null;
        }
        try {
            if (!Duration.parse(value).isNegative()) {
                return value;
            }
        } catch (DateTimeParseException e) {
            // falls through to the warning
        }
        log.warn("Service task {}: invalid retryTimeout '{}' ignored, the interval from BPMN applies", data.getServiceTaskId(), value);
        return null;
    }

    private static ServiceTaskFailed failed(UUID serviceTaskId, String errorCode, String message, String details) {
        ServiceTaskFailed serviceTaskFailed = new ServiceTaskFailed();
        serviceTaskFailed.setServiceTaskId(serviceTaskId);
        serviceTaskFailed.setErrorCode(errorCode);
        serviceTaskFailed.setMessage(message);
        serviceTaskFailed.setDetails(details);
        return serviceTaskFailed;
    }
}
