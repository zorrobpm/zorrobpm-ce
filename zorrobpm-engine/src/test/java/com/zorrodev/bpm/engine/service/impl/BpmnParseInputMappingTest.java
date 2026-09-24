package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.BpmnParseException;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.VariableMappingModel;
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
        VariableMappingModel mapping = parse(kind, """
            <zeebe:ioMapping>
              <zeebe:input source="=order.total" target="amount" />
              <zeebe:input source="KZT" target="currency" />
            </zeebe:ioMapping>
            """).getExtensions().getInputMapping();

        assertThat(mapping.mappings()).containsExactly(
            new VariableMappingModel.Mapping("amount", "order.total", true),
            new VariableMappingModel.Mapping("currency", "KZT", false));
    }

    @Test
    void serviceTaskWithoutTaskDefinitionStillGetsTheMapping() {
        BpmnElementModel element = parse("serviceTask", "", """
            <zeebe:ioMapping><zeebe:input source="=1" target="one" /></zeebe:ioMapping>
            """);

        assertThat(element.getExtensions().getServiceTaskExtension()).isNull();
        assertThat(element.getExtensions().getInputMapping().mappings()).hasSize(1);
    }

    @Test
    void noMappingWithoutIoMapping() {
        assertThat(parse("serviceTask", "").getExtensions().getInputMapping()).isNull();
        assertThat(parse("serviceTask", "").getExtensions().getOutputMapping()).isNull();
        assertThat(parse("userTask", "").getExtensions()).isNull();
        assertThat(parse("callActivity", "").getExtensions().getInputMapping()).isNull();
    }

    @Test
    void emptyIoMappingIsNoMapping() {
        assertThat(parse("userTask", "<zeebe:ioMapping />").getExtensions().getInputMapping()).isNull();
    }

    @Test
    void outputsDoNotAffectInputs() {
        BpmnElementModel element = parse("serviceTask", """
            <zeebe:ioMapping>
              <zeebe:input source="=a" target="b" />
              <zeebe:output source="=result" target="paid" />
            </zeebe:ioMapping>
            """);

        assertThat(element.getExtensions().getInputMapping().mappings()).containsExactly(new VariableMappingModel.Mapping("b", "a", true));
        assertThat(element.getExtensions().getOutputMapping().mappings()).containsExactly(new VariableMappingModel.Mapping("paid", "result", true));
        BpmnElementModel outputsOnly = parse("serviceTask", """
            <zeebe:ioMapping><zeebe:output source="=result" target="paid" /></zeebe:ioMapping>
            """);
        assertThat(outputsOnly.getExtensions().getInputMapping()).isNull();
        assertThat(outputsOnly.getExtensions().getOutputMapping().mappings()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"serviceTask", "userTask", "callActivity"})
    void parsesExpressionAndLiteralOutputs(String kind) {
        VariableMappingModel mapping = parse(kind, """
            <zeebe:ioMapping>
              <zeebe:output source="=result.transactionId" target="paymentId" />
              <zeebe:output source="done" target="stage" />
            </zeebe:ioMapping>
            """).getExtensions().getOutputMapping();

        assertThat(mapping.mappings()).containsExactly(
            new VariableMappingModel.Mapping("paymentId", "result.transactionId", true),
            new VariableMappingModel.Mapping("stage", "done", false));
    }

    @Test
    void inputAndOutputMayShareATarget() {
        BpmnElementModel element = parse("serviceTask", """
            <zeebe:ioMapping>
              <zeebe:input source="=order.total" target="amount" />
              <zeebe:output source="=amount * 2" target="amount" />
            </zeebe:ioMapping>
            """);

        assertThat(element.getExtensions().getInputMapping().mappings()).hasSize(1);
        assertThat(element.getExtensions().getOutputMapping().mappings()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "missing source   | <zeebe:output target=\"paymentId\" />                                                             | has no source",
        "missing target   | <zeebe:output source=\"=1\" />                                                                    | without target",
        "duplicate target | <zeebe:output source=\"=1\" target=\"paymentId\" /><zeebe:output source=\"=2\" target=\"paymentId\" /> | is declared twice",
    })
    void rejectsInvalidOutputs(String name, String outputs, String reason) {
        for (String kind : new String[] {"serviceTask", "userTask", "callActivity"}) {
            assertThatThrownBy(() -> parse(kind, "<zeebe:ioMapping>" + outputs + "</zeebe:ioMapping>"))
                .isInstanceOf(BpmnParseException.class)
                .hasMessageContaining("'step'")
                .hasMessageContaining("output")
                .hasMessageContaining(reason.equals("without target") ? "output" : "'paymentId'")
                .hasMessageContaining(reason);
        }
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
