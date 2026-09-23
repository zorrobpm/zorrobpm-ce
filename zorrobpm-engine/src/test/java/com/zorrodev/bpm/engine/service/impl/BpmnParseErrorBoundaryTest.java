package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BpmnParseErrorBoundaryTest {

    private static final String ERRORS = """
        <bpmn:error id="Error_NotFound" errorCode="CUSTOMER_NOT_FOUND" />
        <bpmn:error id="Error_NotFound2" errorCode="CUSTOMER_NOT_FOUND" />
        <bpmn:error id="Error_NoCode" name="no code" />
        <bpmn:error id="Error_Expression" errorCode="=code" />
        """;

    private final BpmnParseService service = new BpmnParseServiceImpl();

    @Test
    void parsesErrorBoundaryEvents() throws IOException {
        BpmnProcessDefinitionModel bpmn = service.parse(Files.readString(Path.of("src/test/files/bpmn-error/error-parse.bpmn")));

        assertThat(bpmn.getErrorBoundaryEvents("lookup")).extracting(BpmnElementModel::getId)
            .containsExactly("lookupAny", "lookupNotFound");
        assertThat(bpmn.getErrorBoundaryEvents("call")).extracting(BpmnElementModel::getId).containsExactly("callNotFound");
        // Timers and error events on the same host stay apart.
        assertThat(bpmn.getBoundaryEvents("lookup")).extracting(BpmnElementModel::getId).containsExactly("lookupDeadline");

        BpmnElementModel notFound = bpmn.getElement("lookupNotFound");
        assertThat(notFound.getType()).isEqualTo(BpmnElementType.ERROR_BOUNDARY_EVENT);
        assertThat(notFound.getName()).isEqualTo("Not found");
        assertThat(notFound.getOutgoing()).containsExactly("toNotFound");
        assertThat(notFound.getExtensions().getBoundaryEventExtension().getAttachedTo()).isEqualTo("lookup");
        assertThat(notFound.getExtensions().getBoundaryEventExtension().isCancelActivity()).isTrue();
        assertThat(notFound.getExtensions().getErrorEventExtension().getErrorCode()).isEqualTo("CUSTOMER_NOT_FOUND");
        assertThat(notFound.getExtensions().getTimerEventExtension()).isNull();

        assertThat(bpmn.getElement("lookupAny").getExtensions().getErrorEventExtension().getErrorCode()).isNull();
        assertThat(bpmn.getElement("callNotFound").getExtensions().getErrorEventExtension().getErrorCode()).isEqualTo("CUSTOMER_NOT_FOUND");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "service task with errorRef    | charge | true | <bpmn:errorEventDefinition errorRef=\"Error_NotFound\" /> | CUSTOMER_NOT_FOUND",
        "service task without errorRef | charge | true | <bpmn:errorEventDefinition />                           | ",
        "call activity                 | call   | true | <bpmn:errorEventDefinition errorRef=\"Error_NotFound\" /> | CUSTOMER_NOT_FOUND",
        "no cancelActivity attribute   | charge |      | <bpmn:errorEventDefinition />                           | ",
    })
    void acceptsErrorBoundaryEvents(String name, String attachedTo, String cancelActivity, String definition, String errorCode) {
        BpmnProcessDefinitionModel bpmn = service.parse(process(boundary("boundary", attachedTo, cancelActivity, definition, true)));

        BpmnElementModel boundary = bpmn.getElement("boundary");
        assertThat(boundary.getType()).isEqualTo(BpmnElementType.ERROR_BOUNDARY_EVENT);
        assertThat(boundary.getExtensions().getErrorEventExtension().getErrorCode()).isEqualTo(errorCode);
        assertThat(bpmn.getErrorBoundaryEvents(attachedTo)).hasSize(1);
        assertThat(bpmn.getBoundaryEvents(attachedTo)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "unknown errorRef          | charge  | true  | <bpmn:errorEventDefinition errorRef=\"Error_Missing\" />    | error 'Error_Missing' does not exist",
        "error without errorCode   | charge  | true  | <bpmn:errorEventDefinition errorRef=\"Error_NoCode\" />     | error 'Error_NoCode' has no errorCode",
        "errorCode expression      | charge  | true  | <bpmn:errorEventDefinition errorRef=\"Error_Expression\" /> | errorCode expressions are not supported",
        "non-interrupting          | charge  | false | <bpmn:errorEventDefinition />                              | always interrupting",
        "on a user task            | approve | true  | <bpmn:errorEventDefinition />                              | only on service tasks and call activities, not on USER_TASK",
        "on a gateway              | xor     | true  | <bpmn:errorEventDefinition />                              | not on EXCLUSIVE_GATEWAY",
        "unknown host              | nothing | true  | <bpmn:errorEventDefinition />                              | does not exist",
    })
    void rejectsInvalidErrorBoundaryEvents(String name, String attachedTo, String cancelActivity, String definition, String reason) {
        assertThatThrownBy(() -> service.parse(process(boundary("boundary", attachedTo, cancelActivity, definition, true))))
            .isInstanceOf(BpmnParseException.class)
            .hasMessageContaining("'boundary'")
            .hasMessageContaining(reason);
    }

    @Test
    void rejectsErrorBoundaryEventWithoutOutgoingFlow() {
        assertThatThrownBy(() -> service.parse(process(boundary("boundary", "charge", "true", "<bpmn:errorEventDefinition />", false))))
            .isInstanceOf(BpmnParseException.class)
            .hasMessageContaining("'boundary'")
            .hasMessageContaining("no outgoing sequence flow");
    }

    @Test
    void rejectsTwoEventsForTheSameCodeOnOneHost() {
        // Two bpmn:error elements with the same code are still the same code.
        String events = boundary("first", "charge", "true", "<bpmn:errorEventDefinition errorRef=\"Error_NotFound\" />", true)
            + boundary("second", "charge", "true", "<bpmn:errorEventDefinition errorRef=\"Error_NotFound2\" />", true);

        assertThatThrownBy(() -> service.parse(process(events)))
            .isInstanceOf(BpmnParseException.class)
            .hasMessageContaining("'second'")
            .hasMessageContaining("for error code 'CUSTOMER_NOT_FOUND'");
    }

    @Test
    void rejectsTwoCatchAllEventsOnOneHost() {
        String events = boundary("first", "charge", "true", "<bpmn:errorEventDefinition />", true)
            + boundary("second", "charge", "true", "<bpmn:errorEventDefinition />", true);

        assertThatThrownBy(() -> service.parse(process(events)))
            .isInstanceOf(BpmnParseException.class)
            .hasMessageContaining("'second'")
            .hasMessageContaining("without an error code");
    }

    @Test
    void acceptsTheSameCodeOnDifferentHosts() {
        String events = boundary("first", "charge", "true", "<bpmn:errorEventDefinition errorRef=\"Error_NotFound\" />", true)
            + boundary("second", "call", "true", "<bpmn:errorEventDefinition errorRef=\"Error_NotFound\" />", true)
            + boundary("third", "charge", "true", "<bpmn:errorEventDefinition />", true);

        BpmnProcessDefinitionModel bpmn = service.parse(process(events));

        assertThat(bpmn.getErrorBoundaryEvents("charge")).extracting(BpmnElementModel::getId).containsExactly("first", "third");
        assertThat(bpmn.getErrorBoundaryEvents("call")).extracting(BpmnElementModel::getId).containsExactly("second");
    }

    private static String boundary(String id, String attachedTo, String cancelActivity, String definition, boolean outgoing) {
        String cancel = cancelActivity == null ? "" : " cancelActivity=\"" + cancelActivity + "\"";
        return "<bpmn:boundaryEvent id=\"%s\"%s attachedToRef=\"%s\">%s%s</bpmn:boundaryEvent>\n"
            .formatted(id, cancel, attachedTo, outgoing ? "<bpmn:outgoing>fb</bpmn:outgoing>" : "", definition);
    }

    private static String process(String boundaryEvents) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="d" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="p" isExecutable="true">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:exclusiveGateway id="xor"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:exclusiveGateway>
                <bpmn:userTask id="approve"><bpmn:incoming>f2</bpmn:incoming><bpmn:outgoing>f3</bpmn:outgoing></bpmn:userTask>
                <bpmn:serviceTask id="charge"><bpmn:incoming>f3</bpmn:incoming><bpmn:outgoing>f4</bpmn:outgoing></bpmn:serviceTask>
                <bpmn:callActivity id="call"><bpmn:incoming>f4</bpmn:incoming><bpmn:outgoing>f5</bpmn:outgoing></bpmn:callActivity>
                <bpmn:endEvent id="end"><bpmn:incoming>f5</bpmn:incoming><bpmn:incoming>fb</bpmn:incoming></bpmn:endEvent>
                %s
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="xor" />
                <bpmn:sequenceFlow id="f2" sourceRef="xor" targetRef="approve" />
                <bpmn:sequenceFlow id="f3" sourceRef="approve" targetRef="charge" />
                <bpmn:sequenceFlow id="f4" sourceRef="charge" targetRef="call" />
                <bpmn:sequenceFlow id="f5" sourceRef="call" targetRef="end" />
                <bpmn:sequenceFlow id="fb" sourceRef="boundary" targetRef="end" />
              </bpmn:process>
              %s
            </bpmn:definitions>
            """.formatted(boundaryEvents, ERRORS);
    }
}
