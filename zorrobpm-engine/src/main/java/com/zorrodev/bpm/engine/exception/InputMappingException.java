package com.zorrodev.bpm.engine.exception;

/**
 * An input of an input mapping could not be evaluated. Not an {@code EngineException} on purpose:
 * on element entry it must become an incident, not an error of the command.
 */
public class InputMappingException extends RuntimeException {

    public InputMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
