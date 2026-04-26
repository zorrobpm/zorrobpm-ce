package com.zorrodev.bpm.event;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public abstract class TaskInstanceEvent extends ProcessEngineEvent {
    private String bpmnElementId;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;
}
