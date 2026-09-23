package com.zorrodev.bpm.handler;

import lombok.Getter;

/**
 * Thrown by a {@link JobHandler} to fail the job with its own error code instead of the exception
 * class name. The engine records the code on the incident.
 */
@Getter
public class JobFailedException extends RuntimeException {

    private final String errorCode;

    public JobFailedException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public JobFailedException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
