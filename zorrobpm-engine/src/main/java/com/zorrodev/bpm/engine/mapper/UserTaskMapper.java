package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateType;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.repository.UserTaskCandidateRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class UserTaskMapper {

    private final BpmnService bpmnService;
    private final UserTaskCandidateRepository userTaskCandidateRepository;

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
        dto.setAssignee(entity.getAssignee());
        dto.setLoopIndex(entity.getLoopIndex());
        dto.setLoopTotal(entity.getLoopTotal());
        dto.setLoopItem(entity.getLoopItem());

        List<UserTaskCandidateEntity> candidates = userTaskCandidateRepository.findByTaskId(entity.getId());
        dto.setCandidateGroups(candidateValues(candidates, UserTaskCandidateType.GROUP));
        dto.setCandidateUsers(candidateValues(candidates, UserTaskCandidateType.USER));
        return dto;
    }

    private static List<String> candidateValues(List<UserTaskCandidateEntity> candidates, UserTaskCandidateType type) {
        return candidates.stream()
            .filter(c -> c.getCandidateType() == type)
            .map(UserTaskCandidateEntity::getCandidateValue)
            .toList();
    }
}
