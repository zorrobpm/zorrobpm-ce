package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;

import java.util.List;
import java.util.UUID;

public interface ActivityService {

    void execute(UUID processInstanceId, UUID tokenId, String bpmnElementId);

    void completeServiceTask(UUID activityId, List<ProcessVariable> variables);

    UUID failServiceTask(UUID serviceTaskId, String message);

    void completeUserTask(UUID activityId, List<ProcessVariable> variables);

    void resolveIncident(UUID incidentId, List<ProcessVariable> variables);

    /**
     * Fires a due boundary timer; runs in its own transaction. Returns false when the timer was
     * skipped (host locked elsewhere, already handled, not due) or canceled because its host is closed.
     */
    boolean fireTimer(UUID timerId, UUID hostActivityId);

    UUID startProcessInstance(UUID parentProcessInstanceId, UUID processDefinitionId, List<ProcessVariable> variables);
}
