package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.CallActivityExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventType;
import com.zorrodev.bpm.engine.bpmn.xml.*;
import com.zorrodev.bpm.engine.bpmn.xml.extension.CalledElementModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.ZeebeLoopCharacteristicsModel;
import com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnConditionExpressionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.ExclusiveGatewayExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import jakarta.xml.bind.JAXB;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class BpmnParseServiceImpl implements BpmnParseService {

    @Override
    public com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel parse(String bpmn) throws BpmnParseException {
        try {
            BpmnDefinitionsModel definitions = JAXB.unmarshal(new StringReader(bpmn), BpmnDefinitionsModel.class);
            BpmnProcessDefinitionModel process = definitions.getProcess();

            checkBpmn(process);

            com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel pd = new com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel();
            pd.setExecutionPlatformVersion(definitions.getExecutionPlatformVersion());
            pd.setKey(process.getId());
            pd.setName(process.getName());

            for (BpmnStartEventModel startEvent : process.getStartEvents()) {
                BpmnElementModel element = toElementModel(startEvent);
                element.setProcessDefinition(pd);
                pd.addElement(element);
                if (startEvent.getExtensionElements() != null && startEvent.getExtensionElements().getProperties() != null && startEvent.getExtensionElements().getProperties().getProperties() != null) {
                    List<PropertyModel> properties = startEvent.getExtensionElements().getProperties().getProperties();
                    for (PropertyModel property : properties) {
                        if (property.getName().equals("formKey")) {
                            pd.setStartFormKey(property.getValue());
                        }
                    }
                }
            }

            for (BpmnEndEventModel endEvent : process.getEndEvents()) {
                BpmnElementModel element = toElementModel(endEvent);
                element.setProcessDefinition(pd);
                element.setType(BpmnElementType.END_EVENT);
                pd.addElement(element);
            }

            if (Optional.ofNullable(process.getServiceTasks()).isPresent()) {
                for (BpmnServiceTaskModel serviceTask : process.getServiceTasks()) {
                    BpmnElementModel element = toElementModel(serviceTask);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getUserTasks()).isPresent()) {
                for (BpmnUserTaskModel userTask : process.getUserTasks()) {
                    BpmnElementModel element = toElementModel(userTask);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getExclusiveGateways()).isPresent()) {
                for (BpmnExclusiveGatewayModel exclusiveGateway : process.getExclusiveGateways()) {
                    BpmnElementModel element = toElementModel(exclusiveGateway);
                    element.setProcessDefinition(pd);
                    element.setType(BpmnElementType.EXCLUSIVE_GATEWAY);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getParallelGateways()).isPresent()) {
                for (BpmnParallelGatewayModel parallelGateway : process.getParallelGateways()) {
                    BpmnElementModel element = toElementModel(parallelGateway);
                    element.setProcessDefinition(pd);
                    element.setType(BpmnElementType.PARALLEL_GATEWAY);
                    pd.addElement(element);
                }
            }
            if (Optional.ofNullable(process.getFlows()).isPresent()) {
                for (BpmnSequenceFlowModel flow : process.getFlows()) {
                    BpmnFlowModel element = toFlowModel(flow);
                    pd.addFlow(element);
                }
            }
            if (process.getIntermediateCatchEvents() != null) {
                for (BpmnIntermediateCatchEventModel catchEvent : process.getIntermediateCatchEvents()) {
                    BpmnElementModel element = toElementModel(catchEvent);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }

            if (process.getIntermediateThrowEvents() != null) {
                for (BpmnIntermediateThrowEventModel throwEvent : process.getIntermediateThrowEvents()) {
                    BpmnElementModel element = toElementModel(throwEvent);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }

            if (process.getCallActivities() != null) {
                for (BpmnCallActivityModel callActivity : process.getCallActivities()) {
                    BpmnElementModel element = toElementModel(callActivity);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }

            if (process.getBoundaryEvents() != null) {
                for (BpmnBoundaryEventModel boundaryEvent : process.getBoundaryEvents()) {
                    checkBoundaryEvent(boundaryEvent, pd);
                    BpmnElementModel element = toElementModel(boundaryEvent);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
            }

            return pd;
        } catch (BpmnParseException e) {
            throw e;
        } catch (Exception e) {
            throw new BpmnParseException(e);
        }
    }

    private BpmnFlowModel toFlowModel(BpmnSequenceFlowModel flow) {
        BpmnFlowModel element = new BpmnFlowModel();
        element.setFlowId(flow.getId());
        element.setSourceRef(flow.getSourceRef());
        element.setTargetRef(flow.getTargetRef());
        if (flow.getConditionExpression() != null) {
            BpmnConditionExpressionModel expression = new BpmnConditionExpressionModel();
            expression.setType(flow.getConditionExpression().getType());
            expression.setExpression(flow.getConditionExpression().getExpression());
            element.setConditionExpression(expression);
        }
        return element;
    }

    private void checkBpmn(BpmnProcessDefinitionModel process) {
        if (Optional.ofNullable(process.getStartEvents()).isEmpty()) {
            throw new BpmnParseException("No start events in the process definition xml");
        }
        long vanillaStartEventCount = process.getStartEvents().stream()
            .filter(e -> e.getMessageEventDefinition()==null)
            .filter(e -> e.getTimerEventDefinition()==null)
            .count();
        if (vanillaStartEventCount != 1) {
            throw new BpmnParseException("Start event should be exactly one in the process definition xml");
        }
        if (Optional.ofNullable(process.getEndEvents()).isEmpty()) {
            throw new BpmnParseException("No end events in the process definition xml");
        }
    }

    private static final Set<BpmnElementType> BOUNDARY_HOST_TYPES = Set.of(
        BpmnElementType.USER_TASK, BpmnElementType.SERVICE_TASK, BpmnElementType.CALL_ACTIVITY);

    /**
     * Only timer boundary events on user tasks, service tasks and call activities are executable;
     * anything else is rejected instead of being silently dropped. Runs after all other elements
     * are in the model, because the host's type is needed.
     */
    private void checkBoundaryEvent(BpmnBoundaryEventModel boundaryEvent, com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel pd) {
        String id = boundaryEvent.getId();
        BpmnTimerEventDefinitionModel timer = boundaryEvent.getTimerEventDefinition();
        boolean otherDefinition = Optional.ofNullable(boundaryEvent.getOtherElements()).orElse(List.of()).stream()
            .filter(Element.class::isInstance)
            .map(Element.class::cast)
            .anyMatch(e -> e.getLocalName() != null && e.getLocalName().endsWith("EventDefinition"));
        if (timer == null || otherDefinition) {
            throw new BpmnParseException("Boundary event '" + id + "': only timer boundary events are supported");
        }
        String attachedTo = boundaryEvent.getAttachedToRef();
        BpmnElementModel host = attachedTo == null ? null : pd.getElement(attachedTo);
        if (host == null) {
            throw new BpmnParseException("Boundary event '" + id + "': attached element '" + attachedTo + "' does not exist");
        }
        if (!BOUNDARY_HOST_TYPES.contains(host.getType())) {
            throw new BpmnParseException("Boundary event '" + id + "': timer boundary events are supported only on user tasks, service tasks and call activities, not on " + host.getType() + " '" + attachedTo + "'");
        }
        if (boundaryEvent.getOutgoing() == null || boundaryEvent.getOutgoing().isEmpty()) {
            throw new BpmnParseException("Boundary event '" + id + "': no outgoing sequence flow");
        }
        if (isBlank(timer.getTimeDate()) && isBlank(timer.getTimeDuration()) && isBlank(timer.getTimeCycle())) {
            throw new BpmnParseException("Boundary event '" + id + "': timer has no timeDate, timeDuration or timeCycle");
        }
        boolean interrupting = !Boolean.FALSE.equals(boundaryEvent.getCancelActivity());
        if (interrupting && !isBlank(timer.getTimeCycle())) {
            throw new BpmnParseException("Boundary event '" + id + "': timeCycle is supported only on non-interrupting timers");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static TimerEventExtensionModel toTimerExtension(BpmnTimerEventDefinitionModel definition) {
        TimerEventExtensionModel timer = new TimerEventExtensionModel();
        if (!isBlank(definition.getTimeDate())) {
            timer.setType(TimerEventType.DATE);
            timer.setExpression(definition.getTimeDate().trim());
        } else if (!isBlank(definition.getTimeDuration())) {
            timer.setType(TimerEventType.DURATION);
            timer.setExpression(definition.getTimeDuration().trim());
        } else if (!isBlank(definition.getTimeCycle())) {
            timer.setType(TimerEventType.CYCLE);
            timer.setExpression(definition.getTimeCycle().trim());
        }
        return timer;
    }

    private BpmnElementModel toElementModel(BpmnBoundaryEventModel boundaryEvent) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(boundaryEvent.getId());
        element.setName(boundaryEvent.getName());
        element.setType(BpmnElementType.TIMER_BOUNDARY_EVENT);
        element.setOutgoing(boundaryEvent.getOutgoing());

        BoundaryEventExtensionModel boundary = new BoundaryEventExtensionModel();
        boundary.setAttachedTo(boundaryEvent.getAttachedToRef());
        boundary.setCancelActivity(!Boolean.FALSE.equals(boundaryEvent.getCancelActivity()));

        element.setExtensions(new BpmnElementExtensionModel());
        element.getExtensions().setBoundaryEventExtension(boundary);
        element.getExtensions().setTimerEventExtension(toTimerExtension(boundaryEvent.getTimerEventDefinition()));
        return element;
    }

    private BpmnElementModel toElementModel(BpmnServiceTaskModel serviceTask) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(serviceTask.getId());
        element.setName(serviceTask.getName());
        element.setType(BpmnElementType.SERVICE_TASK);
        element.setIncoming(serviceTask.getIncoming());
        element.setOutgoing(serviceTask.getOutgoing());
        if (serviceTask.getExtensionElements() != null && serviceTask.getExtensionElements().getTaskDefinition() != null) {
            element.setExtensions(new BpmnElementExtensionModel());
            element.getExtensions().setServiceTaskExtension(new ServiceTaskExtensionModel());
            element.getExtensions().getServiceTaskExtension().setJob(serviceTask.getExtensionElements().getTaskDefinition().getType());
        }
        return element;
    }

    private BpmnElementModel toElementModel(BpmnUserTaskModel userTask) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(userTask.getId());
        element.setName(userTask.getName());
        element.setType(BpmnElementType.USER_TASK);
        element.setIncoming(userTask.getIncoming());
        element.setOutgoing(userTask.getOutgoing());
        if (userTask.getExtensionElements() != null || userTask.getMultiInstanceLoopCharacteristics() != null) {
            element.setExtensions(new BpmnElementExtensionModel());
        }
        if (userTask.getMultiInstanceLoopCharacteristics() != null) {
            MultiInstanceExtensionModel multiInstance = new MultiInstanceExtensionModel();
            multiInstance.setSequential(Boolean.TRUE.equals(userTask.getMultiInstanceLoopCharacteristics().getIsSequential()));
            ZeebeLoopCharacteristicsModel loopCharacteristics = Optional.ofNullable(userTask.getMultiInstanceLoopCharacteristics().getExtensionElements())
                .map(ExtensionElements::getLoopCharacteristics)
                .orElse(null);
            if (loopCharacteristics != null) {
                multiInstance.setInputCollection(loopCharacteristics.getInputCollection());
                multiInstance.setInputElement(loopCharacteristics.getInputElement());
                multiInstance.setOutputCollection(loopCharacteristics.getOutputCollection());
                multiInstance.setOutputElement(loopCharacteristics.getOutputElement());
            }
            multiInstance.setCompletionCondition(Optional.ofNullable(userTask.getMultiInstanceLoopCharacteristics().getCompletionCondition())
                .map(condition -> condition.getExpression())
                .filter(str -> !str.isEmpty())
                .orElse(null));
            element.getExtensions().setMultiInstanceExtension(multiInstance);
        }
        if (userTask.getExtensionElements() != null) {
            element.getExtensions().setUserTaskExtension(new UserTaskExtensionModel());
            if (userTask.getExtensionElements().getAssignmentDefinition() != null) {
                element.getExtensions().getUserTaskExtension().setAssignee(userTask.getExtensionElements().getAssignmentDefinition().getAssignee());
                element.getExtensions().getUserTaskExtension().setCandidateUsers(userTask.getExtensionElements().getAssignmentDefinition().getCandidateUsers());
                element.getExtensions().getUserTaskExtension().setCandidateGroups(userTask.getExtensionElements().getAssignmentDefinition().getCandidateGroups());
            }
            if (userTask.getExtensionElements().getFormDefinition() != null) {
                if (userTask.getExtensionElements().getFormDefinition().getFormKey() != null) {
                    element.getExtensions().getUserTaskExtension().setFormKey(userTask.getExtensionElements().getFormDefinition().getFormKey());
                } else if (userTask.getExtensionElements().getFormDefinition().getExternalReference() != null) {
                    element.getExtensions().getUserTaskExtension().setFormKey(userTask.getExtensionElements().getFormDefinition().getExternalReference());
                }
            }
        }
        return element;
    }

    private BpmnElementModel toElementModel(BpmnEndEventModel endEvent) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(endEvent.getId());
        element.setName(endEvent.getName());
        element.setType(BpmnElementType.END_EVENT);
        element.setIncoming(endEvent.getIncoming());
        return element;
    }

    private BpmnElementModel toElementModel(BpmnStartEventModel startEvent) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(startEvent.getId());
        element.setName(startEvent.getName());

        if (startEvent.getIncoming() != null) {
            element.getIncoming().addAll(startEvent.getIncoming());
        }

        if (startEvent.getOutgoing() != null) {
            element.getOutgoing().addAll(startEvent.getOutgoing());
        }

        if (startEvent.getMessageEventDefinition() != null) {
            element.setType(BpmnElementType.MESSAGE_START_EVENT);
        } else if (startEvent.getTimerEventDefinition() != null) {
            element.setType(BpmnElementType.TIMER_START_EVENT);
        } else {
            element.setType(BpmnElementType.START_EVENT);
        }

        return element;
    }


    private BpmnElementModel toElementModel(BpmnExclusiveGatewayModel exclusiveGateway) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(exclusiveGateway.getId());
        element.setName(exclusiveGateway.getName());
        element.setType(BpmnElementType.EXCLUSIVE_GATEWAY);
        element.setOutgoing(exclusiveGateway.getOutgoing());
        element.setIncoming(exclusiveGateway.getIncoming());
        if (exclusiveGateway.getDefaultFlow() != null) {
            element.setExtensions(new BpmnElementExtensionModel());
            element.getExtensions().setExclusiveGatewayExtension(new ExclusiveGatewayExtensionModel());
            element.getExtensions().getExclusiveGatewayExtension().setDefaultFlowId(exclusiveGateway.getDefaultFlow());
        }
        return element;
    }

    private BpmnElementModel toElementModel(BpmnParallelGatewayModel parallelGateway) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(parallelGateway.getId());
        element.setName(parallelGateway.getName());
        element.setType(BpmnElementType.PARALLEL_GATEWAY);
        element.setOutgoing(parallelGateway.getOutgoing());
        element.setIncoming(parallelGateway.getIncoming());
        return element;
    }

    private BpmnElementModel toElementModel(BpmnIntermediateCatchEventModel catchEvent) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(catchEvent.getId());
        element.setName(catchEvent.getName());
        element.setIncoming(catchEvent.getIncoming());
        element.setOutgoing(catchEvent.getOutgoing());

        if (catchEvent.getMessageEventDefinition() != null) {
            element.setType(BpmnElementType.MESSAGE_CATCH_EVENT);
        } else if (catchEvent.getTimerEventDefinition() != null) {
            element.setType(BpmnElementType.TIMER_CATCH_EVENT);
        } else {
            element.setType(BpmnElementType.INTERMEDIATE_CATCH_EVENT);
        }

        if (catchEvent.getTimerEventDefinition() != null) {
            element.setExtensions(new BpmnElementExtensionModel());
            element.getExtensions().setTimerEventExtension(toTimerExtension(catchEvent.getTimerEventDefinition()));
        }

        return element;
    }

    private BpmnElementModel toElementModel(BpmnIntermediateThrowEventModel throwEvent) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(throwEvent.getId());
        element.setName(throwEvent.getName());
        element.setIncoming(throwEvent.getIncoming());
        element.setOutgoing(throwEvent.getOutgoing());

        if (throwEvent.getMessageEventDefinition() != null) {
            element.setType(BpmnElementType.MESSAGE_THROW_EVENT);
        } else {
            element.setType(BpmnElementType.INTERMEDIATE_THROW_EVENT);
        }

        return element;
    }

    private BpmnElementModel toElementModel(BpmnCallActivityModel callActivity) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(callActivity.getId());
        element.setName(callActivity.getName());
        element.setType(BpmnElementType.CALL_ACTIVITY);
        element.setOutgoing(callActivity.getOutgoing());
        element.setIncoming(callActivity.getIncoming());
        if (callActivity.getExtensionElements() != null) {
            element.setExtensions(new BpmnElementExtensionModel());
            element.getExtensions().setCallActivityExtension(new CallActivityExtensionModel());
            CalledElementModel calledElement = Optional.ofNullable(callActivity.getExtensionElements()).map(ExtensionElements::getCalledElement).orElse(null);;
            if (calledElement != null) {
                element.getExtensions().getCallActivityExtension().setProcessId(calledElement.getProcessId());
                element.getExtensions().getCallActivityExtension().setBindingType(calledElement.getBindingType());
                element.getExtensions().getCallActivityExtension().setPropagateAllChildVariables(calledElement.getPropagateAllChildVariables());
            }
        }
        return element;
    }
}
