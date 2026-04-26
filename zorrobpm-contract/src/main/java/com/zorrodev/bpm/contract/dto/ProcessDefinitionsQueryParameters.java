package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcessDefinitionsQueryParameters {
    private Integer pageIndex = 0;
    private Integer pageSize = 20;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;
    private Boolean latestVersionOnly;
}
