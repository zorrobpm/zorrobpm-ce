package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Incident;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.DBService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DBServiceImpl implements DBService {

    private final ProcessDefinitionRepository processDefinitionRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ActivityRepository activityRepository;
    private final ServiceTaskRepository serviceTaskRepository;
    private final UserTaskRepository userTaskRepository;
    private final VariableRepository variableRepository;
    private final TokenRepository tokenRepository;
    private final IncidentRepository incidentRepository;
    private final ProcessInstanceMapper processInstanceMapper;

    @Override
    public UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables) {
        UUID id = UUID.randomUUID();
        ProcessInstanceEntity entity = new ProcessInstanceEntity();
        entity.setId(id);
        entity.setProcessDefinitionId(processDefinitionId);
        entity.setStartedAt(Instant.now());
        entity.setParentActivityId(parentActivityId);
        processInstanceRepository.save(entity);
        List<ProcessVariableEntity> vs = new LinkedList<>();
        for (ProcessVariable variable : Optional.ofNullable(variables).orElse(List.of())) {
            ProcessVariableEntity v = new ProcessVariableEntity();
            v.setProcessInstanceId(id);
            v.setName(variable.getName());
            v.setType(variable.getType());
            v.setTextValue(variable.getValue());
            vs.add(v);
        }
        variableRepository.saveAll(vs);
        return id;
    }

    @Override
    public UUID createActivity(UUID processInstanceId, UUID token, BpmnElementModel element) {
        UUID id = UUID.randomUUID();
        ActivityEntity entity = new ActivityEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setCreatedAt(Instant.now());
        entity.setStatus(ActivityStatus.CREATED);
        entity.setType(element.getType());
        entity.setBpmnElementId(element.getId());
        entity.setToken(token);
        activityRepository.saveAndFlush(entity);
        return id;
    }

    @Override
    public UUID createActivity(UUID processInstanceId, UUID token, BpmnFlowModel element) {
        UUID id = UUID.randomUUID();
        ActivityEntity entity = new ActivityEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setCreatedAt(Instant.now());
        entity.setStatus(ActivityStatus.CREATED);
        entity.setType(BpmnElementType.SEQUENCE_FLOW);
        entity.setBpmnElementId(element.getFlowId());
        entity.setToken(token);
        activityRepository.saveAndFlush(entity);
        return id;
    }

    @Override
    public void completeActivity(UUID activityId) {
        activityRepository.setStatusAndCompletedAt(activityId, ActivityStatus.COMPLETED, Instant.now());
    }

    @Override
    public ProcessInstance getProcessInstance(UUID processInstanceId) {
        ProcessInstanceEntity entity = processInstanceRepository.findById(processInstanceId).orElseThrow();
        return processInstanceMapper.toDTO(entity);
    }

    @Override
    public void createServiceTask(UUID activityId) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        ServiceTaskEntity entity = new ServiceTaskEntity();
        entity.setId(activity.getId());
        entity.setBpmnElementId(activity.getBpmnElementId());
        entity.setProcessInstanceId(activity.getProcessInstanceId());
        entity.setCreatedAt(activity.getCreatedAt());

        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        entity.setProcessDefinitionId(pi.getProcessDefinitionId());

        serviceTaskRepository.save(entity);
    }

    @Override
    public void createUserTask(UUID activityId) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        UserTaskEntity entity = new UserTaskEntity();
        entity.setId(activity.getId());
        entity.setBpmnElementId(activity.getBpmnElementId());
        entity.setProcessInstanceId(activity.getProcessInstanceId());
        entity.setCreatedAt(activity.getCreatedAt());

        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        entity.setProcessDefinitionId(pi.getProcessDefinitionId());

        userTaskRepository.save(entity);
    }

    @Override
    public void completeServiceTask(UUID serviceTaskId) {
        serviceTaskRepository.setCompletedAt(serviceTaskId, Instant.now());
    }

    @Override
    public void completeUserTask(UUID userTaskId) {
        userTaskRepository.setCompletedAt(userTaskId, Instant.now());
    }

    @Override
    public Activity getActivity(UUID activityId) {
        ActivityEntity activityEntity = activityRepository.findById(activityId).orElseThrow();
        return getActivity(activityEntity);
    }

    @Override
    public List<ProcessVariable> getVariables(@NonNull UUID processInstanceId) {
        List<ProcessVariableEntity> variables = variableRepository.findByProcessInstanceId(processInstanceId);
        return variables.stream()
            .map(variable -> {
                ProcessVariable result = new ProcessVariable();
                result.setName(variable.getName());
                result.setType(variable.getType());
                result.setValue(variable.getTextValue());
                return result;
            })
            .toList();
    }

    @Override
    public void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables) {
        List<ProcessVariableEntity> entities = new ArrayList<>();
        for (ProcessVariable variable : variables) {
            ProcessVariableEntity entity = new ProcessVariableEntity();
            entity.setProcessInstanceId(processInstanceId);
            entity.setName(variable.getName());
            entity.setType(variable.getType());
            entity.setTextValue(variable.getValue());
            entities.add(entity);
        }
        variableRepository.saveAll(entities);
    }

    @Override
    public List<Activity> getActivitiesByTokenAndBpmnElementId(UUID token, String bpmnElementId) {
        return activityRepository.findByTokenAndBpmnElementId(token, bpmnElementId).stream()
            .map(this::getActivity)
            .toList();
    }

    @Override
    public ProcessDefinition getProcessDefinition(String key, Integer version) {
        ProcessDefinitionEntity entity = processDefinitionRepository.findByKeyAndVersion(key, version).orElseThrow();
        ProcessDefinition result = new ProcessDefinition();
        result.setId(entity.getId());
        result.setName(entity.getName());
        result.setKey(entity.getKey());
        result.setSha256(entity.getSha256());
        result.setCreatedAt(entity.getCreatedAt());
        result.setStartFormKey(entity.getStartFormKey());
        result.setVersion(entity.getVersion());
        return result;
    }

    @Override
    public Token createToken(UUID parentId) {
        TokenEntity tokenEntity = new TokenEntity();
        tokenEntity.setId(UUID.randomUUID());
        tokenEntity.setParentId(parentId);
        tokenRepository.save(tokenEntity);

        Token token = new Token();
        token.setId(tokenEntity.getId());
        token.setParentId(tokenEntity.getParentId());
        return token;
    }

    @Override
    public Token getToken(UUID tokenId) {
        return tokenRepository.findById(tokenId)
            .map(t -> {
                Token token = new Token();
                token.setId(t.getId());
                token.setParentId(t.getParentId());
                return token;
            })
            .orElseThrow();
    }

    @Override
    public Integer getMaxProcessDefinitionVersionByKey(String key) {
        return processDefinitionRepository.findMaxByKey(key).orElse(0);
    }

    @Override
    public void completeProcessInstance(UUID processInstanceId) {
        processInstanceRepository.setCompletedAt(processInstanceId, Instant.now());
    }

    @Override
    public UUID createIncident(UUID activityId, String message) {
        ActivityEntity activityEntity = activityRepository.findById(activityId).orElseThrow();
        UUID id = UUID.randomUUID();

        IncidentEntity entity = new IncidentEntity();
        entity.setId(id);
        entity.setActivityId(activityId);
        entity.setCreatedAt(Instant.now());
        entity.setMessage(message);
        incidentRepository.save(entity);

        return id;
    }

    @Override
    public Incident getIncident(UUID incidentId) {
        return null;
    }

    private Activity getActivity(ActivityEntity activityEntity) {
        Activity activity = new Activity();
        activity.setId(activityEntity.getId());
        activity.setProcessInstanceId(activityEntity.getProcessInstanceId());
        activity.setBpmnElementId(activityEntity.getBpmnElementId());
        activity.setCreatedAt(activityEntity.getCreatedAt());
        activity.setCompletedAt(activityEntity.getCompletedAt());
        activity.setStatus(activityEntity.getStatus());
        activity.setType(activityEntity.getType());
        activity.setToken(activityEntity.getToken());
        return activity;
    }
}
