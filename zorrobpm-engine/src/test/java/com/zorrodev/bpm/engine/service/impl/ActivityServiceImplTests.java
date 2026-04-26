package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    @InjectMocks
    private ActivityServiceImpl activityService;

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
        when(dbService.getActivity(serviceTaskId)).thenReturn(activity);
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
        when(dbService.getActivity(userTaskId)).thenReturn(activity);
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
}
