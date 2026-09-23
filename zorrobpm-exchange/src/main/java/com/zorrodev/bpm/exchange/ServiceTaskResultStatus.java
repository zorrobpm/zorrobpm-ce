package com.zorrodev.bpm.exchange;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Result of a job reported by a worker. A message without a status is a success, as before the
 * status existed; a value this engine does not know becomes {@link #UNSUPPORTED} instead of failing
 * the whole message, so it is never taken for a success and never dropped.
 */
public enum ServiceTaskResultStatus {
    SUCCESS,
    FAILURE,
    /** A business error for the process to catch on an error boundary event; not a handler failure. */
    BPMN_ERROR,
    UNSUPPORTED;

    @JsonCreator
    public static ServiceTaskResultStatus from(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "SUCCESS" -> SUCCESS;
            case "FAILURE" -> FAILURE;
            case "BPMN_ERROR" -> BPMN_ERROR;
            default -> UNSUPPORTED;
        };
    }

    @JsonValue
    public String value() {
        return name();
    }
}
