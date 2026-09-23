package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ErrorEventExtensionModel {
    /** The error code the event catches; {@code null} — any code. */
    private String errorCode;
}
