package com.zorrodev.bpm.contract.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ServiceTaskNotFoundException extends EngineException {

    public ServiceTaskNotFoundException(String message) {
        super(message);
    }
}
