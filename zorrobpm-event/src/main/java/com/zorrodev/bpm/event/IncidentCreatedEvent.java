package com.zorrodev.bpm.event;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class IncidentCreatedEvent extends ProcessEngineEvent {
    private String bpmnElementId;
    private String bpmnElementType;
    private String message;
    private String description;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;
    private Instant createdAt;
}
