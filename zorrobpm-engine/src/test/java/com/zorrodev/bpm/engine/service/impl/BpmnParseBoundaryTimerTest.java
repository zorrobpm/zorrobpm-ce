package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventType;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BpmnParseBoundaryTimerTest {

    private static final String DURATION = "<bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>";

    private final BpmnParseService service = new BpmnParseServiceImpl();

    @Test
    void parsesBoundaryTimers() throws IOException {
        BpmnProcessDefinitionModel bpmn = service.parse(Files.readString(Path.of("src/test/files/boundary/boundary-parse.bpmn")));

        assertThat(bpmn.getBoundaryEvents("approve")).extracting(BpmnElementModel::getId)
            .containsExactly("approveRemind", "approveTimeout");
        assertThat(bpmn.getBoundaryEvents("charge")).extracting(BpmnElementModel::getId).containsExactly("chargeDeadline");
        assertThat(bpmn.getBoundaryEvents("call")).extracting(BpmnElementModel::getId).containsExactly("callTimeout");
        assertThat(bpmn.getBoundaryEvents("endEvent")).isEmpty();

        BpmnElementModel timeout = bpmn.getElement("approveTimeout");
        assertThat(timeout.getType()).isEqualTo(BpmnElementType.TIMER_BOUNDARY_EVENT);
        assertThat(timeout.getName()).isEqualTo("1 hour");
        assertThat(timeout.getOutgoing()).containsExactly("flowTimeout");
        assertThat(timeout.getIncoming()).isEmpty();
        assertThat(timeout.getExtensions().getBoundaryEventExtension().getAttachedTo()).isEqualTo("approve");
        assertThat(timeout.getExtensions().getBoundaryEventExtension().isCancelActivity()).isTrue();
        assertTimer(timeout, TimerEventType.DURATION, "PT1H");

        BpmnElementModel remind = bpmn.getElement("approveRemind");
        assertThat(remind.getExtensions().getBoundaryEventExtension().isCancelActivity()).isFalse();
        assertTimer(remind, TimerEventType.CYCLE, "R3/PT10M");

        assertTimer(bpmn.getElement("chargeDeadline"), TimerEventType.DATE, "2026-10-01T09:00:00+05:00");
        assertTimer(bpmn.getElement("callTimeout"), TimerEventType.DURATION, "=duration(\"PT\" + string(minutes) + \"M\")");
    }

    @Test
    void keepsExpressionOfIntermediateTimers() throws IOException {
        BpmnProcessDefinitionModel bpmn = service.parse(Files.readString(Path.of("src/test/files/process-with-events.bpmn")));

        assertTimer(bpmn.getElement("timerCatchEventElement_Duration"), TimerEventType.DURATION, "duration");
        assertTimer(bpmn.getElement("timerCatchEventElement_Date"), TimerEventType.DATE, "date");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "user task, interrupting duration      | approve | true  | <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>",
        "user task, non-interrupting cycle     | approve | false | <bpmn:timerEventDefinition><bpmn:timeCycle>R/PT1H</bpmn:timeCycle></bpmn:timerEventDefinition>",
        "service task, date                    | charge  | true  | <bpmn:timerEventDefinition><bpmn:timeDate>2026-10-01T09:00:00Z</bpmn:timeDate></bpmn:timerEventDefinition>",
        "call activity, non-interrupting       | call    | false | <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>",
        "extension elements with a timer       | approve | true  | <bpmn:extensionElements><zeebe:properties /></bpmn:extensionElements><bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>",
    })
    void acceptsSupportedBoundaryTimers(String name, String attachedTo, String cancelActivity, String definition) {
        BpmnProcessDefinitionModel bpmn = service.parse(process(attachedTo, cancelActivity, definition, true));

        assertThat(bpmn.getElement("boundary").getType()).isEqualTo(BpmnElementType.TIMER_BOUNDARY_EVENT);
        assertThat(bpmn.getBoundaryEvents(attachedTo)).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "signal boundary event       | charge  | true  | <bpmn:signalEventDefinition signalRef=\"s\" />                                                    | only timer and error boundary events",
        "message boundary event      | approve | false | <bpmn:messageEventDefinition messageRef=\"m\" />                                                  | only timer and error boundary events",
        "no event definition         | approve | true  | <bpmn:documentation>none</bpmn:documentation>                                                     | only timer and error boundary events",
        "timer and error together    | charge  | true  | <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition><bpmn:errorEventDefinition /> | only timer and error boundary events",
        "timer on a gateway          | xor     | true  | <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition> | not on EXCLUSIVE_GATEWAY",
        "unknown host                | nothing | true  | <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition> | does not exist",
        "timer without value         | approve | true  | <bpmn:timerEventDefinition />                                                                     | no timeDate, timeDuration or timeCycle",
        "cycle on interrupting timer | approve | true  | <bpmn:timerEventDefinition><bpmn:timeCycle>R3/PT10M</bpmn:timeCycle></bpmn:timerEventDefinition>  | timeCycle is supported only on non-interrupting",
    })
    void rejectsUnsupportedBoundaryEvents(String name, String attachedTo, String cancelActivity, String definition, String reason) {
        assertThatThrownBy(() -> service.parse(process(attachedTo, cancelActivity, definition, true)))
            .isInstanceOf(BpmnParseException.class)
            .hasMessageContaining("'boundary'")
            .hasMessageContaining(reason);
    }

    @Test
    void rejectsBoundaryTimerWithoutOutgoingFlow() {
        assertThatThrownBy(() -> service.parse(process("approve", "true", DURATION, false)))
            .isInstanceOf(BpmnParseException.class)
            .hasMessageContaining("'boundary'")
            .hasMessageContaining("no outgoing sequence flow");
    }

    private static void assertTimer(BpmnElementModel element, TimerEventType type, String expression) {
        TimerEventExtensionModel timer = element.getExtensions().getTimerEventExtension();
        assertThat(timer.getType()).isEqualTo(type);
        assertThat(timer.getExpression()).isEqualTo(expression);
    }

    private static String process(String attachedTo, String cancelActivity, String definition, boolean outgoing) {
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
                <bpmn:boundaryEvent id="boundary" cancelActivity="%s" attachedToRef="%s">%s%s</bpmn:boundaryEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="xor" />
                <bpmn:sequenceFlow id="f2" sourceRef="xor" targetRef="approve" />
                <bpmn:sequenceFlow id="f3" sourceRef="approve" targetRef="charge" />
                <bpmn:sequenceFlow id="f4" sourceRef="charge" targetRef="call" />
                <bpmn:sequenceFlow id="f5" sourceRef="call" targetRef="end" />
                <bpmn:sequenceFlow id="fb" sourceRef="boundary" targetRef="end" />
              </bpmn:process>
            </bpmn:definitions>
            """.formatted(cancelActivity, attachedTo, outgoing ? "<bpmn:outgoing>fb</bpmn:outgoing>" : "", definition);
    }
}
