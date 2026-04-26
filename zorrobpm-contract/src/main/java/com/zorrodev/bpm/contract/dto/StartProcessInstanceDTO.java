package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class StartProcessInstanceDTO {
    private UUID processDefinitionId;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;
    private List<ProcessVariable> variables = new ArrayList<>();
}
