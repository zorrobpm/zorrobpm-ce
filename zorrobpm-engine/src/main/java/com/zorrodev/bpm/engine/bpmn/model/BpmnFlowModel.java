package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BpmnFlowModel {
    private String flowId;
    private String sourceRef;
    private String targetRef;
    private BpmnConditionExpressionModel conditionExpression;
}
