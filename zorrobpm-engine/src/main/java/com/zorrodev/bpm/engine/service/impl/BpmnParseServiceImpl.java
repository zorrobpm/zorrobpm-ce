package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.model.BoundaryEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.CallActivityExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.ErrorEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventType;
import com.zorrodev.bpm.engine.bpmn.xml.*;
import com.zorrodev.bpm.engine.bpmn.xml.extension.CalledElementModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.IoInputModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.ZeebeLoopCharacteristicsModel;
import com.zorrodev.bpm.engine.bpmn.model.MultiInstanceExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnConditionExpressionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnFlowModel;
import com.zorrodev.bpm.engine.bpmn.model.ExclusiveGatewayExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.InputMappingModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import jakarta.xml.bind.JAXB;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import java.io.StringReader;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
                Map<String, BpmnErrorModel> errors = Optional.ofNullable(definitions.getErrors()).orElse(List.of()).stream()
                    .filter(error -> error.getId() != null)
                    .collect(Collectors.toMap(BpmnErrorModel::getId, Function.identity(), (first, second) -> first));
                for (BpmnBoundaryEventModel boundaryEvent : process.getBoundaryEvents()) {
                    BpmnElementModel element = checkBoundaryEvent(boundaryEvent, pd, errors);
                    element.setProcessDefinition(pd);
                    pd.addElement(element);
                }
                checkErrorCodesPerHost(process.getBoundaryEvents(), pd);
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

    private static final Set<BpmnElementType> ERROR_BOUNDARY_HOST_TYPES = Set.of(
        BpmnElementType.SERVICE_TASK, BpmnElementType.CALL_ACTIVITY);

    /**
     * Only timer boundary events on user tasks, service tasks and call activities and error boundary
     * events on service tasks and call activities are executable; anything else is rejected instead
     * of being silently dropped. Runs after all other elements are in the model, because the host's
     * type is needed.
     */
    private BpmnElementModel checkBoundaryEvent(BpmnBoundaryEventModel boundaryEvent, com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel pd, Map<String, BpmnErrorModel> errors) {
        String id = boundaryEvent.getId();
        boolean otherDefinition = Optional.ofNullable(boundaryEvent.getOtherElements()).orElse(List.of()).stream()
            .filter(Element.class::isInstance)
            .map(Element.class::cast)
            .anyMatch(e -> e.getLocalName() != null && e.getLocalName().endsWith("EventDefinition"));
        boolean timer = boundaryEvent.getTimerEventDefinition() != null;
        boolean error = boundaryEvent.getErrorEventDefinition() != null;
        if (otherDefinition || timer == error) {
            throw new BpmnParseException("Boundary event '" + id + "': only timer and error boundary events are supported");
        }
        String attachedTo = boundaryEvent.getAttachedToRef();
        BpmnElementModel host = attachedTo == null ? null : pd.getElement(attachedTo);
        if (host == null) {
            throw new BpmnParseException("Boundary event '" + id + "': attached element '" + attachedTo + "' does not exist");
        }
        if (timer) {
            checkTimerBoundaryEvent(boundaryEvent, host);
            checkOutgoing(boundaryEvent);
            return toTimerElementModel(boundaryEvent);
        }
        String errorCode = checkErrorBoundaryEvent(boundaryEvent, host, errors);
        checkOutgoing(boundaryEvent);
        return toErrorElementModel(boundaryEvent, errorCode);
    }

    private static void checkOutgoing(BpmnBoundaryEventModel boundaryEvent) {
        if (boundaryEvent.getOutgoing() == null || boundaryEvent.getOutgoing().isEmpty()) {
            throw new BpmnParseException("Boundary event '" + boundaryEvent.getId() + "': no outgoing sequence flow");
        }
    }

    private void checkTimerBoundaryEvent(BpmnBoundaryEventModel boundaryEvent, BpmnElementModel host) {
        String id = boundaryEvent.getId();
        BpmnTimerEventDefinitionModel timer = boundaryEvent.getTimerEventDefinition();
        if (!BOUNDARY_HOST_TYPES.contains(host.getType())) {
            throw new BpmnParseException("Boundary event '" + id + "': timer boundary events are supported only on user tasks, service tasks and call activities, not on " + host.getType() + " '" + host.getId() + "'");
        }
        if (isBlank(timer.getTimeDate()) && isBlank(timer.getTimeDuration()) && isBlank(timer.getTimeCycle())) {
            throw new BpmnParseException("Boundary event '" + id + "': timer has no timeDate, timeDuration or timeCycle");
        }
        boolean interrupting = !Boolean.FALSE.equals(boundaryEvent.getCancelActivity());
        if (interrupting && !isBlank(timer.getTimeCycle())) {
            throw new BpmnParseException("Boundary event '" + id + "': timeCycle is supported only on non-interrupting timers");
        }
    }

    /**
     * Returns the error code the event catches, {@code null} for an event without {@code errorRef}.
     */
    private String checkErrorBoundaryEvent(BpmnBoundaryEventModel boundaryEvent, BpmnElementModel host, Map<String, BpmnErrorModel> errors) {
        String id = boundaryEvent.getId();
        if (!ERROR_BOUNDARY_HOST_TYPES.contains(host.getType())) {
            throw new BpmnParseException("Boundary event '" + id + "': error boundary events are supported only on service tasks and call activities, not on " + host.getType() + " '" + host.getId() + "'");
        }
        if (Boolean.FALSE.equals(boundaryEvent.getCancelActivity())) {
            throw new BpmnParseException("Boundary event '" + id + "': error boundary events are always interrupting, cancelActivity=\"false\" is not allowed");
        }
        String errorRef = boundaryEvent.getErrorEventDefinition().getErrorRef();
        if (isBlank(errorRef)) {
            return null;
        }
        BpmnErrorModel error = errors.get(errorRef.trim());
        if (error == null) {
            throw new BpmnParseException("Boundary event '" + id + "': error '" + errorRef + "' does not exist");
        }
        if (isBlank(error.getErrorCode())) {
            throw new BpmnParseException("Boundary event '" + id + "': error '" + errorRef + "' has no errorCode");
        }
        String errorCode = error.getErrorCode().trim();
        if (errorCode.startsWith("=")) {
            throw new BpmnParseException("Boundary event '" + id + "': errorCode expressions are not supported, got '" + errorCode + "'");
        }
        return errorCode;
    }

    /**
     * On one host an error code is caught by at most one event, and at most one event catches any code.
     */
    private void checkErrorCodesPerHost(List<BpmnBoundaryEventModel> boundaryEvents, com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel pd) {
        Set<List<String>> seen = new HashSet<>();
        for (BpmnBoundaryEventModel boundaryEvent : boundaryEvents) {
            BpmnElementModel element = pd.getElement(boundaryEvent.getId());
            if (element.getType() != BpmnElementType.ERROR_BOUNDARY_EVENT) {
                continue;
            }
            String errorCode = element.getExtensions().getErrorEventExtension().getErrorCode();
            // Arrays.asList, not List.of: the code of a catch-all event is null.
            if (!seen.add(Arrays.asList(boundaryEvent.getAttachedToRef(), errorCode))) {
                throw new BpmnParseException("Boundary event '" + boundaryEvent.getId() + "': element '" + boundaryEvent.getAttachedToRef() + "' already has an error boundary event "
                    + (errorCode == null ? "without an error code" : "for error code '" + errorCode + "'"));
            }
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

    private BpmnElementModel toTimerElementModel(BpmnBoundaryEventModel boundaryEvent) {
        BpmnElementModel element = toBoundaryElementModel(boundaryEvent, BpmnElementType.TIMER_BOUNDARY_EVENT);
        element.getExtensions().setTimerEventExtension(toTimerExtension(boundaryEvent.getTimerEventDefinition()));
        return element;
    }

    private BpmnElementModel toErrorElementModel(BpmnBoundaryEventModel boundaryEvent, String errorCode) {
        BpmnElementModel element = toBoundaryElementModel(boundaryEvent, BpmnElementType.ERROR_BOUNDARY_EVENT);
        ErrorEventExtensionModel error = new ErrorEventExtensionModel();
        error.setErrorCode(errorCode);
        element.getExtensions().setErrorEventExtension(error);
        return element;
    }

    private BpmnElementModel toBoundaryElementModel(BpmnBoundaryEventModel boundaryEvent, BpmnElementType type) {
        BpmnElementModel element = new BpmnElementModel();
        element.setId(boundaryEvent.getId());
        element.setName(boundaryEvent.getName());
        element.setType(type);
        element.setOutgoing(boundaryEvent.getOutgoing());

        BoundaryEventExtensionModel boundary = new BoundaryEventExtensionModel();
        boundary.setAttachedTo(boundaryEvent.getAttachedToRef());
        boundary.setCancelActivity(!Boolean.FALSE.equals(boundaryEvent.getCancelActivity()));

        element.setExtensions(new BpmnElementExtensionModel());
        element.getExtensions().setBoundaryEventExtension(boundary);
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
            element.getExtensions().getServiceTaskExtension().setRetries(parseRetries(serviceTask));
            element.getExtensions().getServiceTaskExtension().setRetryTimeout(parseRetryTimeout(serviceTask));
        }
        setInputMapping(element, "Service task", serviceTask.getExtensionElements());
        return element;
    }

    /**
     * {@code zeebe:ioMapping}: a {@code zeebe:input} per input variable. A source with a leading
     * {@code =} is a FEEL expression, otherwise a string literal. {@code zeebe:output} is ignored.
     * An {@code ioMapping} without inputs is no mapping.
     */
    private static void setInputMapping(BpmnElementModel element, String kind, ExtensionElements extensionElements) {
        InputMappingModel mapping = parseInputMapping(kind, element.getId(), extensionElements);
        if (mapping == null) {
            return;
        }
        if (element.getExtensions() == null) {
            element.setExtensions(new BpmnElementExtensionModel());
        }
        element.getExtensions().setInputMapping(mapping);
    }

    static InputMappingModel parseInputMapping(String kind, String id, ExtensionElements extensionElements) {
        List<IoInputModel> inputs = Optional.ofNullable(extensionElements)
            .map(ExtensionElements::getIoMapping)
            .map(io -> io.getInputs())
            .orElse(List.of());
        if (inputs.isEmpty()) {
            return null;
        }
        List<InputMappingModel.Input> result = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (IoInputModel input : inputs) {
            String target = input.getTarget() == null ? null : input.getTarget().trim();
            if (isBlank(target)) {
                throw new BpmnParseException(kind + " '" + id + "': input mapping has an input without target");
            }
            if (isBlank(input.getSource())) {
                throw new BpmnParseException(kind + " '" + id + "': input '" + target + "' has no source");
            }
            if (!targets.add(target)) {
                throw new BpmnParseException(kind + " '" + id + "': input target '" + target + "' is declared twice");
            }
            String source = input.getSource();
            boolean expression = source.startsWith("=");
            result.add(new InputMappingModel.Input(target, expression ? source.substring(1) : source, expression));
        }
        return new InputMappingModel(List.copyOf(result));
    }

    static final String RETRY_TIMEOUT_PROPERTY = "retryTimeout";

    /** {@code zeebe:taskDefinition@retries}: a literal integer {@code >= 0}, 0 when absent. */
    private static int parseRetries(BpmnServiceTaskModel serviceTask) {
        String value = serviceTask.getExtensionElements().getTaskDefinition().getRetries();
        if (isBlank(value)) {
            return 0;
        }
        int retries;
        try {
            retries = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new BpmnParseException("Service task '" + serviceTask.getId() + "': retries '" + value + "' is not an integer");
        }
        if (retries < 0) {
            throw new BpmnParseException("Service task '" + serviceTask.getId() + "': retries must not be negative, got " + retries);
        }
        return retries;
    }

    /** {@code zeebe:property name="retryTimeout"}: an ISO-8601 duration {@code >= 0}, zero when absent. */
    private static Duration parseRetryTimeout(BpmnServiceTaskModel serviceTask) {
        String value = Optional.ofNullable(serviceTask.getExtensionElements().getProperties())
            .map(PropertiesModel::getProperties).orElse(List.of()).stream()
            .filter(p -> RETRY_TIMEOUT_PROPERTY.equals(p.getName()))
            .map(PropertyModel::getValue)
            .findFirst().orElse(null);
        if (isBlank(value)) {
            return Duration.ZERO;
        }
        Duration timeout;
        try {
            timeout = Duration.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BpmnParseException("Service task '" + serviceTask.getId() + "': retryTimeout '" + value + "' is not an ISO-8601 duration");
        }
        if (timeout.isNegative()) {
            throw new BpmnParseException("Service task '" + serviceTask.getId() + "': retryTimeout must not be negative, got " + value);
        }
        return timeout;
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
        setInputMapping(element, "User task", userTask.getExtensionElements());
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
        setInputMapping(element, "Call activity", callActivity.getExtensionElements());
        return element;
    }
}
