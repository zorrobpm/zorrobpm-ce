package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.dto.IdDTO;

import java.util.List;
import java.util.UUID;

public interface RuntimeService {

    IdDTO startProcessInstance(StartProcessInstanceDTO dto);

    IdDTO startProcessInstance(UUID parentProcessInstanceId, StartProcessInstanceDTO dto);

    IdDTO completeServiceTask(UUID id, List<ProcessVariable> variables);

    /**
     * Reports a failure of the service task and returns the id of its open incident.
     */
    IdDTO failServiceTask(UUID id, String message, String errorCode, String details);

    IdDTO completeUserTask(UUID id, List<ProcessVariable> variables);

    IdDTO claimUserTask(UUID id, String assignee);

    IdDTO unclaimUserTask(UUID id);

    IdDTO resolveIncident(UUID id, List<ProcessVariable> variables);
}
