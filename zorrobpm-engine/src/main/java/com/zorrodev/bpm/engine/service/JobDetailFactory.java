package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Builds the job a worker gets for a service task: the same data on every transport. */
@Component
@RequiredArgsConstructor
public class JobDetailFactory {

    private final DBService dbService;
    private final BpmnService bpmnService;

    public JobDetailModel create(UUID serviceTaskId) {
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
        detail.setRetries(dbService.getServiceTaskRetryState(serviceTaskId).retries());
        detail.setVariables(variables);
        return detail;
    }
}
