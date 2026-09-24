package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.InputMappingModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BpmnParseInputMappingTest {

    private final BpmnParseService service = new BpmnParseServiceImpl();

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"serviceTask", "userTask", "callActivity"})
    void parsesExpressionAndLiteralInputs(String kind) {
        InputMappingModel mapping = parse(kind, """
            <zeebe:ioMapping>
              <zeebe:input source="=order.total" target="amount" />
              <zeebe:input source="KZT" target="currency" />
            </zeebe:ioMapping>
            """).getExtensions().getInputMapping();

        assertThat(mapping.inputs()).containsExactly(
            new InputMappingModel.Input("amount", "order.total", true),
            new InputMappingModel.Input("currency", "KZT", false));
    }

    @Test
    void serviceTaskWithoutTaskDefinitionStillGetsTheMapping() {
        BpmnElementModel element = parse("serviceTask", "", """
            <zeebe:ioMapping><zeebe:input source="=1" target="one" /></zeebe:ioMapping>
            """);

        assertThat(element.getExtensions().getServiceTaskExtension()).isNull();
        assertThat(element.getExtensions().getInputMapping().inputs()).hasSize(1);
    }

    @Test
    void noMappingWithoutIoMapping() {
        assertThat(parse("serviceTask", "").getExtensions().getInputMapping()).isNull();
        assertThat(parse("userTask", "").getExtensions()).isNull();
        assertThat(parse("callActivity", "").getExtensions().getInputMapping()).isNull();
    }

    @Test
    void emptyIoMappingIsNoMapping() {
        assertThat(parse("userTask", "<zeebe:ioMapping />").getExtensions().getInputMapping()).isNull();
    }

    @Test
    void outputsAreIgnored() {
        InputMappingModel mapping = parse("serviceTask", """
            <zeebe:ioMapping>
              <zeebe:input source="=a" target="b" />
              <zeebe:output source="=result" target="paid" />
            </zeebe:ioMapping>
            """).getExtensions().getInputMapping();

        assertThat(mapping.inputs()).containsExactly(new InputMappingModel.Input("b", "a", true));
        assertThat(parse("serviceTask", """
            <zeebe:ioMapping><zeebe:output source="=result" target="paid" /></zeebe:ioMapping>
            """).getExtensions().getInputMapping()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "missing source   | <zeebe:input target=\"amount\" />                                                       | has no source",
        "empty source     | <zeebe:input source=\"\" target=\"amount\" />                                            | has no source",
        "missing target   | <zeebe:input source=\"=1\" />                                                            | without target",
        "blank target     | <zeebe:input source=\"=1\" target=\" \" />                                               | without target",
        "duplicate target | <zeebe:input source=\"=1\" target=\"amount\" /><zeebe:input source=\"=2\" target=\"amount\" /> | is declared twice",
    })
    void rejectsInvalidInputs(String name, String inputs, String reason) {
        for (String kind : new String[] {"serviceTask", "userTask", "callActivity"}) {
            assertThatThrownBy(() -> parse(kind, "<zeebe:ioMapping>" + inputs + "</zeebe:ioMapping>"))
                .isInstanceOf(BpmnParseException.class)
                .hasMessageContaining("'step'")
                .hasMessageContaining(reason.equals("without target") ? "input" : "'amount'")
                .hasMessageContaining(reason);
        }
    }

    private BpmnElementModel parse(String kind, String ioMapping) {
        String definition = switch (kind) {
            case "serviceTask" -> "<zeebe:taskDefinition type=\"charge\" />";
            case "callActivity" -> "<zeebe:calledElement processId=\"child\" />";
            default -> "";
        };
        return parse(kind, definition, ioMapping);
    }

    private BpmnElementModel parse(String kind, String definition, String ioMapping) {
        String extensions = definition.isEmpty() && ioMapping.isEmpty() ? "" :
            "<bpmn:extensionElements>" + definition + ioMapping + "</bpmn:extensionElements>";
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="d" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="p" isExecutable="true">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:%s id="step">
                  %s
                  <bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:%s>
                <bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="step" />
                <bpmn:sequenceFlow id="f2" sourceRef="step" targetRef="end" />
              </bpmn:process>
            </bpmn:definitions>
            """.formatted(kind, extensions, kind);
        return service.parse(xml).getElement("step");
    }
}
