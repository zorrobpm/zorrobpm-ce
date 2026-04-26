package com.zorrodev.bpm.engine.query;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class ProcessInstanceQuery extends BaseQuery {
    private UUID parentProcessInstanceId;
    private UUID processDefinitionId;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;
}
