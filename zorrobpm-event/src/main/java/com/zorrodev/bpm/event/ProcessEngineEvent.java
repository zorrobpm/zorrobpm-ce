package com.zorrodev.bpm.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ProcessDefinitionCreatedEvent.class, name = "ProcessDefinitionCreatedEvent"),
    @JsonSubTypes.Type(value = ProcessInstanceCreatedEvent.class, name = "ProcessInstanceCreatedEvent"),
    @JsonSubTypes.Type(value = ProcessInstanceCompletedEvent.class, name = "ProcessInstanceCompletedEvent"),
    @JsonSubTypes.Type(value = ServiceTaskInstanceCreatedEvent.class, name = "ServiceTaskInstanceCreatedEvent"),
    @JsonSubTypes.Type(value = ServiceTaskInstanceCompletedEvent.class, name = "ServiceTaskInstanceCompletedEvent"),
    @JsonSubTypes.Type(value = UserTaskInstanceCreatedEvent.class, name = "UserTaskInstanceCreatedEvent"),
    @JsonSubTypes.Type(value = UserTaskInstanceCompletedEvent.class, name = "UserTaskInstanceCompletedEvent"),
    @JsonSubTypes.Type(value = IncidentCreatedEvent.class, name = "IncidentCreatedEvent"),
    @JsonSubTypes.Type(value = IncidentResolvedEvent.class, name = "IncidentResolvedEvent"),
})
@Getter
@Setter
public abstract class ProcessEngineEvent {
    private UUID id;
    private String type;
}
