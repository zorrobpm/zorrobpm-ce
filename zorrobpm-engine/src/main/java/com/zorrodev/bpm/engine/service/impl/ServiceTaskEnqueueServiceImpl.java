package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.event.data.Variable;
import com.zorrodev.bpm.event.inner.ServiceTaskEnqueued;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

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

                List<Variable> variables = dbService.getVariables(processInstanceId).stream()
                    .map(pv -> {
                        Variable v = new Variable();
                        v.setName(pv.getName());
                        v.setValue(pv.getValue());
                        v.setType(pv.getType().toString());
                        return v;
                    })
                    .toList();

                ServiceTaskEnqueued serviceTaskEnqueued = new ServiceTaskEnqueued();
                serviceTaskEnqueued.setJob(job);
                serviceTaskEnqueued.setServiceTaskId(serviceTaskId);
                serviceTaskEnqueued.setProcessDefinitionId(processDefinitionId);
                serviceTaskEnqueued.setProcessInstanceId(processInstanceId);
                serviceTaskEnqueued.setVariables(variables);
                publisher.publishEvent(serviceTaskEnqueued);
            }
        });
    }
}
