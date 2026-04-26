package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;

import java.util.Optional;
import java.util.UUID;

public interface ProcessDefinitionService {

    Optional<ProcessDefinition> getProcessDefinitionById(UUID id);

    ProcessDefinition addProcessDefinition(String bpmn);

    PagedDataDTO<ProcessDefinition> getProcessDefinitions(ProcessDefinitionsQueryParameters parameters);
}
