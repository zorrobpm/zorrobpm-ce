package com.zorrodev.bpm.event;

import com.zorrodev.bpm.event.data.Variable;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ProcessInstanceCreatedEvent extends ProcessEngineEvent {
    private String bpmnElementId;
    private UUID processDefinitionId;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;
    private Instant createdAt;
    private List<Variable> variables;
}
