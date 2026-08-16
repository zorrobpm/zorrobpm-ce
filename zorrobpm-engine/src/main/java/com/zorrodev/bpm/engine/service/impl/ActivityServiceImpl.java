package com.zorrodev.bpm.engine.service.impl;

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
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.dto.Token;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private final DBService dbService;
    private final BpmnService bpmnService;
    private final ScriptService scriptService;
    private final ServiceTaskEnqueueService serviceTaskEnqueueService;
    private final ObjectMapper objectMapper;

    @Override
    public void execute(UUID processInstanceId, UUID tokenId, String bpmnElementId) {
        ProcessInstance processInstanceEntity = dbService.getProcessInstance(processInstanceId);
        UUID processDefinitionId = processInstanceEntity.getProcessDefinitionId();
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processDefinitionId);
        BpmnElementModel element = bpmn.getElement(bpmnElementId);

        execute(processInstanceId, tokenId, bpmn, element);
    }

    private void execute(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel element) {
        BpmnElementType type = element.getType();

        if (type == BpmnElementType.START_EVENT) {
            processStartEvent(processInstanceId, tokenId, bpmn, element);
        } else if (type == BpmnElementType.END_EVENT) {
            processEndEvent(processInstanceId, tokenId, element);
        } else if (type == BpmnElementType.SERVICE_TASK) {
            enterServiceTask(processInstanceId, tokenId, element);
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

    private void processCallActivity(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);

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
                execute(processInstanceId, token, bpmn, target);
            } else {
                String outgoing = bpmnElement.getExtensions().getExclusiveGatewayExtension().getDefaultFlowId();
                processFlow(processInstanceId, token, outgoing, false, null);
                BpmnFlowModel flow = bpmn.getFlow(outgoing);
                String targetRef = flow.getTargetRef();
                BpmnElementModel target = bpmn.getElement(targetRef);
                execute(processInstanceId, token, bpmn, target);
            }
        } else if (outgoings.size() == 1 && incoming.size() > 1) {
            String outgoing = outgoings.get(0);
            processFlow(processInstanceId, token, outgoing, false, null);
            BpmnFlowModel flow = bpmn.getFlow(outgoing);
            String targetRef = flow.getTargetRef();
            BpmnElementModel target = bpmn.getElement(targetRef);
            execute(processInstanceId, token, bpmn, target);
        }
    }

    private void enterServiceTask(UUID processInstanceId, UUID token, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);
        dbService.createServiceTask(activityId);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());

        serviceTaskEnqueueService.enqueueAfterCommit(activityId);
    }

    @Override
    public void completeServiceTask(UUID serviceTaskId, List<ProcessVariable> variables) {
        Activity activity = dbService.getActivity(serviceTaskId);
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID tokenId = activity.getToken();

        dbService.setVariables(processInstanceId, variables);
        dbService.completeActivity(serviceTaskId);
        dbService.completeServiceTask(serviceTaskId);

        log.info("{}/{}: Completing {}: {}/{}", processInstanceId, tokenId, activity.getType(), serviceTaskId, activity.getBpmnElementId());

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        advance(processInstanceId, tokenId, bpmn, bpmnElement);
    }

    private void enterUserTask(UUID processInstanceId, UUID token, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        MultiInstanceExtensionModel multiInstance = Optional.ofNullable(bpmnElement.getExtensions())
            .map(BpmnElementExtensionModel::getMultiInstanceExtension)
            .orElse(null);
        if (multiInstance != null) {
            enterMultiInstanceUserTask(processInstanceId, token, bpmn, bpmnElement, multiInstance);
            return;
        }

        UUID activityId = dbService.createActivity(processInstanceId, token, bpmnElement);
        dbService.createUserTask(activityId, bpmnElement);

        log.info("{}/{}: Entering {}: {}/{}", processInstanceId, token, bpmnElement.getType(), activityId, bpmnElement.getId());
    }

    private void enterMultiInstanceUserTask(UUID processInstanceId, UUID token, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement, MultiInstanceExtensionModel multiInstance) {
        List<?> items = evaluateInputCollection(processInstanceId, multiInstance);
        int total = items.size();

        UUID scopeActivityId = dbService.createActivity(processInstanceId, token, bpmnElement, null, null, total);

        log.info("{}/{}: Entering multi-instance {}: {}/{} with {} instances", processInstanceId, token, bpmnElement.getType(), scopeActivityId, bpmnElement.getId(), total);

        if (multiInstance.getOutputCollection() != null) {
            List<Object> results = new ArrayList<>(Collections.nCopies(total, null));
            dbService.setVariables(processInstanceId, List.of(jsonVariable(multiInstance.getOutputCollection(), results)));
        }

        if (total == 0) {
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
        dbService.createUserTask(activityId, bpmnElement, index, total, objectMapper.writeValueAsString(item));

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

    @Override
    public void completeUserTask(UUID userTaskId, List<ProcessVariable> variables) {
        Activity activity = dbService.getActivity(userTaskId);
        if (activity.getParentActivityId() != null) {
            completeMultiInstanceUserTask(activity, variables);
            return;
        }

        UUID processInstanceId = activity.getProcessInstanceId();
        UUID token = activity.getToken();

        dbService.setVariables(processInstanceId, variables);
        dbService.completeActivity(userTaskId);
        dbService.completeUserTask(userTaskId);

        log.info("{}/{}: Completing {}: {}/{}", processInstanceId, token, activity.getType(), userTaskId, activity.getBpmnElementId());

        ProcessInstance processInstance = dbService.getProcessInstance(processInstanceId);
        BpmnProcessDefinitionModel bpmn = bpmnService.getProcessDefinitionModelById(processInstance.getProcessDefinitionId());
        BpmnElementModel bpmnElement = bpmn.getElement(activity.getBpmnElementId());

        advance(processInstanceId, token, bpmn, bpmnElement);
    }

    private void completeMultiInstanceUserTask(Activity activity, List<ProcessVariable> variables) {
        UUID userTaskId = activity.getId();
        UUID processInstanceId = activity.getProcessInstanceId();
        UUID token = activity.getToken();

        // Pessimistic lock on the scope row serializes concurrent sibling completions
        // for both the outputCollection read-modify-write and the join count check.
        Activity scope = dbService.getActivityForUpdate(activity.getParentActivityId());

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
    public void resolveIncident(UUID incidentId) {
        Incident incident = dbService.getIncident(incidentId);
        Activity activity = dbService.getActivity(incident.getActivityId());
        execute(activity.getProcessInstanceId(), activity.getToken(), activity.getBpmnElementId());
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

    private void processEndEvent(UUID processInstanceId, UUID tokenId, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);
        dbService.completeProcessInstance(processInstanceId);

        log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

        ProcessInstance pi = dbService.getProcessInstance(processInstanceId);
        UUID parentActivityId = pi.getParentActivityId();
        if (parentActivityId != null) {
            dbService.completeActivity(parentActivityId);

            Activity parentActivity = dbService.getActivity(parentActivityId);
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

    private void processStartEvent(UUID processInstanceId, UUID tokenId, BpmnProcessDefinitionModel bpmn, BpmnElementModel bpmnElement) {
        UUID activityId = dbService.createActivity(processInstanceId, tokenId, bpmnElement);
        dbService.completeActivity(activityId);

        log.info("{}/{}: Entering and completing {}: {}/{}", processInstanceId, tokenId, bpmnElement.getType(), activityId, bpmnElement.getId());

        advance(processInstanceId, tokenId, bpmn, bpmnElement);
    }

}
