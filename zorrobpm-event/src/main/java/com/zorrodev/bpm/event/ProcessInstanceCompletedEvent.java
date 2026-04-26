package com.zorrodev.bpm.event;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class ProcessInstanceCompletedEvent extends ProcessEngineEvent {
    private String bpmnElementId;
    private UUID processDefinitionId;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;
    private Instant completedAt;
}
