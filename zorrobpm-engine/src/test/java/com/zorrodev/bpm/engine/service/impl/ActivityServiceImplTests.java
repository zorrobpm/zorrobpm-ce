package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.exception.IncidentAlreadyResolvedException;
import com.zorrodev.bpm.contract.exception.IncidentNotFoundException;
import com.zorrodev.bpm.contract.exception.ServiceTaskNotFoundException;
import com.zorrodev.bpm.contract.exception.TaskNotActiveException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.FailureOutcome;
import com.zorrodev.bpm.engine.dto.RetryOverride;
import com.zorrodev.bpm.engine.dto.ServiceTaskRetryState;
import com.zorrodev.bpm.engine.dto.Timer;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerKind;
import com.zorrodev.bpm.engine.entity.TimerStatus;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.exchange.ErrorReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension .class)
public class ActivityServiceImplTests {

    private final BpmnParseService bpmnParseService = new BpmnParseServiceImpl();

    @Mock
    private DBService dbService;

    @Mock
    private BpmnService bpmnService;

    @Mock
    private ScriptService scriptService;

    @Mock
    private ServiceTaskEnqueueService serviceTaskEnqueueService;

    @Mock
    private Clock clock;

    @InjectMocks
    private ActivityServiceImpl activityService;

    @BeforeEach
    void stubLocksAndLookups() {
        // The locked read returns what the test stubbed for the plain read.
        lenient().when(dbService.getProcessInstanceForUpdate(any())).thenAnswer(invocation -> {
            ProcessInstance instance = dbService.getProcessInstance(invocation.getArgument(0));
            return instance != null ? instance : new ProcessInstance();
        });
        lenient().when(dbService.findParentActivityId(any())).thenReturn(Optional.empty());
        lenient().when(dbService.hasServiceTask(any())).thenReturn(true);
    }

    @Test
    public void test1() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test1.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());

        activityService.execute(processInstanceId, token, "startEvent");

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(2)).createActivity(eq(processInstanceId), eq(token), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(1)).createActivity(eq(processInstanceId), eq(token), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT);
    }

    @Test
    public void test2() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test2.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent2"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow2"))).thenReturn(UUID.randomUUID());

        activityService.execute(processInstanceId, token, "startEvent");

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(3)).createActivity(eq(processInstanceId), eq(token), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(2)).createActivity(eq(processInstanceId), eq(token), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT);
    }

    @Test
    @Transactional
    @Rollback(false)
    public void test3() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test3.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        UUID serviceTaskId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setType(BpmnElementType.SERVICE_TASK);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId("serviceTask1");
        activity.setToken(token);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.getActivityForUpdate(serviceTaskId)).thenReturn(activity);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("serviceTask1"))).thenReturn(serviceTaskId);
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow2"))).thenReturn(UUID.randomUUID());

        activityService.execute(processInstanceId, token, "startEvent");
        activityService.completeServiceTask(serviceTaskId, List.of());

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(3)).createActivity(eq(processInstanceId), eq(token), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(2)).createActivity(eq(processInstanceId), eq(token), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT, BpmnElementType.SERVICE_TASK);
    }

    @Test
    public void test4() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test4.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        UUID userTaskId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setType(BpmnElementType.USER_TASK);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId("userTask1");
        activity.setToken(token);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.getActivityForUpdate(userTaskId)).thenReturn(activity);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("userTask1"))).thenReturn(userTaskId);
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow2"))).thenReturn(UUID.randomUUID());

        activityService.execute(processInstanceId, token, "startEvent");
        activityService.completeUserTask(userTaskId, List.of());

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(3)).createActivity(eq(processInstanceId), eq(token), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(2)).createActivity(eq(processInstanceId), eq(token), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT, BpmnElementType.USER_TASK);
    }

    @Test
    public void test5() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test5.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("xor1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("xor2"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow2"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow4"))).thenReturn(UUID.randomUUID());
        when(scriptService.evaluateScript(eq("x = 1"), any())).thenReturn(Boolean.TRUE);

        activityService.execute(processInstanceId, token, "startEvent");

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(4)).createActivity(eq(processInstanceId), eq(token), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(3)).createActivity(eq(processInstanceId), eq(token), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT, BpmnElementType.EXCLUSIVE_GATEWAY);
    }

    @Test
    public void test6() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test6.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("xor1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("xor2"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow3"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow4"))).thenReturn(UUID.randomUUID());
        when(scriptService.evaluateScript(eq("x = 1"), any())).thenReturn(Boolean.FALSE);

        activityService.execute(processInstanceId, token, "startEvent");

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(4)).createActivity(eq(processInstanceId), eq(token), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(3)).createActivity(eq(processInstanceId), eq(token), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT, BpmnElementType.EXCLUSIVE_GATEWAY);
    }

    @Test
    public void test7() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test7.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        UUID tokenId = UUID.randomUUID();

        Token token1 = new Token();
        token1.setId(tokenId);
        token1.setParentId(null);

        Token token2 = new Token();
        token2.setId(UUID.randomUUID());
        token2.setParentId(token1.getId());

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("startEvent")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("endEvent")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("parallel1")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("parallel2")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getFlow("flow1")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getFlow("flow2")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getFlow("flow3")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getFlow("flow4")))).thenReturn(UUID.randomUUID());
        when(dbService.createToken(isNull())).thenReturn(token1);
        when(dbService.createToken(eq(token1.getId()))).thenReturn(token2);
        when(dbService.getToken(eq(token2.getId()))).thenReturn(token2);

        Activity activity = new Activity();
        when(dbService.getActivitiesByTokenAndBpmnElementId(any(UUID.class), eq("flow2"))).thenReturn(List.of(activity));
        when(dbService.getActivitiesByTokenAndBpmnElementId(any(UUID.class), eq("flow3"))).thenReturn(List.of(), List.of(activity));

        activityService.execute(processInstanceId, tokenId, "startEvent");

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(4)).createActivity(eq(processInstanceId), any(UUID.class), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(4)).createActivity(eq(processInstanceId), any(UUID.class), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT, BpmnElementType.PARALLEL_GATEWAY);
    }

    @Test
    public void test8() throws IOException {
        String bpmnStr = Files.readString(Path.of("src/test/files/test8.bpmn"));
        String dummyBpmnStr = Files.readString(Path.of("src/test/files/test1.bpmn"));
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(bpmnStr);
        BpmnProcessDefinitionModel dummyBpmn = bpmnParseService.parse(dummyBpmnStr);

        UUID processDefinitionId = UUID.randomUUID();
        UUID dummyProcessDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID dummyProcessInstanceId = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        UUID callActivityId = UUID.randomUUID();

        ProcessInstance dummyPi = new ProcessInstance();
        dummyPi.setId(dummyProcessInstanceId);
        dummyPi.setProcessDefinitionId(dummyProcessDefinitionId);
        dummyPi.setParentActivityId(callActivityId);

        ProcessDefinition dummyProcessDefinition = new ProcessDefinition();
        dummyProcessDefinition.setId(dummyProcessDefinitionId);

        UUID tokenId = UUID.randomUUID();

        Token token1 = new Token();
        token1.setId(tokenId);
        token1.setParentId(null);

        Token token2 = new Token();
        token2.setId(UUID.randomUUID());
        token2.setParentId(token1.getId());

        Activity callActivity = new Activity();
        callActivity.setId(callActivityId);
        callActivity.setToken(tokenId);
        callActivity.setProcessInstanceId(processInstanceId);
        callActivity.setBpmnElementId("callActivity1");
        callActivity.setType(BpmnElementType.CALL_ACTIVITY);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(bpmnService.getProcessDefinitionModelById(dummyProcessDefinitionId)).thenReturn(dummyBpmn);
        when(dbService.getProcessDefinition(eq("dummy-process"), any())).thenReturn(dummyProcessDefinition);
        when(dbService.createProcessInstance(any(UUID.class), eq(dummyProcessDefinitionId), any())).thenReturn(dummyProcessInstanceId);
        when(dbService.getProcessInstance(eq(dummyProcessInstanceId))).thenReturn(dummyPi);
        when(dbService.getProcessInstance(eq(processInstanceId))).thenReturn(pi);
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("startEvent1")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("endEvent1")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getElement("callActivity1")))).thenReturn(callActivityId);
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getFlow("flow1")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(processInstanceId), any(UUID.class), eq(bpmn.getFlow("flow2")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(dummyProcessInstanceId), any(UUID.class), eq(dummyBpmn.getElement("startEvent")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(dummyProcessInstanceId), any(UUID.class), eq(dummyBpmn.getElement("endEvent")))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(eq(dummyProcessInstanceId), any(UUID.class), eq(dummyBpmn.getFlow("flow1")))).thenReturn(UUID.randomUUID());
        when(dbService.getActivity(callActivityId)).thenReturn(callActivity);
        lenient().when(dbService.getActivityForUpdate(callActivityId)).thenReturn(callActivity);
        when(dbService.createToken(isNull())).thenReturn(token1);
        when(dbService.createToken(eq(token1.getId()))).thenReturn(token2);
        when(dbService.getToken(eq(token1.getId()))).thenReturn(token1);

        activityService.execute(processInstanceId, tokenId, "startEvent1");

        ArgumentCaptor<BpmnElementModel> elementCaptor = ArgumentCaptor.forClass(BpmnElementModel.class);
        verify(dbService, times(3)).createActivity(eq(processInstanceId), any(UUID.class), elementCaptor.capture());

        ArgumentCaptor<BpmnFlowModel> flowCaptor = ArgumentCaptor.forClass(BpmnFlowModel.class);
        verify(dbService, times(2)).createActivity(eq(processInstanceId), any(UUID.class), flowCaptor.capture());

        List<BpmnElementType> elementTypes = elementCaptor.getAllValues().stream().map(BpmnElementModel::getType).toList();

        assertThat(elementTypes).contains(BpmnElementType.START_EVENT, BpmnElementType.END_EVENT, BpmnElementType.CALL_ACTIVITY);
    }

    @Test
    public void failServiceTask_createsIncidentAndMarksActivityError() {
        UUID serviceTaskId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        when(dbService.hasServiceTask(serviceTaskId)).thenReturn(true);
        when(dbService.getActivityForUpdate(serviceTaskId)).thenReturn(serviceTaskActivity(serviceTaskId, ActivityStatus.CREATED, null));
        when(dbService.findOpenIncidentId(serviceTaskId)).thenReturn(Optional.empty());
        ErrorReport error = new ErrorReport("CARD_DECLINED", "boom", "stack");
        when(dbService.getServiceTaskRetryState(serviceTaskId)).thenReturn(new ServiceTaskRetryState(0, null));
        when(dbService.createIncident(serviceTaskId, error)).thenReturn(incidentId);

        FailureOutcome result = activityService.failServiceTask(serviceTaskId, error, RetryOverride.NONE);

        assertThat(result).isEqualTo(new FailureOutcome(incidentId, 0, null));
        verify(dbService).createIncident(serviceTaskId, error);
        verify(dbService, never()).scheduleServiceTaskRetry(any(), anyInt(), any(), any());
        verify(dbService).setActivityStatus(serviceTaskId, ActivityStatus.ERROR);
        verify(dbService, never()).completeServiceTask(any());
        verify(dbService, never()).completeActivity(any());
    }

    @Test
    public void failServiceTask_returnsOpenIncidentOnRepeatedFailure() {
        UUID serviceTaskId = UUID.randomUUID();
        UUID openIncidentId = UUID.randomUUID();
        when(dbService.hasServiceTask(serviceTaskId)).thenReturn(true);
        when(dbService.getActivityForUpdate(serviceTaskId)).thenReturn(serviceTaskActivity(serviceTaskId, ActivityStatus.ERROR, null));
        when(dbService.findOpenIncidentId(serviceTaskId)).thenReturn(Optional.of(openIncidentId));

        FailureOutcome result = activityService.failServiceTask(serviceTaskId, new ErrorReport(null, "boom again", null), RetryOverride.NONE);

        assertThat(result.incidentId()).isEqualTo(openIncidentId);
        verify(dbService, never()).createIncident(any(), any());
        verify(dbService, never()).setActivityStatus(any(), any());
    }

    @Test
    public void failServiceTask_rejectsCompletedServiceTask() {
        UUID serviceTaskId = UUID.randomUUID();
        when(dbService.hasServiceTask(serviceTaskId)).thenReturn(true);
        when(dbService.getActivityForUpdate(serviceTaskId)).thenReturn(serviceTaskActivity(serviceTaskId, ActivityStatus.COMPLETED, Instant.now()));

        assertThatThrownBy(() -> activityService.failServiceTask(serviceTaskId, new ErrorReport(null, "boom", null), RetryOverride.NONE))
            .isInstanceOf(TaskNotActiveException.class);

        verify(dbService, never()).createIncident(any(), any());
        verify(dbService, never()).setActivityStatus(any(), any());
    }

    @Test
    public void failServiceTask_rejectsIdWithoutServiceTask() {
        UUID unknownOrUserTaskId = UUID.randomUUID();
        when(dbService.hasServiceTask(unknownOrUserTaskId)).thenReturn(false);

        assertThatThrownBy(() -> activityService.failServiceTask(unknownOrUserTaskId, new ErrorReport(null, "boom", null), RetryOverride.NONE))
            .isInstanceOf(ServiceTaskNotFoundException.class);

        verify(dbService, never()).getActivityForUpdate(any());
        verify(dbService, never()).createIncident(any(), any());
    }

    @Test
    public void failServiceTask_withRetriesLeftSchedulesRetryInsteadOfIncident() {
        UUID serviceTaskId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-23T10:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(dbService.getActivityForUpdate(serviceTaskId)).thenReturn(serviceTaskActivity(serviceTaskId, ActivityStatus.CREATED, null));
        when(dbService.findOpenIncidentId(serviceTaskId)).thenReturn(Optional.empty());
        when(dbService.getServiceTaskRetryState(serviceTaskId)).thenReturn(new ServiceTaskRetryState(2, null));
        ErrorReport error = new ErrorReport("java.net.SocketTimeoutException", "timeout", null);

        FailureOutcome result = activityService.failServiceTask(serviceTaskId, error, new RetryOverride(null, Duration.ofMinutes(1)));

        assertThat(result).isEqualTo(new FailureOutcome(null, 1, now.plusSeconds(60)));
        verify(dbService).scheduleServiceTaskRetry(serviceTaskId, 1, error, now.plusSeconds(60));
        verify(dbService, never()).createIncident(any(), any());
        verify(dbService, never()).setActivityStatus(any(), any());
    }

    @Test
    public void failServiceTask_workerWithZeroRetriesGetsIncidentAtOnce() {
        UUID serviceTaskId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        when(dbService.getActivityForUpdate(serviceTaskId)).thenReturn(serviceTaskActivity(serviceTaskId, ActivityStatus.CREATED, null));
        when(dbService.findOpenIncidentId(serviceTaskId)).thenReturn(Optional.empty());
        when(dbService.getServiceTaskRetryState(serviceTaskId)).thenReturn(new ServiceTaskRetryState(3, null));
        ErrorReport error = new ErrorReport("CARD_DECLINED", "card declined", null);
        when(dbService.createIncident(serviceTaskId, error)).thenReturn(incidentId);

        FailureOutcome result = activityService.failServiceTask(serviceTaskId, error, RetryOverride.NO_RETRY);

        assertThat(result).isEqualTo(new FailureOutcome(incidentId, 0, null));
        verify(dbService).setServiceTaskRetries(serviceTaskId, 0);
        verify(dbService).setActivityStatus(serviceTaskId, ActivityStatus.ERROR);
        verify(dbService, never()).scheduleServiceTaskRetry(any(), anyInt(), any(), any());
    }

    @Test
    public void failServiceTask_ignoresFailureWhileRetryIsPending() {
        UUID serviceTaskId = UUID.randomUUID();
        Instant nextRetryAt = Instant.parse("2026-09-23T10:01:00Z");
        when(dbService.getActivityForUpdate(serviceTaskId)).thenReturn(serviceTaskActivity(serviceTaskId, ActivityStatus.CREATED, null));
        when(dbService.findOpenIncidentId(serviceTaskId)).thenReturn(Optional.empty());
        when(dbService.getServiceTaskRetryState(serviceTaskId)).thenReturn(new ServiceTaskRetryState(1, nextRetryAt));

        FailureOutcome result = activityService.failServiceTask(serviceTaskId, new ErrorReport(null, "again", null), RetryOverride.NONE);

        assertThat(result).isEqualTo(new FailureOutcome(null, 1, nextRetryAt));
        verify(dbService, never()).scheduleServiceTaskRetry(any(), anyInt(), any(), any());
        verify(dbService, never()).createIncident(any(), any());
        verify(dbService, never()).setServiceTaskRetries(any(), anyInt());
    }

    @Test
    public void fireTimer_retryRequeuesJobWithoutBoundaryPath() {
        UUID serviceTaskId = UUID.randomUUID();
        UUID timerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-23T10:01:00Z");
        when(clock.instant()).thenReturn(now);
        when(dbService.findActivityForUpdateSkipLocked(serviceTaskId)).thenReturn(Optional.of(serviceTaskActivity(serviceTaskId, ActivityStatus.CREATED, null)));
        when(dbService.getTimerForUpdate(timerId)).thenReturn(Optional.of(retryTimer(timerId, serviceTaskId, now)));

        boolean fired = activityService.fireTimer(timerId, serviceTaskId);

        assertThat(fired).isTrue();
        verify(dbService).setTimerStatus(timerId, TimerStatus.FIRED);
        verify(dbService).clearNextRetryAt(serviceTaskId);
        verify(serviceTaskEnqueueService).enqueueAfterCommit(serviceTaskId);
        verify(dbService, never()).createActivity(any(), any(), any(BpmnElementModel.class));
        verify(dbService, never()).terminateActivity(any());
    }

    @Test
    public void fireTimer_retryOfClosedServiceTaskIsCanceled() {
        UUID serviceTaskId = UUID.randomUUID();
        UUID timerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-23T10:01:00Z");
        when(clock.instant()).thenReturn(now);
        when(dbService.findActivityForUpdateSkipLocked(serviceTaskId)).thenReturn(Optional.of(serviceTaskActivity(serviceTaskId, ActivityStatus.TERMINATED, now)));
        when(dbService.getTimerForUpdate(timerId)).thenReturn(Optional.of(retryTimer(timerId, serviceTaskId, now)));

        boolean fired = activityService.fireTimer(timerId, serviceTaskId);

        assertThat(fired).isFalse();
        verify(dbService).setTimerStatus(timerId, TimerStatus.CANCELED);
        verify(serviceTaskEnqueueService, never()).enqueueAfterCommit(any());
    }

    private static Timer retryTimer(UUID timerId, UUID serviceTaskId, Instant dueAt) {
        Timer timer = new Timer();
        timer.setId(timerId);
        timer.setKind(TimerKind.RETRY);
        timer.setActivityId(serviceTaskId);
        timer.setBpmnElementId("serviceTask1");
        timer.setDueAt(dueAt);
        timer.setStatus(TimerStatus.SCHEDULED);
        return timer;
    }

    /** The BPMN model of the activity's service task with the given retries. */
    private void stubServiceTaskModel(Activity activity, int retries) {
        UUID processDefinitionId = UUID.randomUUID();
        ProcessInstance pi = new ProcessInstance();
        pi.setId(activity.getProcessInstanceId());
        pi.setProcessDefinitionId(processDefinitionId);
        ServiceTaskExtensionModel extension = new ServiceTaskExtensionModel();
        extension.setJob("charge");
        extension.setRetries(retries);
        BpmnElementModel element = new BpmnElementModel();
        element.setId(activity.getBpmnElementId());
        element.setType(BpmnElementType.SERVICE_TASK);
        element.setExtensions(new BpmnElementExtensionModel());
        element.getExtensions().setServiceTaskExtension(extension);
        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        bpmn.addElement(element);
        when(dbService.getProcessInstance(activity.getProcessInstanceId())).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
    }

    private static Activity serviceTaskActivity(UUID id, ActivityStatus status, Instant completedAt) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setProcessInstanceId(UUID.randomUUID());
        activity.setToken(UUID.randomUUID());
        activity.setBpmnElementId("serviceTask1");
        activity.setType(BpmnElementType.SERVICE_TASK);
        activity.setStatus(status);
        activity.setCompletedAt(completedAt);
        return activity;
    }

    @Test
    public void executionErrorInGatewayConditionBecomesIncidentOnGateway() throws IOException {
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(Files.readString(Path.of("src/test/files/process4.bpmn")));

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID gatewayActivityId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        Activity gatewayActivity = new Activity();
        gatewayActivity.setId(gatewayActivityId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow1"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("xor1"))).thenReturn(gatewayActivityId);
        when(scriptService.evaluateScript(eq("a > 5"), any())).thenThrow(new IllegalStateException("a is undefined"));
        when(dbService.findOpenActivity(token, "xor1")).thenReturn(Optional.of(gatewayActivity));
        when(dbService.createIncident(eq(gatewayActivityId), any())).thenReturn(incidentId);

        activityService.execute(processInstanceId, token, "startEvent");

        verify(dbService).cancelOpenChildUserTasks(gatewayActivityId);
        verify(dbService).setActivityStatus(gatewayActivityId, ActivityStatus.ERROR);
        ArgumentCaptor<ErrorReport> error = ArgumentCaptor.forClass(ErrorReport.class);
        verify(dbService).createIncident(eq(gatewayActivityId), error.capture());
        assertThat(error.getValue().getErrorCode()).isEqualTo("java.lang.IllegalStateException");
        assertThat(error.getValue().getMessage()).isEqualTo("a is undefined");
        assertThat(error.getValue().getDetails()).startsWith("java.lang.IllegalStateException: a is undefined").contains("\tat ");
        verify(dbService, never()).createActivity(processInstanceId, token, bpmn.getElement("userTask1"));
        verify(dbService, never()).createActivity(processInstanceId, token, bpmn.getElement("userTask2"));
    }

    @Test
    public void databaseErrorIsNotTurnedIntoIncident() throws IOException {
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(Files.readString(Path.of("src/test/files/process4.bpmn")));

        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("startEvent")))
            .thenThrow(new DataIntegrityViolationException("fk"));

        assertThatThrownBy(() -> activityService.execute(processInstanceId, token, "startEvent"))
            .isInstanceOf(DataIntegrityViolationException.class);
        verify(dbService, never()).createIncident(any(), any());
    }

    @Test
    public void resolveIncident_serviceTaskIsRequeued() {
        UUID serviceTaskId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        Activity activity = serviceTaskActivity(serviceTaskId, ActivityStatus.ERROR, null);
        List<ProcessVariable> variables = List.of(new ProcessVariable());
        when(dbService.getIncident(incidentId)).thenReturn(incident(incidentId, serviceTaskId, null));
        when(dbService.getActivityForUpdate(serviceTaskId)).thenReturn(activity);
        stubServiceTaskModel(activity, 2);

        activityService.resolveIncident(incidentId, variables);

        verify(dbService).setVariables(activity.getProcessInstanceId(), variables);
        verify(dbService).resolveIncident(incidentId);
        verify(dbService).setActivityStatus(serviceTaskId, ActivityStatus.CREATED);
        verify(dbService).setServiceTaskRetries(serviceTaskId, 2);
        verify(serviceTaskEnqueueService).enqueueAfterCommit(serviceTaskId);
        verify(dbService, never()).terminateActivity(any());
    }

    @Test
    public void resolveIncident_engineErrorTerminatesActivityAndReexecutesElement() throws IOException {
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(Files.readString(Path.of("src/test/files/process4.bpmn")));
        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID failedActivityId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        Activity failed = new Activity();
        failed.setId(failedActivityId);
        failed.setProcessInstanceId(processInstanceId);
        failed.setToken(token);
        failed.setBpmnElementId("userTask1");
        failed.setType(BpmnElementType.USER_TASK);
        failed.setStatus(ActivityStatus.ERROR);

        UUID newActivityId = UUID.randomUUID();
        when(dbService.getIncident(incidentId)).thenReturn(incident(incidentId, failedActivityId, null));
        when(dbService.getActivityForUpdate(failedActivityId)).thenReturn(failed);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("userTask1"))).thenReturn(newActivityId);

        activityService.resolveIncident(incidentId, null);

        verify(dbService, never()).setVariables(any(), any());
        verify(dbService).resolveIncident(incidentId);
        verify(dbService).terminateActivity(failedActivityId);
        verify(dbService).createUserTask(eq(newActivityId), eq(bpmn.getElement("userTask1")), any());
        verify(serviceTaskEnqueueService, never()).enqueueAfterCommit(any());
    }

    @Test
    public void resolveIncident_closedIncidentIsConflict() {
        UUID serviceTaskId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        when(dbService.getIncident(incidentId)).thenReturn(incident(incidentId, serviceTaskId, Instant.now()));
        when(dbService.getActivityForUpdate(serviceTaskId)).thenReturn(serviceTaskActivity(serviceTaskId, ActivityStatus.CREATED, null));

        assertThatThrownBy(() -> activityService.resolveIncident(incidentId, List.of()))
            .isInstanceOf(IncidentAlreadyResolvedException.class);
        verify(dbService, never()).resolveIncident(any());
        verify(serviceTaskEnqueueService, never()).enqueueAfterCommit(any());
    }

    @Test
    public void resolveIncident_unknownIncidentIsNotFound() {
        UUID incidentId = UUID.randomUUID();
        when(dbService.getIncident(incidentId)).thenThrow(new IncidentNotFoundException("missing"));

        assertThatThrownBy(() -> activityService.resolveIncident(incidentId, List.of()))
            .isInstanceOf(IncidentNotFoundException.class);
        verify(dbService, never()).resolveIncident(any());
    }

    private static Incident incident(UUID id, UUID activityId, Instant completedAt) {
        Incident incident = new Incident();
        incident.setId(id);
        incident.setActivityId(activityId);
        incident.setMessage("boom");
        incident.setCreatedAt(Instant.now());
        incident.setCompletedAt(completedAt);
        return incident;
    }

    @Test
    public void completeServiceTask_closesOpenIncidentAndAdvances() throws IOException {
        BpmnProcessDefinitionModel bpmn = bpmnParseService.parse(Files.readString(Path.of("src/test/files/process2.bpmn")));
        UUID processDefinitionId = UUID.randomUUID();
        UUID serviceTaskId = UUID.randomUUID();
        Activity activity = serviceTaskActivity(serviceTaskId, ActivityStatus.ERROR, null);
        activity.setBpmnElementId("serviceTask");
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID token = activity.getToken();

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        when(dbService.getActivityForUpdate(serviceTaskId)).thenReturn(activity);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.createActivity(processInstanceId, token, bpmn.getFlow("flow2"))).thenReturn(UUID.randomUUID());
        when(dbService.createActivity(processInstanceId, token, bpmn.getElement("endEvent"))).thenReturn(UUID.randomUUID());

        activityService.completeServiceTask(serviceTaskId, List.of());

        verify(dbService).resolveOpenIncidents(serviceTaskId);
        verify(dbService).completeActivity(serviceTaskId);
        verify(dbService).completeServiceTask(serviceTaskId);
        verify(dbService).completeProcessInstance(processInstanceId);
    }
}
