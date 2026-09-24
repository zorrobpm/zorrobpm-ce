package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.InputMappingModel;
import com.zorrodev.bpm.engine.bpmn.model.InputMappingModel.Input;
import com.zorrodev.bpm.engine.exception.InputMappingException;
import com.zorrodev.bpm.engine.service.InputMappingService;
import org.camunda.feel.api.FeelEngineBuilder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.script.ScriptEngineManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputMappingServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InputMappingService service = new InputMappingServiceImpl(
        new ScriptServiceImpl(new ScriptEngineManager().getEngineByName("feel"), FeelEngineBuilder.forJava().build(), objectMapper),
        objectMapper);

    private final List<ProcessVariable> context = List.of(
        variable("order", ProcessVariableType.JSON, "{\"total\":100,\"items\":[\"a\",\"b\"],\"price\":2.5}"),
        variable("vip", ProcessVariableType.BOOLEAN, "true"),
        variable("count", ProcessVariableType.LONG, "7"),
        variable("name", ProcessVariableType.STRING, "Alice"));

    @Test
    void typesResults() {
        List<ProcessVariable> result = service.evaluate("charge", mapping(
            new Input("amount", "order.total", true),
            new Input("items", "order.items", true),
            new Input("vip", "vip", true),
            new Input("share", "order.total / 3", true),
            new Input("absent", "order.missing", true),
            new Input("currency", "KZT", false),
            new Input("ctx", "{a: 1, b: name}", true),
            new Input("greeting", "\"Hi, \" + name", true),
            new Input("bigSum", "count * 1000000000000", true),
            new Input("price", "order.price", true),
            new Input("day", "date(\"2026-09-24\")", true),
            new Input("wait", "duration(\"PT30S\")", true)),
            context);

        assertThat(result).extracting(ProcessVariable::getName, ProcessVariable::getType, ProcessVariable::getValue).containsExactly(
            org.assertj.core.groups.Tuple.tuple("amount", ProcessVariableType.LONG, "100"),
            org.assertj.core.groups.Tuple.tuple("items", ProcessVariableType.JSON, "[\"a\",\"b\"]"),
            org.assertj.core.groups.Tuple.tuple("vip", ProcessVariableType.BOOLEAN, "true"),
            org.assertj.core.groups.Tuple.tuple("share", ProcessVariableType.JSON, result.get(3).getValue()),
            org.assertj.core.groups.Tuple.tuple("absent", ProcessVariableType.JSON, "null"),
            org.assertj.core.groups.Tuple.tuple("currency", ProcessVariableType.STRING, "KZT"),
            org.assertj.core.groups.Tuple.tuple("ctx", ProcessVariableType.JSON, "{\"a\":1,\"b\":\"Alice\"}"),
            org.assertj.core.groups.Tuple.tuple("greeting", ProcessVariableType.STRING, "Hi, Alice"),
            org.assertj.core.groups.Tuple.tuple("bigSum", ProcessVariableType.LONG, "7000000000000"),
            org.assertj.core.groups.Tuple.tuple("price", ProcessVariableType.JSON, "2.5"),
            org.assertj.core.groups.Tuple.tuple("day", ProcessVariableType.STRING, "2026-09-24"),
            org.assertj.core.groups.Tuple.tuple("wait", ProcessVariableType.STRING, "PT30S"));
        assertThat(Double.parseDouble(result.get(3).getValue())).isCloseTo(33.333, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void inputsAreIndependent() {
        List<ProcessVariable> result = service.evaluate("charge", mapping(
            new Input("a", "1", true),
            new Input("b", "a + 1", true)), List.of());

        // FEEL yields null for a name it does not know: 'a' is not visible to 'b'.
        assertThat(result).extracting(ProcessVariable::getName, ProcessVariable::getType, ProcessVariable::getValue).containsExactly(
            org.assertj.core.groups.Tuple.tuple("a", ProcessVariableType.LONG, "1"),
            org.assertj.core.groups.Tuple.tuple("b", ProcessVariableType.JSON, "null"));
    }

    @Test
    void assertionFailureBecomesInputMappingException() {
        assertThatThrownBy(() -> service.evaluate("charge", mapping(
            new Input("amount", "assert(order.total, order.total != null)", true)), List.of()))
            .isInstanceOf(InputMappingException.class)
            .hasMessageContaining("Input 'amount' of 'charge'");
    }

    @Test
    void contextIsNotChanged() {
        List<ProcessVariable> before = List.copyOf(context);
        service.evaluate("charge", mapping(new Input("amount", "order.total", true)), context);
        assertThat(context).containsExactlyElementsOf(before);
    }

    @Test
    void feelFailureBecomesInputMappingException() {
        assertThatThrownBy(() -> service.evaluate("charge", mapping(new Input("x", "order.total +", true)), context))
            .isInstanceOf(InputMappingException.class)
            .hasMessageContaining("Input 'x' of 'charge'")
            .hasMessageContaining("order.total +");
    }

    private static InputMappingModel mapping(Input... inputs) {
        return new InputMappingModel(List.of(inputs));
    }

    private static ProcessVariable variable(String name, ProcessVariableType type, String value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(type);
        variable.setValue(value);
        return variable;
    }
}
