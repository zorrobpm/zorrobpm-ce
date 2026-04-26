package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class ProcessInstance {
    private UUID id;
    private UUID parentActivityId;
    private UUID processDefinitionId;
//    private String processDefinitionKey;
//    private Integer processDefinitionVersion;
    private Instant startedAt;
    private Instant completedAt;
//    private List<ProcessVariable> variables;
}
