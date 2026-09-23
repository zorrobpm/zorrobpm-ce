package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.dto.BpmnErrorOutcome;
import com.zorrodev.bpm.engine.dto.FailureOutcome;
import com.zorrodev.bpm.engine.dto.RetryOverride;
import com.zorrodev.bpm.exchange.ErrorReport;

import java.util.List;
import java.util.UUID;

public interface ActivityService {

    void execute(UUID processInstanceId, UUID tokenId, String bpmnElementId);

    void completeServiceTask(UUID activityId, List<ProcessVariable> variables);

    /**
     * Reports a failure of the service task: schedules a retry while retries are left, otherwise
     * opens an incident. A repeated failure while an incident is open or a retry is pending changes nothing.
     */
    FailureOutcome failServiceTask(UUID serviceTaskId, ErrorReport error, RetryOverride override);

    /**
     * Throws a BPMN error on the service task: the nearest error boundary event catching the code, on
     * the task or up the chain of call activities, interrupts everything below it and continues the
     * process; without one, an incident opens on the service task. An open incident is returned as is.
     */
    BpmnErrorOutcome throwBpmnError(UUID serviceTaskId, String errorCode, String message, List<ProcessVariable> variables);

    void completeUserTask(UUID activityId, List<ProcessVariable> variables);

    void resolveIncident(UUID incidentId, List<ProcessVariable> variables);

    /**
     * Fires a due boundary timer; runs in its own transaction. Returns false when the timer was
     * skipped (host locked elsewhere, already handled, not due) or canceled because its host is closed.
     */
    boolean fireTimer(UUID timerId, UUID hostActivityId);

    UUID startProcessInstance(UUID parentProcessInstanceId, UUID processDefinitionId, List<ProcessVariable> variables);
}
