package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProcessInstanceMapper {

    public ProcessInstance toDTO(ProcessInstanceEntity entity) {
        ProcessInstance pi = new ProcessInstance();
        pi.setId(entity.getId());
        pi.setParentActivityId(entity.getParentActivityId());
        pi.setStartedAt(entity.getStartedAt());
        pi.setCompletedAt(entity.getCompletedAt());
        pi.setProcessDefinitionId(entity.getProcessDefinitionId());
        return pi;
    }
}
