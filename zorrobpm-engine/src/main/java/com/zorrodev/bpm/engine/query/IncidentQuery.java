package com.zorrodev.bpm.engine.query;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class IncidentQuery extends BaseQuery {
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;
    private String bpmnElementId;
}
