package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ServiceTaskQueryParameters {
    private Integer pageIndex = 0;
    private Integer pageSize = 20;
    private UUID processDefinitionId;
    private UUID processInstanceId;
    private String processDefinitionKey;
    private String processDefinitionVersion;
}
