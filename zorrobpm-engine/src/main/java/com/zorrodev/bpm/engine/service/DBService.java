package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Incident;
import com.zorrodev.bpm.engine.dto.Token;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

public interface DBService {

    UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables);

    UUID createActivity(UUID processInstanceId, UUID tokenId, BpmnElementModel element);

    UUID createActivity(UUID processInstanceId, UUID tokenId, BpmnFlowModel element);

    void completeActivity(UUID executionId);

    ProcessInstance getProcessInstance(UUID processInstanceId);

    void createServiceTask(UUID activityId);

    void completeServiceTask(UUID serviceTaskId);

    void createUserTask(UUID activityId);

    void completeUserTask(UUID serviceTaskId);

    Activity getActivity(UUID activityId);

    List<ProcessVariable> getVariables(@NonNull UUID processInstanceId);

    void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables);

    List<Activity> getActivitiesByTokenAndBpmnElementId(UUID tokenId, String incoming);

    ProcessDefinition getProcessDefinition(String key, Integer version);

    Token createToken(UUID parentId);

    Token getToken(UUID tokenId);

    Integer getMaxProcessDefinitionVersionByKey(String key);

    void completeProcessInstance(UUID processInstanceId);

    UUID createIncident(UUID activityId, String message);

    Incident getIncident(UUID incidentId);
}
