package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.exception.IncidentAlreadyResolvedException;
import com.zorrodev.bpm.contract.exception.ServiceTaskNotFoundException;
import com.zorrodev.bpm.contract.exception.TaskNotActiveException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnConditionExpressionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ExclusiveGatewayExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.dto.ResolvedAssignment;
import com.zorrodev.bpm.engine.dto.Timer;
import com.zorrodev.bpm.engine.dto.TimerSchedule;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.TimerStatus;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.engine.service.TimerExpressionService;
import com.zorrodev.bpm.exchange.ErrorReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final ScriptService scriptService;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;
    private final TimerExpressionService timerExpressionService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * A boundary timer computed on entering its host, before anything is written.
     */
    private record PendingTimer(BpmnElementModel boundaryEvent, TimerSchedule schedule) {
    }

    @Override
    public void execute(UUID processInstanceId, UUID tokenId, String bpmnElementId) {
        ProcessInstance processInstanceEntity = dbService.getProcessInstance(processInstanceId);
        UUID processDefinitionId = processInstanceEntity.getProcessDefinitionId();
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        BpmnElementModel element = bpmn.getElement(bpmnElementId);

        execute(processInstanceId, tokenId, bpmn, element);
    }

    /**
     * Executes one element. An execution error of the element (expression, assignment, missing
     * model data) becomes an incident on it and stops this token there; the rest of the command
     * is kept. Database errors and contract errors of the command itself are rethrown.
     */
    private void execute(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element) {
        try {
            dispatch(processInstanceId, tokenId, bpmn, element);
        } catch (DataAccessException | EngineException | ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            // Exception, not RuntimeException: script evaluation rethrows ScriptException via @SneakyThrows
            raiseIncident(processInstanceId, tokenId, element, e);
        }
    }

    private void raiseIncident(UUID processInstanceId, UUID tokenId, BpmnElementModel element, Exception e) {
        UUID activityId = dbService.findOpenActivity(tokenId, element.getId())
            .map(Activity::getId)
            .orElseGet(() -> dbService.createActivity(processInstanceId, tokenId, element));
        dbService.cancelOpenChildUserTasks(activityId);
        dbService.setActivityStatus(activityId, ActivityStatus.ERROR);
        UUID incidentId = dbService.createIncident(activityId, ErrorReport.of(e, null));

        log.warn("{}/{}: Incident {} on {}: {}/{}", processInstanceId, tokenId, incidentId, element.getType(), activityId, element.getId(), e);
    }

    private void dispatch(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element) {
        BpmnElementType type = element.getType();

        if (type == BpmnElementType.START_EVENT) {
            processStartEvent(processInstanceId, tokenId, bpmn, element);
        } else if (type == BpmnElementType.END_EVENT) {
            processEndEvent(processInstanceId, tokenId, element);
        } else if (type == BpmnElementType.SERVICE_TASK) {
            enterServiceTask(processInstanceId, tokenId, bpmn, element);
        } else if (type == BpmnElementType.USER_TASK) {
            enterUserTask(processInstanceId, tokenId, bpmn, element);
        } else if (type == BpmnElementType.EXCLUSIVE_GATEWAY) {
            processExclusiveGateway(processInstanceId, tokenId, bpmn, element);
        } else if (type == BpmnElementType.PARALLEL_GATEWAY) {
            processParallelGateway(processInstanceId, tokenId, bpmn, element);
        } else if (type == BpmnElementType.CALL_ACTIVITY) {
            processCallActivity(processInstanceId, tokenId, bpmn, element);
        } else {
            log.info("Unsupported BpmnElementType: " + type);
        }
    }

    private void advance(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        List<String> outgoings = bpmnElement.getOutgoing();
        for (String outgoing : outgoings) {
            processFlow(processInstanceId, tokenId, outgoing, false, null);
            BpmnFlowModel flow = bpmn.getFlow(outgoing);
            String targetRef = flow.getTargetRef();
            BpmnElementModel target = bpmn.getElement(targetRef);
            execute(processInstanceId, tokenId, bpmn, target);
        }
    }

    /**
     * Computes the boundary timers of a host element. Pure: a failure leaves nothing written, so
     * the incident is raised on an empty host activity.
     */
    private List<PendingTimer> scheduleBoundaryTimers(UUID processInstanceId, BpmnProcessDefinitionModel bpmn, BpmnElementModel host) {
        List<BpmnElementModel> boundaryEvents = bpmn.getBoundaryEvents(host.getId());
        if (boundaryEvents.isEmpty()) {
            return List.of();
        }
        List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
        Instant now = clock.instant();
        return boundaryEvents.stream()
            .map(boundaryEvent -> new PendingTimer(boundaryEvent, timerExpressionService.schedule(boundaryEvent, variables, now)))
            .toList();
    }

    /**
     * Written right after the host activity and before anything that may complete the host in the
     * same call (a child instance that ends at once, an empty multi-instance collection), so that
     * the completion sees and cancels them.
     */
    private void armBoundaryTimers(UUID processInstanceId, UUID hostActivityId, List<PendingTimer> timers) {
        for (PendingTimer timer : timers) {
            UUID timerId = dbService.createTimer(processInstanceId, hostActivityId, timer.boundaryEvent().getId(), timer.schedule());
            log.info("{}: Armed timer {} of {} on {} due at {}", processInstanceId, timerId, timer.boundaryEvent().getId(), hostActivityId, timer.schedule().dueAt());
        }
    }

    private void processCallActivity(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        List<PendingTimer> timers = scheduleBoundaryTimers(processInstanceId, bpmn, bpmnElement);
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        armBoundaryTimers(processInstanceId, activityId, timers);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

        List<ProcessVariable> variables = dbService.getVariables(processInstanceId);

        String key = bpmnElement.getExtensions().getCallActivityExtension().getProcessId();
        Integer version = dbService.getMaxProcessDefinitionVersionByKey(key);
        ProcessDefinition pd = dbService.getProcessDefinition(key, version);
        UUID processDefinitionId = pd.getId();

        startProcessInstance(activityId, processDefinitionId, variables);
    }

    private void processParallelGateway(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        List<String> incomings = bpmnElement.getIncoming();

        if (incomings.size() == 1) {
            UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
            dbService.completeActivity(activityId);
            log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

            Token newToken = dbService.createToken(tokenId);
            UUID newTokenId = newToken.getId();
            advance(processInstanceId, newTokenId, bpmn, bpmnElement);
        } else if (incomings.size() > 1) {
            boolean reached = true;

            for (String incoming : incomings) {
                List<Activity> activities = dbService.getActivitiesByTokenAndBpmnElementId(tokenId, incoming);
                if (activities.isEmpty()) {
                    reached = false;
                    break;
                }
            }

            if (reached) {
                Token token = dbService.getToken(tokenId);
                UUID oldTokenId = token.getParentId();
                UUID activityId = dbService.createActivity(processInstanceId, oldTokenId, bpmnElement);
                dbService.completeActivity(activityId);
                log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());
                advance(processInstanceId, oldTokenId, bpmn, bpmnElement);
            } else {
                log.info("{}/{}: Parallel Gateway Not ready yet {}: {}", processInstanceId, tokenId, bpmnElement.getType(), bpmnElement.getId());
            }
        }
    }

    private void processExclusiveGateway(UUID processInstanceId, UUID token, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);

        log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        List<String> outgoings = bpmnElement.getOutgoing();
        List<String> incoming = bpmnElement.getIncoming();

        if (outgoings.size() > 1 && incoming.size() == 1) {
            String matchedOutgoing = null;
            for (String outgoing : outgoings) {
                Boolean defaultFlow = Objects.equals(outgoing, Optional.ofNullable(bpmnElement).map(BpmnElementModel::getExtensions).map(BpmnElementExtensionModel::getExclusiveGatewayExtension).map(ExclusiveGatewayExtensionModel::getDefaultFlowId).orElse(null));
                UUID flowActivityId = processFlow(processInstanceId, token, outgoing, true, defaultFlow);
                if (flowActivityId != null) {
                    matchedOutgoing = outgoing;
                    break;
                }
            }

            if (matchedOutgoing != null) {
                BpmnFlowModel flow = bpmn.getFlow(matchedOutgoing);
                String targetRef = flow.getTargetRef();
                BpmnElementModel target = bpmn.getElement(targetRef);
                // Completed only once a flow is chosen: a failing condition leaves it open for the incident.
                dbService.completeActivity(activityId);
                execute(processInstanceId, token, bpmn, target);
            } else {
                String outgoing = bpmnElement.getExtensions().getExclusiveGatewayExtension().getDefaultFlowId();
                processFlow(processInstanceId, token, outgoing, false, null);
                BpmnFlowModel flow = bpmn.getFlow(outgoing);
                String targetRef = flow.getTargetRef();
                BpmnElementModel target = bpmn.getElement(targetRef);
                dbService.completeActivity(activityId);
                execute(processInstanceId, token, bpmn, target);
            }
        } else if (outgoings.size() == 1 && incoming.size() > 1) {
            String outgoing = outgoings.get(0);
            dbService.completeActivity(activityId);
            processFlow(processInstanceId, token, outgoing, false, null);
            BpmnFlowModel flow = bpmn.getFlow(outgoing);
            String targetRef = flow.getTargetRef();
            BpmnElementModel target = bpmn.getElement(targetRef);
            execute(processInstanceId, token, bpmn, target);
        }
    }

    private void enterServiceTask(UUID processInstanceId, UUID token, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        List<PendingTimer> timers = scheduleBoundaryTimers(processInstanceId, bpmn, bpmnElement);
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);
        armBoundaryTimers(processInstanceId, activityId, timers);
        dbService.createServiceTask(activityId, bpmnElement);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        serviceTaskEnqueueService.enqueueAfterCommit(activityId);
    }

    @Override
    public void completeServiceTask(UUID serviceTaskId, List<ProcessVariable> variables) {
        // The row lock serializes the completion with a boundary timer firing on this task.
        Activity activity = dbService.getActivityForUpdate(serviceTaskId);
        if (activity.getCompletedAt() != null) {
            throw new TaskNotActiveException("Service task " + serviceTaskId + " is not active: " + activity.getStatus());
        }
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID tokenId = activity.getToken();

        dbService.setVariables(processInstanceId, variables);
        dbService.resolveOpenIncidents(serviceTaskId);
        dbService.cancelTimers(serviceTaskId);
        dbService.completeActivity(serviceTaskId);
        dbService.completeServiceTask(serviceTaskId);

        log.info("{}/{}: Completing {}: {}/{}", processInstanceId, tokenId, activity.getType(), serviceTaskId, activity.getBpmnElementId());

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        advance(processInstanceId, tokenId, bpmn, bpmnElement);
    }

    @Override
    public UUID failServiceTask(UUID serviceTaskId, ErrorReport error) {
        if (!dbService.hasServiceTask(serviceTaskId)) {
            throw new ServiceTaskNotFoundException("Service task " + serviceTaskId + " not found");
        }
        // The row lock serializes the failure with a completion or a boundary timer firing on this task.
        Activity activity = dbService.getActivityForUpdate(serviceTaskId);
        if (activity.getCompletedAt() != null) {
            throw new TaskNotActiveException("Service task " + serviceTaskId + " is not active: " + activity.getStatus());
        }

        Optional<UUID> openIncidentId = dbService.findOpenIncidentId(serviceTaskId);
        if (openIncidentId.isPresent()) {
            log.info("{}/{}: Ignoring repeated failure of {} {}/{}: incident {} is open", activity.getProcessInstanceId(), activity.getToken(), activity.getType(), serviceTaskId, activity.getBpmnElementId(), openIncidentId.get());
            return openIncidentId.get();
        }

        UUID incidentId = dbService.createIncident(serviceTaskId, error);
        dbService.setActivityStatus(serviceTaskId, ActivityStatus.ERROR);

        log.warn("{}/{}: Incident {} on {}: {}/{}: {} ({})", activity.getProcessInstanceId(), activity.getToken(), incidentId, activity.getType(), serviceTaskId, activity.getBpmnElementId(), error.getMessage(), error.getErrorCode());
        return incidentId;
    }

    private void enterUserTask(UUID processInstanceId, UUID token, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        MultiInstanceExtensionModel multiInstance = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getMultiInstanceExtension)
            .orElse(null);
        List<PendingTimer> timers = scheduleBoundaryTimers(processInstanceId, bpmn, bpmnElement);
        if (multiInstance != null) {
            enterMultiInstanceUserTask(processInstanceId, token, bpmn, bpmnElement, multiInstance, timers);
            return;
        }

        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);
        armBoundaryTimers(processInstanceId, activityId, timers);
        ResolvedAssignment assignment = resolveAssignment(userTaskExtension(bpmnElement),
            () -> dbService.getVariables(processInstanceId));
        dbService.createUserTask(activityId, bpmnElement, assignment);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());
    }

    private void enterMultiInstanceUserTask(UUID processInstanceId, UUID token, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement, MultiInstanceExtensionModel multiInstance, List<PendingTimer> timers) {
        List<?> items = evaluateInputCollection(processInstanceId, multiInstance);
        int total = items.size();

        // Boundary timers belong to the whole element: they are armed on the scope, not on each instance.
        UUID scopeActivityId = dbService.createActivity(processInstanceId, token, bpmnElement, null, null, total);
        armBoundaryTimers(processInstanceId, scopeActivityId, timers);

        log.info("{}/{}: Entering multi-instance {}: {}/{} with {} instances", processInstanceId, token, bpmnElement.getType(), scopeActivityId, bpmnElement.getId(), total);

        if (multiInstance.getOutputCollection() != null) {
            List<Object> results = new ArrayList<>(Collections.nCopies(total, null));
            dbService.setVariables(processInstanceId, List.of(jsonVariable(multiInstance.getOutputCollection(), results)));
        }

        if (total == 0) {
            dbService.cancelTimers(scopeActivityId);
            dbService.completeActivity(scopeActivityId);
            advance(processInstanceId, token, bpmn, bpmnElement);
            return;
        }

        if (multiInstance.isSequential()) {
            createMultiInstanceChild(processInstanceId, token, scopeActivityId, bpmnElement, items.get(0), 0, total);
        } else {
            for (int i = 0; i < total; i++) {
                createMultiInstanceChild(processInstanceId, token, scopeActivityId, bpmnElement, items.get(i), i, total);
            }
        }
    }

    private void createMultiInstanceChild(UUID processInstanceId, UUID token, UUID scopeActivityId, BpmnElementModel bpmnElement, Object item, int index, int total) {
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement, scopeActivityId, index, total);
        ResolvedAssignment assignment = resolveAssignment(userTaskExtension(bpmnElement),
            () -> multiInstanceVariables(processInstanceId, bpmnElement, item, index));
        dbService.createUserTask(activityId, bpmnElement, index, total, objectMapper.writeValueAsString(item), assignment);

        log.info("{}/{}: Entering {}: {}/{} [{}/{}]", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId(), index, total);
    }

    private List<?> evaluateInputCollection(UUID processInstanceId, MultiInstanceExtensionModel multiInstance) {
        String expression = stripExpression(multiInstance.getInputCollection());
        List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
        Object result = scriptService.evaluateExpression(expression, variables);
        if (!(result instanceof List)) {
            throw new IllegalStateException("Multi-instance inputCollection '" + multiInstance.getInputCollection() + "' did not evaluate to a list");
        }
        return (List<?>) result;
    }

    private static String stripExpression(String expression) {
        if (expression == null || expression.isEmpty()) {
            throw new IllegalStateException("Multi-instance expression is not set");
        }
        return expression.startsWith("=") ? expression.substring(1) : expression;
    }

    private ProcessVariable jsonVariable(String name, Object value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(ProcessVariableType.JSON);
        variable.setValue(objectMapper.writeValueAsString(value));
        return variable;
    }

    private static UserTaskExtensionModel userTaskExtension(BpmnElementModel element) {
        return Optional.ofNullable(element.getExtensions())
            .map(BpmnElementExtensionModel::getUserTaskExtension)
            .orElse(null);
    }

    private List<ProcessVariable> multiInstanceVariables(UUID processInstanceId, BpmnElementModel bpmnElement, Object item, int index) {
        List<ProcessVariable> variables = new ArrayList<>(dbService.getVariables(processInstanceId));
        MultiInstanceExtensionModel multiInstance = bpmnElement.getExtensions().getMultiInstanceExtension();
        if (multiInstance.getInputElement() != null) {
            variables.add(jsonVariable(multiInstance.getInputElement(), item));
        }
        ProcessVariable loopCounter = new ProcessVariable();
        loopCounter.setName("loopCounter");
        loopCounter.setType(ProcessVariableType.LONG);
        loopCounter.setValue(String.valueOf(index + 1));
        variables.add(loopCounter);
        return variables;
    }

    private ResolvedAssignment resolveAssignment(UserTaskExtensionModel extension, Supplier<List<ProcessVariable>> variablesSupplier) {
        if (extension == null) {
            return ResolvedAssignment.EMPTY;
        }
        boolean hasExpression = isExpression(extension.getAssignee())
            || isExpression(extension.getCandidateUsers())
            || isExpression(extension.getCandidateGroups());
        List<ProcessVariable> variables = hasExpression ? variablesSupplier.get() : null;
        return new ResolvedAssignment(
            resolveAssignee(extension.getAssignee(), variables),
            resolveCandidates(extension.getCandidateUsers(), variables),
            resolveCandidates(extension.getCandidateGroups(), variables));
    }

    private String resolveAssignee(String raw, List<ProcessVariable> variables) {
        if (!isExpression(raw)) {
            return raw;
        }
        Object result = scriptService.evaluateExpression(stripExpression(raw), variables);
        if (result != null && !(result instanceof String)) {
            throw new IllegalStateException("Assignee expression '" + raw + "' did not evaluate to a string");
        }
        return (String) result;
    }

    private List<String> resolveCandidates(String raw, List<ProcessVariable> variables) {
        if (!isExpression(raw)) {
            return splitCandidates(raw);
        }
        Object result = scriptService.evaluateExpression(stripExpression(raw), variables);
        if (result == null) {
            return List.of();
        }
        if (result instanceof String value) {
            return splitCandidates(value);
        }
        if (result instanceof List<?> values) {
            List<String> candidates = new ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof String candidate)) {
                    throw new IllegalStateException("Candidate expression '" + raw + "' must evaluate to a list of strings");
                }
                if (!candidate.isBlank()) {
                    candidates.add(candidate.trim());
                }
            }
            return candidates;
        }
        throw new IllegalStateException("Candidate expression '" + raw + "' did not evaluate to a string or list of strings");
    }

    private static boolean isExpression(String value) {
        return value != null && value.startsWith("=");
    }

    private static List<String> splitCandidates(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    @Override
    public void completeUserTask(UUID userTaskId, List<ProcessVariable> variables) {
        // Nothing of the task is loaded before its lock is held, so the state checked below is the
        // committed one, including a cancellation by a boundary timer that fired meanwhile.
        Optional<UUID> scopeId = dbService.findParentActivityId(userTaskId);
        if (scopeId.isPresent()) {
            completeMultiInstanceUserTask(scopeId.get(), userTaskId, variables);
            return;
        }

        Activity activity = activeTask(userTaskId);
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID token = activity.getToken();

        dbService.setVariables(processInstanceId, variables);
        dbService.cancelTimers(userTaskId);
        dbService.completeActivity(userTaskId);
        dbService.completeUserTask(userTaskId);

        log.info("{}/{}: Completing {}: {}/{}", processInstanceId, token, activity.getType(), userTaskId, activity.getBpmnElementId());

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        advance(processInstanceId, token, bpmn, bpmnElement);
    }

    private Activity activeTask(UUID taskId) {
        Activity activity = dbService.getActivityForUpdate(taskId);
        if (activity.getCompletedAt() != null) {
            throw new TaskNotActiveException("User task " + taskId + " is not active: " + activity.getStatus());
        }
        return activity;
    }

    private void completeMultiInstanceUserTask(UUID scopeId, UUID userTaskId, List<ProcessVariable> variables) {
        // Pessimistic lock on the scope row serializes concurrent sibling completions
        // for both the outputCollection read-modify-write and the join count check,
        // and the completion with a boundary timer firing on the whole element.
        Activity scope = dbService.getActivityForUpdate(scopeId);
        Activity activity = activeTask(userTaskId);
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID token = activity.getToken();

        dbService.setVariables(processInstanceId, variables);

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());
        MultiInstanceExtensionModel multiInstance = bpmnElement.getExtensions().getMultiInstanceExtension();

        if (multiInstance.getOutputCollection() != null && multiInstance.getOutputElement() != null) {
            Object out = scriptService.evaluateExpression(stripExpression(multiInstance.getOutputElement()), variables);
            updateOutputCollection(processInstanceId, multiInstance.getOutputCollection(), activity.getLoopIndex(), out);
        }

        dbService.completeActivity(userTaskId);
        dbService.completeUserTask(userTaskId);

        log.info("{}/{}: Completing {}: {}/{} [{}/{}]", processInstanceId, token, activity.getType(), userTaskId, activity.getBpmnElementId(), activity.getLoopIndex(), activity.getLoopTotal());

        if (multiInstance.getCompletionCondition() != null && evaluateCompletionCondition(processInstanceId, scope, multiInstance)) {
            dbService.cancelOpenChildUserTasks(scope.getId());
            dbService.cancelTimers(scope.getId());
            dbService.completeActivity(scope.getId());

            log.info("{}/{}: Multi-instance completion condition met, completing {}: {}/{}", processInstanceId, token, scope.getType(), scope.getId(), scope.getBpmnElementId());

            advance(processInstanceId, token, bpmn, bpmnElement);
            return;
        }

        if (multiInstance.isSequential() && activity.getLoopIndex() + 1 < activity.getLoopTotal()) {
            int nextIndex = activity.getLoopIndex() + 1;
            List<?> items = evaluateInputCollection(processInstanceId, multiInstance);
            createMultiInstanceChild(processInstanceId, token, scope.getId(), bpmnElement, items.get(nextIndex), nextIndex, activity.getLoopTotal());
            return;
        }

        if (dbService.countCompletedChildActivities(scope.getId()) == scope.getLoopTotal()) {
            dbService.cancelTimers(scope.getId());
            dbService.completeActivity(scope.getId());
            advance(processInstanceId, token, bpmn, bpmnElement);
        }
    }

    private boolean evaluateCompletionCondition(UUID processInstanceId, Activity scope, MultiInstanceExtensionModel multiInstance) {
        List<ProcessVariable> conditionVariables = new ArrayList<>(dbService.getVariables(processInstanceId));
        conditionVariables.add(longVariable("numberOfCompletedInstances", dbService.countCompletedChildActivities(scope.getId())));
        conditionVariables.add(longVariable("numberOfInstances", scope.getLoopTotal()));

        Object result = scriptService.evaluateExpression(stripExpression(multiInstance.getCompletionCondition()), conditionVariables);
        if (!(result instanceof Boolean)) {
            throw new IllegalStateException("Multi-instance completionCondition '" + multiInstance.getCompletionCondition() + "' did not evaluate to a boolean");
        }
        return (Boolean) result;
    }

    private static ProcessVariable longVariable(String name, long value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(ProcessVariableType.LONG);
        variable.setValue(String.valueOf(value));
        return variable;
    }

    private void updateOutputCollection(UUID processInstanceId, String outputCollection, int index, Object value) {
        String json = dbService.getVariables(processInstanceId).stream()
            .filter(variable -> outputCollection.equals(variable.getName()))
            .map(ProcessVariable::getValue)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Multi-instance outputCollection variable '" + outputCollection + "' is missing"));
        List<Object> results = new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<Object>>() {}));
        results.set(index, value);
        dbService.setVariables(processInstanceId, List.of(jsonVariable(outputCollection, results)));
    }

    @Override
    public void resolveIncident(UUID incidentId, List<ProcessVariable> variables) {
        Incident incident = dbService.getIncident(incidentId);
        // The row lock on the failed activity serializes concurrent resolves of the same step.
        Activity activity = dbService.getActivityForUpdate(incident.getActivityId());
        if (incident.getCompletedAt() != null || activity.getStatus() != ActivityStatus.ERROR) {
            throw new IncidentAlreadyResolvedException("Incident " + incidentId + " is already resolved");
        }

        UUID processInstanceId = activity.getProcessInstanceId();
        if (variables != null && !variables.isEmpty()) {
            dbService.setVariables(processInstanceId, variables);
        }
        dbService.resolveIncident(incidentId);

        log.info("{}/{}: Resolving incident {} on {}: {}/{}", processInstanceId, activity.getToken(), incidentId, activity.getType(), activity.getId(), activity.getBpmnElementId());

        // A service task that failed on entry (for example, on its boundary timer) has no job to
        // retry; it is executed again like any other element.
        if (activity.getType() == BpmnElementType.SERVICE_TASK && dbService.hasServiceTask(activity.getId())) {
            dbService.setActivityStatus(activity.getId(), ActivityStatus.CREATED);
            serviceTaskEnqueueService.enqueueAfterCommit(activity.getId());
            return;
        }

        // The element is executed afresh and arms its boundary timers again.
        dbService.cancelTimers(activity.getId());
        dbService.terminateActivity(activity.getId());
        execute(processInstanceId, activity.getToken(), activity.getBpmnElementId());
    }

    @Override
    public UUID startProcessInstance(UUID parentActivityId, UUID processDefinitionId, List<ProcessVariable> variables) {
        UUID processInstanceId = dbService.createProcessInstance(parentActivityId, processDefinitionId, variables);

        dbService.setVariables(processInstanceId, variables);

        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        String startEventId = bpmn.getStartEvent().getId();

        UUID parentTokenId = null;
        if (parentActivityId != null) {
            Activity activity = dbService.getActivity(parentActivityId);
            Token token = dbService.getToken(activity.getToken());
            parentTokenId = token.getId();
        }
        Token token = dbService.createToken(parentTokenId);

        execute(processInstanceId, token.getId(), startEventId);

        return processInstanceId;
    }

    private UUID processFlow(@NonNull UUID processInstanceId, @NonNull UUID tokenId, String flowId, @NonNull Boolean processExpression, Boolean defaultFlow) {
        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        UUID processDefinitionId = processInstance.getProcessDefinitionId();

        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        BpmnFlowModel flow = bpmn.getFlow(flowId);
        String targetRef = flow.getTargetRef();
        String sourceRef = flow.getSourceRef();
        BpmnElementModel target = bpmn.getElement(targetRef);
        BpmnElementModel source = bpmn.getElement(sourceRef);

        UUID flowActivityId = null;

        if (processExpression) {
            String expression = Optional.ofNullable(flow)
                .map(BpmnFlowModel::getConditionExpression)
                .map(BpmnConditionExpressionModel::getExpression)
                .filter(str -> !str.isEmpty())
                .map(str -> str.substring(1))
                .orElse(null);
            if (!(Objects.isNull(expression) && defaultFlow)) {
                List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
                Boolean test = (Boolean) scriptService.evaluateScript(expression, variables);
                if (Boolean.TRUE.equals(test)) {
                    flowActivityId = dbService.createActivity(processInstanceId, tokenId, flow);
                }
            }
        } else {
            flowActivityId = dbService.createActivity(processInstanceId, tokenId, flow);
        }

        if (flowActivityId != null) {
            dbService.completeActivity(flowActivityId);
            log.info("{}/{}: Flow: {}/{} => from {}/{} to {}/{}", processInstanceId, tokenId, flowActivityId, flowId, source.getType(), source.getId(), target.getType(), target.getId());
        }

        return flowActivityId;
    }

    /**
     * The token ends here; the instance completes only when no activity of it is open any more
     * (another branch, a host of a non-interrupting timer, an element with an incident).
     */
    private void processEndEvent(UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

        // The instance row lock serializes branches that reach end events in concurrent
        // transactions: the later one sees the earlier one's activities as closed and completes.
        ProcessInstance pi = dbService.getProcessInstanceForUpdate(processInstanceId);
        if (pi.getCompletedAt() != null) {
            return;
        }
        long open = dbService.countOpenActivities(processInstanceId);
        if (open > 0) {
            log.info("{}/{}: Process instance waits for {} open activities", processInstanceId, tokenId, open);
            return;
        }
        dbService.completeProcessInstance(processInstanceId);
        log.info("{}/{}: Completing process instance", processInstanceId, tokenId);

        UUID parentActivityId = pi.getParentActivityId();
        if (parentActivityId != null) {
            Activity parentActivity = dbService.getActivityForUpdate(parentActivityId);
            if (parentActivity.getCompletedAt() != null) {
                // The call activity was interrupted (by a boundary timer): the parent does not continue from here.
                log.info("{}/{}: Parent {} {}/{} is already {}, not continuing it", processInstanceId, tokenId, parentActivity.getType(), parentActivityId, parentActivity.getBpmnElementId(), parentActivity.getStatus());
                return;
            }
            dbService.cancelTimers(parentActivityId);
            dbService.completeActivity(parentActivityId);

            UUID parentProcessInstanceId = parentActivity.getProcessInstanceId();
            ProcessInstance parentProcessInstance = dbService.getProcessInstance(parentActivity.getProcessInstanceId());
            UUID parentProcessDefinitionId = parentProcessInstance.getProcessDefinitionId();
            UUID parentToken = parentActivity.getToken();
            BpmnProcessDefinitionModel parentBpmn = bpmnService.getProcessDefinitionModelById(parentProcessDefinitionId);

            List<ProcessVariable> variables = dbService.getVariables(processInstanceId);
            dbService.setVariables(parentProcessInstanceId, variables);

            log.info("{}/{}: Completing {}: {}/{}", parentProcessInstanceId, parentToken, parentActivity.getType(), parentActivityId, parentActivity.getBpmnElementId());

            BpmnElementModel parentBpmnElement = parentBpmn.getElement(parentActivity.getBpmnElementId());

            advance(parentProcessInstanceId, parentToken, parentBpmn, parentBpmnElement);
        }
    }

    @Override
    public boolean fireTimer(UUID timerId, UUID hostActivityId) {
        // Lock order host activity -> timer, the same as on the completion paths.
        Optional<Activity> locked = dbService.findActivityForUpdateSkipLocked(hostActivityId);
        if (locked.isEmpty()) {
            log.debug("Timer {}: host {} is locked, retrying on the next poll", timerId, hostActivityId);
            return false;
        }
        Activity host = locked.get();
        Timer timer = dbService.getTimerForUpdate(timerId).orElse(null);
        if (timer == null || timer.getStatus() != TimerStatus.SCHEDULED || timer.getDueAt().isAfter(clock.instant())) {
            return false;
        }
        UUID processInstanceId = host.getProcessInstanceId();
        if (host.getCompletedAt() != null) {
            dbService.setTimerStatus(timerId, TimerStatus.CANCELED);
            log.info("{}: Timer {} of {} canceled, host {} is already {}", processInstanceId, timerId, timer.getBpmnElementId(), hostActivityId, host.getStatus());
            return false;
        }

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel boundaryEvent = bpmn.getElement(timer.getBpmnElementId());

        UUID tokenId;
        if (boundaryEvent.getExtensions().getBoundaryEventExtension().isCancelActivity()) {
            dbService.setTimerStatus(timerId, TimerStatus.FIRED);
            interruptHost(host);
            tokenId = host.getToken();
        } else {
            if (timer.getCycleInterval() != null && (timer.getRemainingRepetitions() == null || timer.getRemainingRepetitions() > 0)) {
                Integer remaining = timer.getRemainingRepetitions() == null ? null : timer.getRemainingRepetitions() - 1;
                dbService.rescheduleTimer(timerId, timerExpressionService.next(timer.getCycleInterval(), timer.getDueAt()), remaining);
            } else {
                dbService.setTimerStatus(timerId, TimerStatus.FIRED);
            }
            tokenId = dbService.createToken(host.getToken()).getId();
        }

        UUID activityId = dbService.createActivity(processInstanceId, tokenId, boundaryEvent);
        dbService.completeActivity(activityId);
        log.info("{}/{}: Timer {} fired: {}/{} on {} {}/{}", processInstanceId, tokenId, timerId, activityId, boundaryEvent.getId(), host.getType(), hostActivityId, host.getBpmnElementId());

        advance(processInstanceId, tokenId, bpmn, boundaryEvent);
        return true;
    }

    /**
     * Ends the host of an interrupting boundary timer with everything under it.
     */
    private void interruptHost(Activity host) {
        UUID hostId = host.getId();
        if (host.getType() == BpmnElementType.USER_TASK && host.getLoopTotal() != null && host.getParentActivityId() == null) {
            dbService.cancelOpenChildUserTasks(hostId);
        } else if (host.getType() == BpmnElementType.USER_TASK) {
            dbService.cancelUserTask(hostId);
        } else if (host.getType() == BpmnElementType.SERVICE_TASK) {
            dbService.cancelServiceTask(hostId);
        } else if (host.getType() == BpmnElementType.CALL_ACTIVITY) {
            dbService.findChildProcessInstance(hostId).ifPresent(child -> terminateProcessInstance(child.getId()));
        }
        dbService.resolveOpenIncidents(hostId);
        dbService.cancelTimers(hostId);
        dbService.terminateActivity(hostId);

        log.info("{}/{}: Interrupted {}: {}/{}", host.getProcessInstanceId(), host.getToken(), host.getType(), hostId, host.getBpmnElementId());
    }

    /**
     * Ends a child instance of an interrupted call activity, recursively; it does not continue its parent.
     */
    private void terminateProcessInstance(UUID processInstanceId) {
        for (Activity activity : dbService.findOpenActivities(processInstanceId)) {
            if (activity.getType() == BpmnElementType.USER_TASK) {
                dbService.cancelUserTask(activity.getId());
            } else if (activity.getType() == BpmnElementType.SERVICE_TASK) {
                dbService.cancelServiceTask(activity.getId());
            } else if (activity.getType() == BpmnElementType.CALL_ACTIVITY) {
                dbService.findChildProcessInstance(activity.getId()).ifPresent(child -> terminateProcessInstance(child.getId()));
            }
            dbService.resolveOpenIncidents(activity.getId());
            dbService.cancelTimers(activity.getId());
            dbService.terminateActivity(activity.getId());
        }
        dbService.completeProcessInstance(processInstanceId);
        log.info("{}: Terminated process instance", processInstanceId);
    }

    private void processStartEvent(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

        advance(processInstanceId, tokenId, bpmn, bpmnElement);
    }

}
