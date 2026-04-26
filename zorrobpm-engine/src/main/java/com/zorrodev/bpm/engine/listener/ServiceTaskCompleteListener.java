package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.event.inner.ServiceTaskCompleted;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ServiceTaskCompleteListener {

    private final RuntimeService runtimeService;

    @Transactional
    @EventListener
    public void on(ServiceTaskCompleted serviceTaskCompleted) {
        UUID serviceTaskId = serviceTaskCompleted.getServiceTaskId();
        List<ProcessVariable> variables = serviceTaskCompleted.getVariables().stream()
            .map(v -> {
                ProcessVariable pv = new ProcessVariable();
                pv.setName(v.getName());
                pv.setValue(v.getValue());
                pv.setType(ProcessVariableType.valueOf(v.getType()));
                return pv;
            })
            .toList();
        runtimeService.completeServiceTask(serviceTaskId, variables);
    }
}
