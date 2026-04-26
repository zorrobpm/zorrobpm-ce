package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;

import java.util.Optional;
import java.util.UUID;

public interface BpmnService {

    void addProcessDefinition(UUID id, BpmnProcessDefinitionModel model);

    BpmnProcessDefinitionModel getProcessDefinitionModelById(UUID id);
}
