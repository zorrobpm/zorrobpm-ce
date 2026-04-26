package com.zorrodev.bpm.contract.exception;

public class BpmnParseException extends RuntimeException {
    public BpmnParseException(Exception e) {
        super(e);
    }

    public BpmnParseException(String msg) {
        super(msg);
    }
}
