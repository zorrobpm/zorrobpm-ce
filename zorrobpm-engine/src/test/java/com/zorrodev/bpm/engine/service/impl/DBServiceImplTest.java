package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DBServiceImplTest {

    @Mock private ProcessDefinitionRepository processDefinitionRepository;
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private UserTaskRepository userTaskRepository;
    @Mock private VariableRepository variableRepository;
    @Mock private TokenRepository tokenRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private ProcessInstanceMapper processInstanceMapper;

    @InjectMocks
    private DBServiceImpl dbService;

    @Test
    void createProcessInstance_savesEntityAndVariables() {
        UUID parentActivityId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        ProcessVariable v1 = newVar("a", "1", ProcessVariableType.LONG);
        ProcessVariable v2 = newVar("b", "x", ProcessVariableType.STRING);

        UUID id = dbService.createProcessInstance(parentActivityId, processDefinitionId, List.of(v1, v2));

        assertThat(id).isNotNull();
        verify(processInstanceRepository).save(any(ProcessInstanceEntity.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProcessVariableEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(variableRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void createProcessInstance_nullVariables_savesEmpty() {
        UUID id = dbService.createProcessInstance(null, UUID.randomUUID(), null);

        assertThat(id).isNotNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ProcessVariableEntity>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(variableRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void createActivity_byElement_persistsEntity() {
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        BpmnElementModel element = new BpmnElementModel();
        element.setId("startEvent");
        element.setType(BpmnElementType.START_EVENT);

        UUID id = dbService.createActivity(processInstanceId, token, element);

        assertThat(id).isNotNull();
        ArgumentCaptor<ActivityEntity> captor = ArgumentCaptor.forClass(ActivityEntity.class);
        verify(activityRepository).saveAndFlush(captor.capture());
        ActivityEntity saved = captor.getValue();
        assertThat(saved.getBpmnElementId()).isEqualTo("startEvent");
        assertThat(saved.getType()).isEqualTo(BpmnElementType.START_EVENT);
        assertThat(saved.getProcessInstanceId()).isEqualTo(processInstanceId);
        assertThat(saved.getToken()).isEqualTo(token);
        assertThat(saved.getStatus()).isEqualTo(ActivityStatus.CREATED);
    }

    @Test
    void createActivity_byFlow_persistsAsSequenceFlow() {
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        BpmnFlowModel flow = new BpmnFlowModel();
        flow.setFlowId("flow1");

        UUID id = dbService.createActivity(processInstanceId, token, flow);

        assertThat(id).isNotNull();
        ArgumentCaptor<ActivityEntity> captor = ArgumentCaptor.forClass(ActivityEntity.class);
        verify(activityRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(BpmnElementType.SEQUENCE_FLOW);
        assertThat(captor.getValue().getBpmnElementId()).isEqualTo("flow1");
    }

    @Test
    void completeActivity_marksCompleted() {
        UUID activityId = UUID.randomUUID();

        dbService.completeActivity(activityId);

        verify(activityRepository).setStatusAndCompletedAt(eq(activityId), eq(ActivityStatus.COMPLETED), any(Instant.class));
    }

    @Test
    void getProcessInstance_returnsMappedDTO() {
        UUID id = UUID.randomUUID();
        ProcessInstanceEntity entity = new ProcessInstanceEntity();
        entity.setId(id);
        ProcessInstance dto = new ProcessInstance();
        dto.setId(id);

        when(processInstanceRepository.findById(id)).thenReturn(Optional.of(entity));
        when(processInstanceMapper.toDTO(entity)).thenReturn(dto);

        assertThat(dbService.getProcessInstance(id)).isSameAs(dto);
    }

    @Test
    void getProcessInstance_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(processInstanceRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dbService.getProcessInstance(id)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void createServiceTask_savesEntityFromActivityAndPI() {
        UUID activityId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();

        ActivityEntity activity = new ActivityEntity();
        activity.setId(activityId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId("svc1");
        activity.setCreatedAt(Instant.now());

        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(processInstanceRepository.findById(processInstanceId)).thenReturn(Optional.of(pi));

        dbService.createServiceTask(activityId);

        ArgumentCaptor<ServiceTaskEntity> captor = ArgumentCaptor.forClass(ServiceTaskEntity.class);
        verify(serviceTaskRepository).save(captor.capture());
        ServiceTaskEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(activityId);
        assertThat(saved.getBpmnElementId()).isEqualTo("svc1");
        assertThat(saved.getProcessInstanceId()).isEqualTo(processInstanceId);
        assertThat(saved.getProcessDefinitionId()).isEqualTo(processDefinitionId);
    }

    @Test
    void createUserTask_savesEntityFromActivityAndPI() {
        UUID activityId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();

        ActivityEntity activity = new ActivityEntity();
        activity.setId(activityId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId("ut1");
        activity.setCreatedAt(Instant.now());

        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(processInstanceRepository.findById(processInstanceId)).thenReturn(Optional.of(pi));

        dbService.createUserTask(activityId);

        ArgumentCaptor<UserTaskEntity> captor = ArgumentCaptor.forClass(UserTaskEntity.class);
        verify(userTaskRepository).save(captor.capture());
        UserTaskEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(activityId);
        assertThat(saved.getBpmnElementId()).isEqualTo("ut1");
        assertThat(saved.getProcessInstanceId()).isEqualTo(processInstanceId);
        assertThat(saved.getProcessDefinitionId()).isEqualTo(processDefinitionId);
    }

    @Test
    void completeServiceTask_callsRepository() {
        UUID id = UUID.randomUUID();
        dbService.completeServiceTask(id);
        verify(serviceTaskRepository).setCompletedAt(eq(id), any(Instant.class));
    }

    @Test
    void completeUserTask_callsRepository() {
        UUID id = UUID.randomUUID();
        dbService.completeUserTask(id);
        verify(userTaskRepository).setCompletedAt(eq(id), any(Instant.class));
    }

    @Test
    void getActivity_returnsMappedDTO() {
        UUID id = UUID.randomUUID();
        ActivityEntity entity = new ActivityEntity();
        entity.setId(id);
        entity.setBpmnElementId("e1");
        entity.setType(BpmnElementType.SERVICE_TASK);
        entity.setStatus(ActivityStatus.CREATED);
        entity.setProcessInstanceId(UUID.randomUUID());
        entity.setToken(UUID.randomUUID());

        when(activityRepository.findById(id)).thenReturn(Optional.of(entity));

        Activity result = dbService.getActivity(id);

        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getBpmnElementId()).isEqualTo("e1");
        assertThat(result.getType()).isEqualTo(BpmnElementType.SERVICE_TASK);
        assertThat(result.getProcessInstanceId()).isEqualTo(entity.getProcessInstanceId());
    }

    @Test
    void getActivity_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(activityRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dbService.getActivity(id)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getVariables_mapsRepositoryEntities() {
        UUID processInstanceId = UUID.randomUUID();
        ProcessVariableEntity e1 = new ProcessVariableEntity();
        e1.setName("a");
        e1.setTextValue("1");
        e1.setType(ProcessVariableType.LONG);
        ProcessVariableEntity e2 = new ProcessVariableEntity();
        e2.setName("b");
        e2.setTextValue("x");
        e2.setType(ProcessVariableType.STRING);

        when(variableRepository.findByProcessInstanceId(processInstanceId)).thenReturn(List.of(e1, e2));

        List<ProcessVariable> result = dbService.getVariables(processInstanceId);

        assertThat(result).extracting(ProcessVariable::getName).containsExactly("a", "b");
        assertThat(result).extracting(ProcessVariable::getValue).containsExactly("1", "x");
        assertThat(result).extracting(ProcessVariable::getType).containsExactly(ProcessVariableType.LONG, ProcessVariableType.STRING);
    }

    @Test
    void setVariables_savesAll() {
        UUID processInstanceId = UUID.randomUUID();
        List<ProcessVariable> vars = List.of(
            newVar("a", "1", ProcessVariableType.LONG),
            newVar("b", "x", ProcessVariableType.STRING)
        );

        dbService.setVariables(processInstanceId, vars);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ProcessVariableEntity>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(variableRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void getActivitiesByTokenAndBpmnElementId_returnsMapped() {
        UUID token = UUID.randomUUID();
        ActivityEntity e = new ActivityEntity();
        e.setId(UUID.randomUUID());
        e.setBpmnElementId("x");

        when(activityRepository.findByTokenAndBpmnElementId(token, "x")).thenReturn(List.of(e));

        List<Activity> result = dbService.getActivitiesByTokenAndBpmnElementId(token, "x");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getBpmnElementId()).isEqualTo("x");
    }

    @Test
    void getProcessDefinition_returnsMapped() {
        ProcessDefinitionEntity entity = new ProcessDefinitionEntity();
        entity.setId(UUID.randomUUID());
        entity.setKey("k");
        entity.setName("n");
        entity.setVersion(2);
        entity.setSha256("s");
        entity.setCreatedAt(Instant.now());

        when(processDefinitionRepository.findByKeyAndVersion("k", 2)).thenReturn(Optional.of(entity));

        ProcessDefinition result = dbService.getProcessDefinition("k", 2);

        assertThat(result.getId()).isEqualTo(entity.getId());
        assertThat(result.getKey()).isEqualTo("k");
        assertThat(result.getVersion()).isEqualTo(2);
    }

    @Test
    void getProcessDefinition_throwsWhenMissing() {
        when(processDefinitionRepository.findByKeyAndVersion("k", 2)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> dbService.getProcessDefinition("k", 2)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void createToken_savesAndReturnsDTO() {
        UUID parentId = UUID.randomUUID();

        Token token = dbService.createToken(parentId);

        assertThat(token.getId()).isNotNull();
        assertThat(token.getParentId()).isEqualTo(parentId);
        verify(tokenRepository).save(any(TokenEntity.class));
    }

    @Test
    void getToken_returnsMapped() {
        UUID id = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        TokenEntity entity = new TokenEntity();
        entity.setId(id);
        entity.setParentId(parentId);

        when(tokenRepository.findById(id)).thenReturn(Optional.of(entity));

        Token result = dbService.getToken(id);

        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getParentId()).isEqualTo(parentId);
    }

    @Test
    void getToken_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(tokenRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> dbService.getToken(id)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getMaxProcessDefinitionVersionByKey_returnsValueOrZero() {
        when(processDefinitionRepository.findMaxByKey("present")).thenReturn(Optional.of(7));
        when(processDefinitionRepository.findMaxByKey("absent")).thenReturn(Optional.empty());

        assertThat(dbService.getMaxProcessDefinitionVersionByKey("present")).isEqualTo(7);
        assertThat(dbService.getMaxProcessDefinitionVersionByKey("absent")).isEqualTo(0);
    }

    @Test
    void completeProcessInstance_callsRepository() {
        UUID id = UUID.randomUUID();
        dbService.completeProcessInstance(id);
        verify(processInstanceRepository).setCompletedAt(eq(id), any(Instant.class));
    }

    @Test
    void createIncident_savesAndReturnsId() {
        UUID activityId = UUID.randomUUID();
        ActivityEntity activity = new ActivityEntity();
        activity.setId(activityId);
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));

        UUID id = dbService.createIncident(activityId, "boom");

        assertThat(id).isNotNull();
        ArgumentCaptor<IncidentEntity> captor = ArgumentCaptor.forClass(IncidentEntity.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getActivityId()).isEqualTo(activityId);
        assertThat(captor.getValue().getMessage()).isEqualTo("boom");
    }

    @Test
    void getIncident_returnsNull() {
        Incident result = dbService.getIncident(UUID.randomUUID());
        assertThat(result).isNull();
    }

    private static ProcessVariable newVar(String name, String value, ProcessVariableType type) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(type);
        return v;
    }
}
