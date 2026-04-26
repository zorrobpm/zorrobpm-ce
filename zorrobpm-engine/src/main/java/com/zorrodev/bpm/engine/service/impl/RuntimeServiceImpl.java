package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeServiceImpl implements RuntimeService {

    private final DBService dbService;
    private final ActivityService activityService;
    private final BpmnService bpmnService;

    @Override
    public IdDTO startProcessInstance(StartProcessInstanceDTO dto) {
        return startProcessInstance(null, dto);
    }

    @Override
    public IdDTO startProcessInstance(UUID parentProcessInstanceId, StartProcessInstanceDTO dto) {
        UUID processDefinitionId = dto.getProcessDefinitionId();
        if (processDefinitionId == null) {
            String key = dto.getProcessDefinitionKey();
            Integer version = dto.getProcessDefinitionVersion();
            if (version == null) {
                version = dbService.getMaxProcessDefinitionVersionByKey(key);
            }
            ProcessDefinition pd = dbService.getProcessDefinition(key, version);
            processDefinitionId = pd.getId();
        }
        List<ProcessVariable> variables = dto.getVariables();

        UUID processInstanceId = activityService.startProcessInstance(parentProcessInstanceId, processDefinitionId, variables);

        IdDTO result = new IdDTO();
        result.setId(processInstanceId);
        return result;
    }

    @Override
    public IdDTO completeServiceTask(UUID id, List<ProcessVariable> variables) {
        activityService.completeServiceTask(id, variables);
        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }

    @Override
    public IdDTO completeUserTask(UUID id, List<ProcessVariable> variables) {
        activityService.completeUserTask(id, variables);
        IdDTO result = new IdDTO();
        result.setId(id);
        return result;
    }

    @Override
    public IdDTO resolveIncident(UUID id, List<ProcessVariable> variables) {
        return null;
    }
}
