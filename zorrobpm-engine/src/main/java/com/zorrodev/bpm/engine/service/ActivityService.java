package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;

import java.util.List;
import java.util.UUID;

public interface ActivityService {

    void execute(UUID processInstanceId, UUID tokenId, String bpmnElementId);

    void completeServiceTask(UUID activityId, List<ProcessVariable> variables);

    void failServiceTask(UUID serviceTaskId, String message);

    void completeUserTask(UUID activityId, List<ProcessVariable> variables);

    void resolveIncident(UUID incidentId, List<ProcessVariable> variables);

    UUID startProcessInstance(UUID parentProcessInstanceId, UUID processDefinitionId, List<ProcessVariable> variables);
}
