package com.zorrodev.bpm.handler;

import com.zorrodev.bpm.exchange.ProcessVariable;
import lombok.Getter;

import java.util.List;

/**
 * Thrown by a {@link JobHandler} to raise a BPMN error: an expected business outcome, such as "customer
 * not found", that the process catches on an error boundary event with the same {@code errorCode}.
 * Unlike a handler failure, it is not retried; the {@code variables} are written into the process
 * where the error is caught. An error that nothing catches becomes an incident on the service task.
 */
@Getter
public class BpmnError extends RuntimeException {

    private final String errorCode;
    private final List<ProcessVariable> variables;

    public BpmnError(String errorCode) {
        this(errorCode, null, List.of());
    }

    public BpmnError(String errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public BpmnError(String errorCode, String message, List<ProcessVariable> variables) {
        super(message);
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("BPMN error code must not be blank");
        }
        this.errorCode = errorCode;
        this.variables = variables == null ? List.of() : List.copyOf(variables);
    }
}
