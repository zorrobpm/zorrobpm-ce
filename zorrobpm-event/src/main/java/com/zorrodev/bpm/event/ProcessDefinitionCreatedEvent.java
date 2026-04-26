package com.zorrodev.bpm.event;

import com.zorrodev.bpm.event.data.ServiceTaskElement;
import com.zorrodev.bpm.event.data.UserTaskElement;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ProcessDefinitionCreatedEvent extends ProcessEngineEvent {
    private String bpmn;
    private UUID processDefinitionId;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;
    private String processDefinitionName;
    private String startFormKey;
    private Instant createdAt;
    private List<ServiceTaskElement> serviceTaskElements;
    private List<UserTaskElement> userTaskElements;
}
