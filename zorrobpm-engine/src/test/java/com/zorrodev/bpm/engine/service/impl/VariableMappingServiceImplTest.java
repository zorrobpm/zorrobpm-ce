package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.exception.VariableMappingException;
import com.zorrodev.bpm.engine.bpmn.model.VariableMappingModel;
import com.zorrodev.bpm.engine.bpmn.model.VariableMappingModel.Mapping;
import com.zorrodev.bpm.engine.service.VariableMappingService;
import com.zorrodev.bpm.engine.service.VariableMappingService.Kind;
import org.camunda.feel.api.FeelEngineBuilder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.script.ScriptEngineManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariableMappingServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VariableMappingService service = new VariableMappingServiceImpl(
        new ScriptServiceImpl(new ScriptEngineManager().getEngineByName("feel"), FeelEngineBuilder.forJava().build(), objectMapper),
        objectMapper);

    private final List<ProcessVariable> context = List.of(
        variable("order", ProcessVariableType.JSON, "{\"total\":100,\"items\":[\"a\",\"b\"],\"price\":2.5}"),
        variable("vip", ProcessVariableType.BOOLEAN, "true"),
        variable("count", ProcessVariableType.LONG, "7"),
        variable("name", ProcessVariableType.STRING, "Alice"));

    @Test
    void typesResults() {
        List<ProcessVariable> result = service.evaluate("charge", Kind.INPUT, mapping(
            new Mapping("amount", "order.total", true),
            new Mapping("items", "order.items", true),
            new Mapping("vip", "vip", true),
            new Mapping("share", "order.total / 3", true),
            new Mapping("absent", "order.missing", true),
            new Mapping("currency", "KZT", false),
            new Mapping("ctx", "{a: 1, b: name}", true),
            new Mapping("greeting", "\"Hi, \" + name", true),
            new Mapping("bigSum", "count * 1000000000000", true),
            new Mapping("price", "order.price", true),
            new Mapping("day", "date(\"2026-09-24\")", true),
            new Mapping("wait", "duration(\"PT30S\")", true)),
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
        List<ProcessVariable> result = service.evaluate("charge", Kind.INPUT, mapping(
            new Mapping("a", "1", true),
            new Mapping("b", "a + 1", true)), List.of());

        // FEEL yields null for a name it does not know: 'a' is not visible to 'b'.
        assertThat(result).extracting(ProcessVariable::getName, ProcessVariable::getType, ProcessVariable::getValue).containsExactly(
            org.assertj.core.groups.Tuple.tuple("a", ProcessVariableType.LONG, "1"),
            org.assertj.core.groups.Tuple.tuple("b", ProcessVariableType.JSON, "null"));
    }

    @Test
    void assertionFailureBecomesVariableMappingException() {
        assertThatThrownBy(() -> service.evaluate("charge", Kind.INPUT, mapping(
            new Mapping("amount", "assert(order.total, order.total != null)", true)), List.of()))
            .isInstanceOf(VariableMappingException.class)
            .hasMessageContaining("Input 'amount' of 'charge'");
    }

    @Test
    void contextIsNotChanged() {
        List<ProcessVariable> before = List.copyOf(context);
        service.evaluate("charge", Kind.INPUT, mapping(new Mapping("amount", "order.total", true)), context);
        assertThat(context).containsExactlyElementsOf(before);
    }

    @Test
    void feelFailureBecomesVariableMappingException() {
        assertThatThrownBy(() -> service.evaluate("charge", Kind.INPUT, mapping(new Mapping("x", "order.total +", true)), context))
            .isInstanceOf(VariableMappingException.class)
            .hasMessageContaining("Input 'x' of 'charge'")
            .hasMessageContaining("order.total +");
    }

    @Test
    void resultContextPutsTheResultOverTheInstanceVariables() {
        List<ProcessVariable> ctx = VariableMappingService.resultContext(
            List.of(variable("order", ProcessVariableType.JSON, "{\"total\":100}"), variable("fee", ProcessVariableType.LONG, "5")),
            List.of(variable("result", ProcessVariableType.JSON, "{\"transactionId\":\"T-1\"}"), variable("fee", ProcessVariableType.LONG, "7")));

        assertThat(ctx).extracting(ProcessVariable::getName, ProcessVariable::getValue)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("order", "{\"total\":100}"),
                org.assertj.core.groups.Tuple.tuple("fee", "7"),
                org.assertj.core.groups.Tuple.tuple("result", "{\"transactionId\":\"T-1\"}"));

        List<ProcessVariable> out = service.evaluate("charge", Kind.OUTPUT, mapping(
            new Mapping("paymentId", "result.transactionId", true),
            new Mapping("charged", "order.total + fee", true)), ctx);
        assertThat(out).extracting(ProcessVariable::getName, ProcessVariable::getType, ProcessVariable::getValue).containsExactly(
            org.assertj.core.groups.Tuple.tuple("paymentId", ProcessVariableType.STRING, "T-1"),
            org.assertj.core.groups.Tuple.tuple("charged", ProcessVariableType.LONG, "107"));
    }

    @Test
    void outputFailureNamesTheOutput() {
        assertThatThrownBy(() -> service.evaluate("charge", Kind.OUTPUT, mapping(
            new Mapping("paymentId", "assert(result.transactionId, result.transactionId != null)", true)), List.of()))
            .isInstanceOf(VariableMappingException.class)
            .hasMessageContaining("Output 'paymentId' of 'charge'");
    }

    private static VariableMappingModel mapping(Mapping... inputs) {
        return new VariableMappingModel(List.of(inputs));
    }

    private static ProcessVariable variable(String name, ProcessVariableType type, String value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(type);
        variable.setValue(value);
        return variable;
    }
}
