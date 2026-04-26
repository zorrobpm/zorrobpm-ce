package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ProcessInstancesQueryParameters {
    private Integer pageIndex = 0;
    private Integer pageSize = 20;
    private UUID processDefinitionId;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;
    private Boolean withVariables = Boolean.FALSE;
}
