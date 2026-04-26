package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CallActivityExtensionModel {
    private String processId;
    private String bindingType;
    private Boolean propagateAllChildVariables;
}
