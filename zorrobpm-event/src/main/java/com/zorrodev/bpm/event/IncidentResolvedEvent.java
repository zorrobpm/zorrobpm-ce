package com.zorrodev.bpm.event;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class IncidentResolvedEvent extends ProcessEngineEvent {
    private String bpmnElementId;
    private String bpmnElementType;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;private Instant resolvedAt;
}
