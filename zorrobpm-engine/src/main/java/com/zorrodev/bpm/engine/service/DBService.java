package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.ResolvedAssignment;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.exchange.ErrorReport;
import com.zorrodev.bpm.engine.dto.Timer;
import com.zorrodev.bpm.engine.dto.TimerSchedule;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerStatus;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DBService {

    UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables);

    UUID createActivity(UUID processInstanceId, UUID tokenId, BpmnElementModel element);

    UUID createActivity(UUID processInstanceId, UUID tokenId, BpmnElementModel element, UUID parentActivityId, Integer loopIndex, Integer loopTotal);

    UUID createActivity(UUID processInstanceId, UUID tokenId, BpmnFlowModel element);

    void completeActivity(UUID executionId);

    ProcessInstance getProcessInstance(UUID processInstanceId);

    void createServiceTask(UUID activityId);

    void createServiceTask(UUID activityId, BpmnElementModel element);

    void completeServiceTask(UUID serviceTaskId);

    void createUserTask(UUID activityId, BpmnElementModel element, ResolvedAssignment assignment);

    void createUserTask(UUID activityId, BpmnElementModel element, Integer loopIndex, Integer loopTotal, String loopItem, ResolvedAssignment assignment);

    void completeUserTask(UUID serviceTaskId);

    void claimUserTask(UUID userTaskId, String assignee);

    void cancelOpenChildUserTasks(UUID parentActivityId);

    void unclaimUserTask(UUID userTaskId);

    Activity getActivity(UUID activityId);

    Activity getActivityForUpdate(UUID activityId);

    long countCompletedChildActivities(UUID parentActivityId);

    List<ProcessVariable> getVariables(@NonNull UUID processInstanceId);

    void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables);

    List<Activity> getActivitiesByTokenAndBpmnElementId(UUID tokenId, String incoming);

    ProcessDefinition getProcessDefinition(String key, Integer version);

    Token createToken(UUID parentId);

    Token getToken(UUID tokenId);

    Integer getMaxProcessDefinitionVersionByKey(String key);

    void completeProcessInstance(UUID processInstanceId);

    UUID createIncident(UUID activityId, ErrorReport error);

    Incident getIncident(UUID incidentId);

    void resolveIncident(UUID incidentId);

    void resolveOpenIncidents(UUID activityId);

    boolean hasOpenIncident(UUID activityId);

    Optional<UUID> findOpenIncidentId(UUID activityId);

    void setActivityStatus(UUID activityId, ActivityStatus status);

    void terminateActivity(UUID activityId);

    Optional<Activity> findOpenActivity(UUID tokenId, String bpmnElementId);

    /**
     * Locks the activity row unless another transaction already holds it; empty when it is held.
     */
    Optional<Activity> findActivityForUpdateSkipLocked(UUID activityId);

    List<Activity> findOpenActivities(UUID processInstanceId);

    long countOpenActivities(UUID processInstanceId);

    /**
     * Locks the instance row; {@code completedAt} is read from the database, not from the
     * persistence context.
     */
    ProcessInstance getProcessInstanceForUpdate(UUID processInstanceId);

    /**
     * The parent (multi-instance scope) of an activity, without loading the activity itself.
     *
     * @throws java.util.NoSuchElementException when the activity does not exist
     */
    Optional<UUID> findParentActivityId(UUID activityId);

    Optional<ProcessInstance> findChildProcessInstance(UUID parentActivityId);

    boolean hasServiceTask(UUID activityId);

    void cancelUserTask(UUID userTaskId);

    void cancelServiceTask(UUID serviceTaskId);

    UUID createTimer(UUID processInstanceId, UUID activityId, String bpmnElementId, TimerSchedule schedule);

    /**
     * Cancels the timers of the host activity that have not fired yet.
     */
    void cancelTimers(UUID activityId);

    List<Timer> findDueTimers(Instant now, int limit);

    Optional<Timer> getTimerForUpdate(UUID timerId);

    void setTimerStatus(UUID timerId, TimerStatus status);

    void rescheduleTimer(UUID timerId, Instant dueAt, Integer remainingRepetitions);
}
