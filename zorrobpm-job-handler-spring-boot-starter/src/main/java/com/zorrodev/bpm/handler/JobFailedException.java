package com.zorrodev.bpm.handler;

import lombok.Getter;

import java.time.Duration;

/**
 * Thrown by a {@link JobHandler} to fail the job with its own error code instead of the exception
 * class name. The engine records the code on the incident.
 *
 * <p>Optionally overrides the engine's retry decision: {@code retries} is the number of retries still
 * available at this failure ({@code 0} — no retry, an incident right away), {@code retryTimeout} is the
 * delay before this retry. {@code null} leaves the decision to the retry settings of the service task.
 */
@Getter
public class JobFailedException extends RuntimeException {

    private final String errorCode;
    private final Integer retries;
    private final Duration retryTimeout;

    public JobFailedException(String errorCode, String message) {
        this(errorCode, message, null, null, null);
    }

    public JobFailedException(String errorCode, String message, Throwable cause) {
        this(errorCode, message, cause, null, null);
    }

    public JobFailedException(String errorCode, String message, Throwable cause, Integer retries, Duration retryTimeout) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retries = retries;
        this.retryTimeout = retryTimeout;
    }

    /** A copy of this exception with the given number of retries still available. */
    public JobFailedException withRetries(int retries) {
        return copy(retries, retryTimeout);
    }

    /** A copy of this exception with the given delay before the retry. */
    public JobFailedException withRetryTimeout(Duration retryTimeout) {
        return copy(retries, retryTimeout);
    }

    private JobFailedException copy(Integer retries, Duration retryTimeout) {
        JobFailedException copy = new JobFailedException(errorCode, getMessage(), getCause(), retries, retryTimeout);
        copy.setStackTrace(getStackTrace());
        return copy;
    }
}
