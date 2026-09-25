package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.IncidentNotFoundException;
import com.zorrodev.bpm.contract.exception.UserTaskAlreadyAssignedException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.dto.ResolvedAssignment;
import com.zorrodev.bpm.engine.dto.Timer;
import com.zorrodev.bpm.engine.dto.ServiceTaskRetryState;
import com.zorrodev.bpm.engine.dto.TimerSchedule;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.TimerEntity;
import com.zorrodev.bpm.engine.entity.TimerKind;
import com.zorrodev.bpm.engine.entity.TimerStatus;
import com.zorrodev.bpm.engine.entity.TokenEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateEntity;
import com.zorrodev.bpm.engine.entity.UserTaskCandidateType;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.mapper.IncidentMapper;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.TimerRepository;
import com.zorrodev.bpm.engine.repository.TokenRepository;
import com.zorrodev.bpm.engine.repository.UserTaskCandidateRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.exchange.ErrorReport;
import com.zorrodev.bpm.event.UserTaskInstanceCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DBServiceImpl implements DBService {

    private final ProcessDefinitionRepository processDefinitionRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final ActivityRepository activityRepository;
    private final ServiceTaskRepository serviceTaskRepository;
    private final UserTaskRepository userTaskRepository;
    private final UserTaskCandidateRepository userTaskCandidateRepository;
    private final VariableRepository variableRepository;
    private final TokenRepository tokenRepository;
    private final IncidentRepository incidentRepository;
    private final TimerRepository timerRepository;
    private final ProcessInstanceMapper processInstanceMapper;
    private final IncidentMapper incidentMapper;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    @Override
    public UUID createProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables) {
        UUID id = UUID.randomUUID();
        ProcessInstanceEntity entity = new ProcessInstanceEntity();
        entity.setId(id);
        entity.setProcessDefinitionId(processDefinitionId);
        entity.setStartedAt(Instant.now());
        entity.setParentActivityId(parentActivityId);
        processInstanceRepository.save(entity);
        List<ProcessVariableEntity> vs = new LinkedList<>();
        for (ProcessVariable variable : Optional.ofNullable(variables).orElse(List.of())) {
            ProcessVariableEntity v = new ProcessVariableEntity();
            v.setId(UUID.randomUUID());
            v.setProcessInstanceId(id);
            v.setName(variable.getName());
            v.setType(variable.getType());
            v.setTextValue(variable.getValue());
            vs.add(v);
        }
        variableRepository.saveAll(vs);
        return id;
    }

    @Override
    public UUID createActivity(UUID processInstanceId, UUID token, BpmnElementModel element) {
        return createActivity(processInstanceId, token, element, null, null, null);
    }

    @Override
    public UUID createActivity(UUID processInstanceId, UUID token, BpmnElementModel element, UUID parentActivityId, Integer loopIndex, Integer loopTotal) {
        UUID id = UUID.randomUUID();
        ActivityEntity entity = new ActivityEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setCreatedAt(Instant.now());
        entity.setStatus(ActivityStatus.CREATED);
        entity.setType(element.getType());
        entity.setBpmnElementId(element.getId());
        entity.setToken(token);
        entity.setParentActivityId(parentActivityId);
        entity.setLoopIndex(loopIndex);
        entity.setLoopTotal(loopTotal);
        activityRepository.saveAndFlush(entity);
        return id;
    }

    @Override
    public UUID createActivity(UUID processInstanceId, UUID token, BpmnFlowModel element) {
        UUID id = UUID.randomUUID();
        ActivityEntity entity = new ActivityEntity();
        entity.setId(id);
        entity.setProcessInstanceId(processInstanceId);
        entity.setCreatedAt(Instant.now());
        entity.setStatus(ActivityStatus.CREATED);
        entity.setType(BpmnElementType.SEQUENCE_FLOW);
        entity.setBpmnElementId(element.getFlowId());
        entity.setToken(token);
        activityRepository.saveAndFlush(entity);
        return id;
    }

    @Override
    public void completeActivity(UUID activityId) {
        activityRepository.setStatusAndCompletedAt(activityId, ActivityStatus.COMPLETED, Instant.now());
    }

    @Override
    public ProcessInstance getProcessInstance(UUID processInstanceId) {
        ProcessInstanceEntity entity = processInstanceRepository.findById(processInstanceId).orElseThrow();
        return processInstanceMapper.toDTO(entity);
    }

    @Override
    public void createServiceTask(UUID activityId) {
        createServiceTask(activityId, null);
    }

    @Override
    public void createServiceTask(UUID activityId, BpmnElementModel element) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        ServiceTaskEntity entity = new ServiceTaskEntity();
        entity.setId(activity.getId());
        entity.setBpmnElementId(activity.getBpmnElementId());
        entity.setProcessInstanceId(activity.getProcessInstanceId());
        entity.setCreatedAt(activity.getCreatedAt());

        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        entity.setProcessDefinitionId(pi.getProcessDefinitionId());

        entity.setJobType(Optional.ofNullable(element)
            .map(BpmnElementModel::getExtensions)
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ServiceTaskExtensionModel::getJob)
            .orElse(null));
        entity.setRetries(Optional.ofNullable(element)
            .map(BpmnElementModel::getExtensions)
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .map(ServiceTaskExtensionModel::getRetries)
            .orElse(0));

        serviceTaskRepository.save(entity);
    }

    @Override
    public void createUserTask(UUID activityId, BpmnElementModel element, ResolvedAssignment assignment) {
        createUserTask(activityId, element, null, null, null, assignment, null);
    }

    @Override
    public void createUserTask(UUID activityId, BpmnElementModel element, ResolvedAssignment assignment, String inputs) {
        createUserTask(activityId, element, null, null, null, assignment, inputs);
    }

    @Override
    public void createUserTask(UUID activityId, BpmnElementModel element, Integer loopIndex, Integer loopTotal, String loopItem, ResolvedAssignment assignment) {
        createUserTask(activityId, element, loopIndex, loopTotal, loopItem, assignment, null);
    }

    @Override
    public void createUserTask(UUID activityId, BpmnElementModel element, Integer loopIndex, Integer loopTotal, String loopItem, ResolvedAssignment assignment, String inputs) {
        ActivityEntity activity = activityRepository.findById(activityId).orElseThrow();
        UserTaskEntity entity = new UserTaskEntity();
        entity.setId(activity.getId());
        entity.setBpmnElementId(activity.getBpmnElementId());
        entity.setProcessInstanceId(activity.getProcessInstanceId());
        entity.setCreatedAt(activity.getCreatedAt());

        ProcessInstanceEntity pi = processInstanceRepository.findById(activity.getProcessInstanceId()).orElseThrow();
        entity.setProcessDefinitionId(pi.getProcessDefinitionId());

        UserTaskExtensionModel extension = Optional.ofNullable(element)
            .map(BpmnElementModel::getExtensions)
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .orElse(null);
        if (extension != null) {
            entity.setFormKey(extension.getFormKey());
        }
        if (assignment == null) {
            assignment = ResolvedAssignment.EMPTY;
        }
        entity.setAssignee(assignment.assignee());
        entity.setLoopIndex(loopIndex);
        entity.setLoopTotal(loopTotal);
        entity.setLoopItem(loopItem);
        entity.setInputs(inputs);

        userTaskRepository.save(entity);

        List<UserTaskCandidateEntity> candidates = new LinkedList<>();
        for (String value : assignment.candidateGroups()) {
            candidates.add(newCandidate(entity.getId(), UserTaskCandidateType.GROUP, value));
        }
        for (String value : assignment.candidateUsers()) {
            candidates.add(newCandidate(entity.getId(), UserTaskCandidateType.USER, value));
        }
        if (!candidates.isEmpty()) {
            userTaskCandidateRepository.saveAll(candidates);
        }

        publishUserTaskInstanceCreatedEvent(entity, element);
    }

    private void publishUserTaskInstanceCreatedEvent(UserTaskEntity entity, BpmnElementModel element) {
        UserTaskInstanceCreatedEvent event = new UserTaskInstanceCreatedEvent();
        event.setId(entity.getId());
        event.setType("UserTaskInstanceCreatedEvent");
        event.setBpmnElementId(entity.getBpmnElementId());
        event.setName(Optional.ofNullable(element).map(BpmnElementModel::getName).orElse(null));
        event.setFormKey(entity.getFormKey());
        event.setCreatedAt(entity.getCreatedAt());
        event.setProcessInstanceId(entity.getProcessInstanceId());
        event.setProcessDefinitionId(entity.getProcessDefinitionId());
        processDefinitionRepository.findById(entity.getProcessDefinitionId()).ifPresent(pd -> {
            event.setProcessDefinitionKey(pd.getKey());
            event.setProcessDefinitionVersion(pd.getVersion());
        });
        publisher.publishEvent(event);
    }

    private static UserTaskCandidateEntity newCandidate(UUID taskId, UserTaskCandidateType type, String value) {
        UserTaskCandidateEntity candidate = new UserTaskCandidateEntity();
        candidate.setId(UUID.randomUUID());
        candidate.setTaskId(taskId);
        candidate.setCandidateType(type);
        candidate.setCandidateValue(value);
        return candidate;
    }

    @Override
    public void completeServiceTask(UUID serviceTaskId) {
        serviceTaskRepository.setCompletedAt(serviceTaskId, Instant.now());
    }

    @Override
    public void completeUserTask(UUID userTaskId) {
        userTaskRepository.setCompletedAt(userTaskId, Instant.now());
    }

    @Override
    public void claimUserTask(UUID userTaskId, String assignee) {
        UserTaskEntity entity = userTaskRepository.findById(userTaskId).orElseThrow();
        if (entity.getCompletedAt() != null) {
            throw new UserTaskAlreadyAssignedException("User task " + userTaskId + " is already completed");
        }
        if (entity.getCanceledAt() != null) {
            throw new UserTaskAlreadyAssignedException("User task " + userTaskId + " is canceled");
        }
        if (entity.getAssignee() != null) {
            throw new UserTaskAlreadyAssignedException("User task " + userTaskId + " is already assigned to " + entity.getAssignee());
        }
        entity.setAssignee(assignee);
        userTaskRepository.save(entity);
    }

    @Override
    public void cancelOpenChildUserTasks(UUID parentActivityId) {
        Instant now = Instant.now();
        for (ActivityEntity child : activityRepository.findByParentActivityIdAndStatus(parentActivityId, ActivityStatus.CREATED)) {
            activityRepository.setStatusAndCompletedAt(child.getId(), ActivityStatus.TERMINATED, now);
            userTaskRepository.setCanceledAt(child.getId(), now);
        }
    }

    @Override
    public void unclaimUserTask(UUID userTaskId) {
        UserTaskEntity entity = userTaskRepository.findById(userTaskId).orElseThrow();
        entity.setAssignee(null);
        userTaskRepository.save(entity);
    }

    @Override
    public Activity getActivity(UUID activityId) {
        ActivityEntity activityEntity = activityRepository.findById(activityId).orElseThrow();
        return getActivity(activityEntity);
    }

    @Override
    public Activity getActivityForUpdate(UUID activityId) {
        ActivityEntity activityEntity = activityRepository.findByIdForUpdate(activityId).orElseThrow();
        return getActivity(activityEntity);
    }

    @Override
    public long countCompletedChildActivities(UUID parentActivityId) {
        return activityRepository.countByParentActivityIdAndStatus(parentActivityId, ActivityStatus.COMPLETED);
    }

    @Override
    public List<ProcessVariable> getVariables(@NonNull UUID processInstanceId) {
        List<ProcessVariableEntity> variables = variableRepository.findByProcessInstanceId(processInstanceId);
        return variables.stream()
            .map(variable -> {
                ProcessVariable result = new ProcessVariable();
                result.setName(variable.getName());
                result.setType(variable.getType());
                result.setValue(variable.getTextValue());
                return result;
            })
            .toList();
    }

    @Override
    public void setVariables(@NonNull UUID processInstanceId, List<ProcessVariable> variables) {
        List<ProcessVariableEntity> entities = new ArrayList<>();
        for (ProcessVariable variable : variables) {
            ProcessVariableEntity entity = variableRepository
                .findByNameAndProcessInstanceId(variable.getName(), processInstanceId)
                .orElseGet(() -> {
                    ProcessVariableEntity e = new ProcessVariableEntity();
                    e.setId(UUID.randomUUID());
                    e.setProcessInstanceId(processInstanceId);
                    e.setName(variable.getName());
                    return e;
                });
            entity.setType(variable.getType());
            entity.setTextValue(variable.getValue());
            entities.add(entity);
        }
        variableRepository.saveAll(entities);
    }

    @Override
    public List<Activity> getActivitiesByTokenAndBpmnElementId(UUID token, String bpmnElementId) {
        return activityRepository.findByTokenAndBpmnElementId(token, bpmnElementId).stream()
            .map(this::getActivity)
            .toList();
    }

    @Override
    public ProcessDefinition getProcessDefinition(String key, Integer version) {
        ProcessDefinitionEntity entity = processDefinitionRepository.findByKeyAndVersion(key, version).orElseThrow();
        ProcessDefinition result = new ProcessDefinition();
        result.setId(entity.getId());
        result.setName(entity.getName());
        result.setKey(entity.getKey());
        result.setSha256(entity.getSha256());
        result.setCreatedAt(entity.getCreatedAt());
        result.setStartFormKey(entity.getStartFormKey());
        result.setVersion(entity.getVersion());
        return result;
    }

    @Override
    public Token createToken(UUID parentId) {
        TokenEntity tokenEntity = new TokenEntity();
        tokenEntity.setId(UUID.randomUUID());
        tokenEntity.setParentId(parentId);
        tokenRepository.save(tokenEntity);

        Token token = new Token();
        token.setId(tokenEntity.getId());
        token.setParentId(tokenEntity.getParentId());
        return token;
    }

    @Override
    public Token getToken(UUID tokenId) {
        return tokenRepository.findById(tokenId)
            .map(t -> {
                Token token = new Token();
                token.setId(t.getId());
                token.setParentId(t.getParentId());
                return token;
            })
            .orElseThrow();
    }

    @Override
    public Integer getMaxProcessDefinitionVersionByKey(String key) {
        return processDefinitionRepository.findMaxByKey(key).orElse(0);
    }

    @Override
    public void completeProcessInstance(UUID processInstanceId) {
        processInstanceRepository.setCompletedAt(processInstanceId, Instant.now());
    }

    @Override
    public UUID createIncident(UUID activityId, ErrorReport error) {
        ActivityEntity activityEntity = activityRepository.findById(activityId).orElseThrow();
        UUID id = UUID.randomUUID();
        ErrorReport stored = ErrorReport.truncate(error);

        IncidentEntity entity = new IncidentEntity();
        entity.setId(id);
        entity.setActivityId(activityId);
        entity.setCreatedAt(Instant.now());
        entity.setMessage(stored.getMessage());
        entity.setErrorCode(stored.getErrorCode());
        entity.setDetails(stored.getDetails());
        incidentRepository.save(entity);

        return id;
    }

    @Override
    public Incident getIncident(UUID incidentId) {
        return incidentRepository.findById(incidentId)
            .map(incidentMapper::toDTO)
            .orElseThrow(() -> new IncidentNotFoundException("Incident " + incidentId + " not found"));
    }

    @Override
    public void resolveIncident(UUID incidentId) {
        IncidentEntity entity = incidentRepository.findById(incidentId)
            .orElseThrow(() -> new IncidentNotFoundException("Incident " + incidentId + " not found"));
        entity.setCompletedAt(Instant.now());
        incidentRepository.save(entity);
    }

    @Override
    public void resolveOpenIncidents(UUID activityId) {
        Instant now = Instant.now();
        List<IncidentEntity> incidents = incidentRepository.findByActivityIdAndCompletedAtIsNull(activityId);
        incidents.forEach(incident -> incident.setCompletedAt(now));
        incidentRepository.saveAll(incidents);
    }

    @Override
    public boolean hasOpenIncident(UUID activityId) {
        return incidentRepository.existsByActivityIdAndCompletedAtIsNull(activityId);
    }

    @Override
    public Optional<UUID> findOpenIncidentId(UUID activityId) {
        return incidentRepository.findByActivityIdAndCompletedAtIsNull(activityId).stream()
            .map(IncidentEntity::getId)
            .findFirst();
    }

    @Override
    public void setActivityStatus(UUID activityId, ActivityStatus status) {
        activityRepository.setStatus(activityId, status);
    }

    @Override
    public void terminateActivity(UUID activityId) {
        activityRepository.setStatusAndCompletedAt(activityId, ActivityStatus.TERMINATED, Instant.now());
    }

    @Override
    public Optional<Activity> findOpenActivity(UUID tokenId, String bpmnElementId) {
        return activityRepository.findFirstByTokenAndBpmnElementIdAndParentActivityIdIsNullAndCompletedAtIsNullOrderByCreatedAtDesc(tokenId, bpmnElementId)
            .map(this::getActivity);
    }

    @Override
    public Optional<Activity> findActivityForUpdateSkipLocked(UUID activityId) {
        return activityRepository.findByIdForUpdateSkipLocked(activityId).map(this::getActivity);
    }

    @Override
    public List<Activity> findOpenActivities(UUID processInstanceId) {
        return activityRepository.findByProcessInstanceIdAndCompletedAtIsNull(processInstanceId).stream()
            .map(this::getActivity)
            .toList();
    }

    @Override
    public long countOpenActivities(UUID processInstanceId) {
        return activityRepository.countByProcessInstanceIdAndCompletedAtIsNull(processInstanceId);
    }

    @Override
    public ProcessInstance getProcessInstanceForUpdate(UUID processInstanceId) {
        ProcessInstance instance = processInstanceMapper.toDTO(processInstanceRepository.findByIdForUpdate(processInstanceId).orElseThrow());
        instance.setCompletedAt(processInstanceRepository.findCompletedAt(processInstanceId).stream().findFirst().orElse(null));
        return instance;
    }

    @Override
    public Optional<UUID> findParentActivityId(UUID activityId) {
        List<UUID> parent = activityRepository.findParentActivityId(activityId);
        if (parent.isEmpty()) {
            throw new NoSuchElementException("Activity " + activityId + " not found");
        }
        return Optional.ofNullable(parent.get(0));
    }

    @Override
    public Optional<ProcessInstance> findChildProcessInstance(UUID parentActivityId) {
        return processInstanceRepository.findFirstByParentActivityId(parentActivityId).map(processInstanceMapper::toDTO);
    }

    @Override
    public boolean hasServiceTask(UUID activityId) {
        return serviceTaskRepository.existsById(activityId);
    }

    @Override
    public void cancelUserTask(UUID userTaskId) {
        userTaskRepository.setCanceledAt(userTaskId, Instant.now());
    }

    @Override
    public void cancelServiceTask(UUID serviceTaskId) {
        serviceTaskRepository.setCanceledAt(serviceTaskId, Instant.now());
    }

    @Override
    public UUID createTimer(UUID processInstanceId, UUID activityId, String bpmnElementId, TimerSchedule schedule) {
        return createTimer(processInstanceId, activityId, bpmnElementId, TimerKind.BOUNDARY, schedule);
    }

    @Override
    public UUID createTimer(UUID processInstanceId, UUID activityId, String bpmnElementId, TimerKind kind, TimerSchedule schedule) {
        TimerEntity entity = new TimerEntity();
        entity.setId(UUID.randomUUID());
        entity.setKind(kind);
        entity.setProcessInstanceId(processInstanceId);
        entity.setActivityId(activityId);
        entity.setBpmnElementId(bpmnElementId);
        entity.setDueAt(schedule.dueAt());
        entity.setStatus(TimerStatus.SCHEDULED);
        entity.setCycleInterval(schedule.cycleInterval());
        entity.setRemainingRepetitions(schedule.remainingRepetitions());
        entity.setCreatedAt(clock.instant());
        timerRepository.save(entity);
        return entity.getId();
    }

    @Override
    public ServiceTaskRetryState getServiceTaskRetryState(UUID serviceTaskId) {
        ServiceTaskEntity entity = serviceTaskRepository.findById(serviceTaskId).orElseThrow();
        return new ServiceTaskRetryState(entity.getRetries(), entity.getNextRetryAt());
    }

    @Override
    public void scheduleServiceTaskRetry(UUID serviceTaskId, int retries, ErrorReport error, Instant dueAt) {
        ServiceTaskEntity entity = serviceTaskRepository.findById(serviceTaskId).orElseThrow();
        ErrorReport stored = ErrorReport.truncate(error);
        entity.setRetries(retries);
        entity.setNextRetryAt(dueAt);
        entity.setLastErrorCode(stored.getErrorCode());
        entity.setLastErrorMessage(stored.getMessage());
        serviceTaskRepository.save(entity);
        createTimer(entity.getProcessInstanceId(), serviceTaskId, entity.getBpmnElementId(), TimerKind.RETRY, TimerSchedule.once(dueAt));
    }

    @Override
    public void clearNextRetryAt(UUID serviceTaskId) {
        ServiceTaskEntity entity = serviceTaskRepository.findById(serviceTaskId).orElseThrow();
        entity.setNextRetryAt(null);
        // The job is handed out afresh: a lock left from the failed attempt must not delay it.
        entity.setLockedUntil(null);
        entity.setLockedBy(null);
        serviceTaskRepository.save(entity);
    }

    @Override
    public void setServiceTaskRetries(UUID serviceTaskId, int retries) {
        ServiceTaskEntity entity = serviceTaskRepository.findById(serviceTaskId).orElseThrow();
        entity.setRetries(retries);
        // Called when an incident is resolved: the job is handed out afresh.
        entity.setLockedUntil(null);
        entity.setLockedBy(null);
        serviceTaskRepository.save(entity);
    }

    @Override
    public List<UUID> findReadyServiceTaskJobs(Collection<String> jobs, int limit) {
        if (jobs.isEmpty() || limit <= 0) {
            return List.of();
        }
        return serviceTaskRepository.findReadyJobs(List.copyOf(jobs), clock.instant(), Limit.of(limit));
    }

    @Override
    public boolean lockServiceTaskJob(UUID serviceTaskId, String owner, Instant until) {
        return serviceTaskRepository.tryLock(serviceTaskId, owner, clock.instant(), until) == 1;
    }

    @Override
    public void releaseServiceTaskLock(UUID serviceTaskId) {
        serviceTaskRepository.releaseLock(serviceTaskId);
    }

    @Override
    public void releaseServiceTaskLock(UUID serviceTaskId, String owner) {
        serviceTaskRepository.releaseLock(serviceTaskId, owner);
    }

    @Override
    public int releaseServiceTaskLocks(String owner) {
        return serviceTaskRepository.releaseLocks(owner);
    }

    @Override
    public void cancelTimers(UUID activityId) {
        timerRepository.cancelScheduled(activityId, clock.instant());
    }

    @Override
    public List<Timer> findDueTimers(Instant now, int limit) {
        return timerRepository.findDue(now, PageRequest.of(0, limit)).stream()
            .map(DBServiceImpl::toTimer)
            .toList();
    }

    @Override
    public Optional<Timer> getTimerForUpdate(UUID timerId) {
        return timerRepository.findByIdForUpdate(timerId).map(DBServiceImpl::toTimer);
    }

    @Override
    public void setTimerStatus(UUID timerId, TimerStatus status) {
        TimerEntity entity = timerRepository.findById(timerId).orElseThrow();
        entity.setStatus(status);
        if (status != TimerStatus.SCHEDULED) {
            entity.setCompletedAt(clock.instant());
        }
        timerRepository.save(entity);
    }

    @Override
    public void rescheduleTimer(UUID timerId, Instant dueAt, Integer remainingRepetitions) {
        TimerEntity entity = timerRepository.findById(timerId).orElseThrow();
        entity.setDueAt(dueAt);
        entity.setRemainingRepetitions(remainingRepetitions);
        timerRepository.save(entity);
    }

    private static Timer toTimer(TimerEntity entity) {
        Timer timer = new Timer();
        timer.setId(entity.getId());
        timer.setProcessInstanceId(entity.getProcessInstanceId());
        timer.setKind(entity.getKind());
        timer.setActivityId(entity.getActivityId());
        timer.setBpmnElementId(entity.getBpmnElementId());
        timer.setDueAt(entity.getDueAt());
        timer.setStatus(entity.getStatus());
        timer.setCycleInterval(entity.getCycleInterval());
        timer.setRemainingRepetitions(entity.getRemainingRepetitions());
        return timer;
    }

    private Activity getActivity(ActivityEntity activityEntity) {
        Activity activity = new Activity();
        activity.setId(activityEntity.getId());
        activity.setProcessInstanceId(activityEntity.getProcessInstanceId());
        activity.setBpmnElementId(activityEntity.getBpmnElementId());
        activity.setCreatedAt(activityEntity.getCreatedAt());
        activity.setCompletedAt(activityEntity.getCompletedAt());
        activity.setStatus(activityEntity.getStatus());
        activity.setType(activityEntity.getType());
        activity.setToken(activityEntity.getToken());
        activity.setParentActivityId(activityEntity.getParentActivityId());
        activity.setLoopIndex(activityEntity.getLoopIndex());
        activity.setLoopTotal(activityEntity.getLoopTotal());
        return activity;
    }
}
