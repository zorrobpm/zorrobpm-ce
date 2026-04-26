package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.ScriptService;
import org.junit.jupiter.api.Test;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ScriptServiceImplTest {

    @Test
    void testExpression() {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("feel");
        ScriptService service = new ScriptServiceImpl(engine);

        ProcessVariable var = new ProcessVariable();
        var.setName("x");
        var.setType(ProcessVariableType.LONG);
        var.setValue("1");

        Boolean result = (Boolean) service.evaluateScript("x = 1", List.of(var));

        assertThat(result).isTrue();

        Boolean result2 = (Boolean) service.evaluateScript("x != 1", List.of(var));

        assertThat(result2).isFalse();
    }
}
