package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BpmnParseServiceTaskRetriesTest {

    private final BpmnParseService service = new BpmnParseServiceImpl();

    @Test
    void parsesRetriesAndTimeout() {
        ServiceTaskExtensionModel extension = charge("retries=\"3\"",
            "<zeebe:properties><zeebe:property name=\"retryTimeout\" value=\"PT30S\" /></zeebe:properties>");

        assertThat(extension.getJob()).isEqualTo("charge");
        assertThat(extension.getRetries()).isEqualTo(3);
        assertThat(extension.getRetryTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void defaultsToNoRetries() {
        ServiceTaskExtensionModel extension = charge("", "");

        assertThat(extension.getRetries()).isZero();
        assertThat(extension.getRetryTimeout()).isEqualTo(Duration.ZERO);
    }

    @Test
    void otherPropertiesDoNotSetTheTimeout() {
        ServiceTaskExtensionModel extension = charge("retries=\"1\"",
            "<zeebe:properties><zeebe:property name=\"owner\" value=\"billing\" /></zeebe:properties>");

        assertThat(extension.getRetries()).isEqualTo(1);
        assertThat(extension.getRetryTimeout()).isEqualTo(Duration.ZERO);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "negative retries       | retries=\"-1\"  | PT30S       | retries must not be negative",
        "non-integer retries    | retries=\"many\"| PT30S       | is not an integer",
        "free-text timeout      | retries=\"3\"   | 30 секунд   | is not an ISO-8601 duration",
        "negative timeout       | retries=\"3\"   | -PT1S       | retryTimeout must not be negative",
    })
    void rejectsInvalidSettings(String name, String retries, String timeout, String reason) {
        assertThatThrownBy(() -> charge(retries,
            "<zeebe:properties><zeebe:property name=\"retryTimeout\" value=\"" + timeout + "\" /></zeebe:properties>"))
            .isInstanceOf(BpmnParseException.class)
            .hasMessageContaining("'charge'")
            .hasMessageContaining(reason);
    }

    private ServiceTaskExtensionModel charge(String retriesAttribute, String properties) {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="d" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="p" isExecutable="true">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:serviceTask id="charge">
                  <bpmn:extensionElements><zeebe:taskDefinition type="charge" %s />%s</bpmn:extensionElements>
                  <bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="charge" />
                <bpmn:sequenceFlow id="f2" sourceRef="charge" targetRef="end" />
              </bpmn:process>
            </bpmn:definitions>
            """.formatted(retriesAttribute, properties);
        return service.parse(xml).getElement("charge").getExtensions().getServiceTaskExtension();
    }
}
