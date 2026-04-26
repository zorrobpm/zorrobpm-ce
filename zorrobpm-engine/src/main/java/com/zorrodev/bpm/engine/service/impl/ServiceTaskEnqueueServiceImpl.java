package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Profile("!test")
@Service
@RequiredArgsConstructor
public class ServiceTaskEnqueueServiceImpl implements ServiceTaskEnqueueService {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final ApplicationEventPublisher publisher;

    @Override
    public void enqueueAfterCommit(UUID serviceTaskId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Activity activity = dbService.getActivity(serviceTaskId);
                ProcessInstance pi = dbService.getProcessInstance(activity.getProcessInstanceId());
                UUID processDefinitionId = pi.getProcessDefinitionId();
                UUID processInstanceId = activity.getProcessInstanceId();
                String bpmnElementId = activity.getBpmnElementId();

                BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
                BpmnElementModel element = bpmn.getElement(bpmnElementId);
                String job = element.getExtensions().getServiceTaskExtension().getJob();

                Map<String, ProcessVariable> variables = dbService.getVariables(processInstanceId).stream()
                    .collect(Collectors.toMap(com.zorrodev.bpm.contract.model.ProcessVariable::getName, pv -> {
                        ProcessVariable v = new ProcessVariable();
                        v.setName(pv.getName());
                        v.setValue(pv.getValue());
                        v.setType(pv.getType().toString());
                        return v;
                    }));

                JobDetailModel detail = new JobDetailModel();
                detail.setServiceTaskId(serviceTaskId);
                detail.setProcessDefinitionId(processDefinitionId);
                detail.setProcessInstanceId(processInstanceId);
                detail.setServiceTaskKey(bpmnElementId);
                detail.setJob(job);
                detail.setVariables(variables);

                publisher.publishEvent(new ServiceTaskEnqueued(detail));
            }
        });
    }
}
