package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@ToString
public class ServiceTaskData {
    private UUID serviceTaskId;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String processDefinitionKey;
    private String job;
    private Integer processDefinitionVersion;
    private List<ProcessVariable> variables;
}
