package com.zorrodev.bpm.contract.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class UserTaskAlreadyAssignedException extends EngineException {

    public UserTaskAlreadyAssignedException(String message) {
        super(message);
    }
}
