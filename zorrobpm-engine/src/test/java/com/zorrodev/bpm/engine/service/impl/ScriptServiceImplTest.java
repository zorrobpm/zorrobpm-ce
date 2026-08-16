package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.camunda.feel.api.FeelEngineBuilder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ScriptServiceImplTest {

    private ScriptService newService() {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("feel");
        return new ScriptServiceImpl(engine, FeelEngineBuilder.forJava().build(), new ObjectMapper());
    }

    private ProcessVariable variable(String name, ProcessVariableType type, String value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(type);
        variable.setValue(value);
        return variable;
    }

    @Test
    void testExpression() {
        ScriptService service = newService();

        ProcessVariable var = variable("x", ProcessVariableType.LONG, "1");

        Boolean result = (Boolean) service.evaluateScript("x = 1", List.of(var));

        assertThat(result).isTrue();

        Boolean result2 = (Boolean) service.evaluateScript("x != 1", List.of(var));

        assertThat(result2).isFalse();
    }

    @Test
    void evaluateExpression_jsonVariable_returnsNumberList() {
        ScriptService service = newService();

        Object result = service.evaluateExpression("items", List.of(variable("items", ProcessVariableType.JSON, "[1,2,3]")));

        assertThat(result).asInstanceOf(InstanceOfAssertFactories.LIST).containsExactly(1L, 2L, 3L);
    }

    @Test
    void evaluateExpression_jsonVariable_returnsStringList() {
        ScriptService service = newService();

        Object result = service.evaluateExpression("names", List.of(variable("names", ProcessVariableType.JSON, "[\"a\",\"b\"]")));

        assertThat(result).asInstanceOf(InstanceOfAssertFactories.LIST).containsExactly("a", "b");
    }

    @Test
    void evaluateExpression_arithmeticOnLongVariable() {
        ScriptService service = newService();

        Object result = service.evaluateExpression("x + 1", List.of(variable("x", ProcessVariableType.LONG, "2")));

        assertThat(result).isEqualTo(3L);
    }

    @Test
    void evaluateExpression_invalidExpression_throws() {
        ScriptService service = newService();

        assertThatThrownBy(() -> service.evaluateExpression("items[", List.of()))
            .isInstanceOf(IllegalStateException.class);
    }
}
