package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.service.BpmnService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserTaskMapper {

    private final BpmnService bpmnService;

    public UserTask toDTO(UserTaskEntity entity) {
        BpmnElementModel element = bpmnService.getProcessDefinitionModelById(entity.getProcessDefinitionId()).getElement(entity.getBpmnElementId());
        UserTask dto = new UserTask();
        dto.setId(entity.getId());
        dto.setName(element.getName());
        dto.setProcessInstanceId(entity.getProcessInstanceId());
        dto.setProcessDefinitionId(entity.getProcessDefinitionId());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        dto.setCode(entity.getBpmnElementId());
        dto.setFormKey(entity.getFormKey());
        return dto;
    }
}
