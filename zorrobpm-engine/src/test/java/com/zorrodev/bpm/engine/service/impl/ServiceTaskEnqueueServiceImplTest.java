package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceTaskEnqueueServiceImplTest {

    @Mock private DBService dbService;
    @Mock private BpmnService bpmnService;
    @Mock private ApplicationEventPublisher publisher;

    @InjectMocks
    private ServiceTaskEnqueueServiceImpl service;

    @BeforeEach
    void initSync() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void enqueueAfterCommit_buildsJobDetailAndPublishesEvent() {
        UUID serviceTaskId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        String bpmnElementId = "serviceTask1";

        Activity activity = new Activity();
        activity.setId(serviceTaskId);
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId(bpmnElementId);
        activity.setType(BpmnElementType.SERVICE_TASK);

        ProcessInstance pi = new ProcessInstance();
        pi.setId(processInstanceId);
        pi.setProcessDefinitionId(processDefinitionId);

        ServiceTaskExtensionModel ext = new ServiceTaskExtensionModel();
        ext.setJob("send-email");
        BpmnElementExtensionModel extensions = new BpmnElementExtensionModel();
        extensions.setServiceTaskExtension(ext);

        BpmnElementModel element = new BpmnElementModel();
        element.setId(bpmnElementId);
        element.setType(BpmnElementType.SERVICE_TASK);
        element.setExtensions(extensions);

        BpmnProcessDefinitionModel bpmn = new BpmnProcessDefinitionModel();
        bpmn.addElement(element);

        ProcessVariable v1 = newVar("name", "Alice", ProcessVariableType.STRING);
        ProcessVariable v2 = newVar("age", "30", ProcessVariableType.LONG);

        when(dbService.getActivity(serviceTaskId)).thenReturn(activity);
        when(dbService.getProcessInstance(processInstanceId)).thenReturn(pi);
        when(bpmnService.getProcessDefinitionModelById(processDefinitionId)).thenReturn(bpmn);
        when(dbService.getVariables(processInstanceId)).thenReturn(List.of(v1, v2));

        service.enqueueAfterCommit(serviceTaskId);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<ServiceTaskEnqueued> captor = ArgumentCaptor.forClass(ServiceTaskEnqueued.class);
        org.mockito.Mockito.verify(publisher).publishEvent(captor.capture());

        JobDetailModel detail = captor.getValue().getDetail();
        assertThat(detail.getServiceTaskId()).isEqualTo(serviceTaskId);
        assertThat(detail.getProcessInstanceId()).isEqualTo(processInstanceId);
        assertThat(detail.getProcessDefinitionId()).isEqualTo(processDefinitionId);
        assertThat(detail.getServiceTaskKey()).isEqualTo(bpmnElementId);
        assertThat(detail.getJob()).isEqualTo("send-email");
        assertThat(detail.getVariables()).hasSize(2);
        assertThat(detail.getVariables().get("name").getValue()).isEqualTo("Alice");
        assertThat(detail.getVariables().get("name").getType()).isEqualTo(ProcessVariableType.STRING.toString());
        assertThat(detail.getVariables().get("age").getValue()).isEqualTo("30");
        assertThat(detail.getVariables().get("age").getType()).isEqualTo(ProcessVariableType.LONG.toString());
    }

    @Test
    void enqueueAfterCommit_doesNotPublishUntilAfterCommit() {
        UUID serviceTaskId = UUID.randomUUID();

        service.enqueueAfterCommit(serviceTaskId);

        org.mockito.Mockito.verifyNoInteractions(publisher);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    private static ProcessVariable newVar(String name, String value, ProcessVariableType type) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(type);
        return v;
    }
}
