package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;

public interface BpmnParseService {

    BpmnProcessDefinitionModel parse(String bpmn);
}
