package com.zorrodev.bpm.contract.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The task is already completed, or was canceled (for example, interrupted by a boundary timer).
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class TaskNotActiveException extends EngineException {

    public TaskNotActiveException(String message) {
        super(message);
    }
}
