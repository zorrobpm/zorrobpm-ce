package com.zorrodev.bpm.contract.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * An input or output mapping of an element could not be evaluated. Not an {@link EngineException}
 * on purpose: on entering an element it must become an incident, not an error of the command. On a
 * synchronous completion (user task, child process) it rejects the command with {@code 422}.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class VariableMappingException extends RuntimeException {

    public VariableMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
