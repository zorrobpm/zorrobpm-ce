package com.zorrodev.bpm.contract.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class IncidentNotFoundException extends EngineException {

    public IncidentNotFoundException(String message) {
        super(message);
    }
}
