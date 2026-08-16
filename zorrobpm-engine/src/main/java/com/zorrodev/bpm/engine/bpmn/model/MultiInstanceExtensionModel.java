package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MultiInstanceExtensionModel {
    private boolean sequential;
    private String inputCollection;
    private String inputElement;
    private String outputCollection;
    private String outputElement;
}
