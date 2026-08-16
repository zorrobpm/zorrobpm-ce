package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.camunda.feel.api.EvaluationResult;
import org.camunda.feel.api.FeelEngineApi;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.SimpleScriptContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptServiceImpl implements ScriptService {

    private final ScriptEngine scriptEngine;
    private final FeelEngineApi feelEngineApi;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public Object evaluateScript(String script, List<ProcessVariable> variables) {
        ScriptContext ctx = new SimpleScriptContext();

        if (variables != null && !variables.isEmpty()) {
            for (ProcessVariable variable : variables) {
                ctx.setAttribute(variable.getName(), toContextValue(variable), ScriptContext.ENGINE_SCOPE);
                log.info("Variable {} = {}", variable.getName(), variable.getValue());
            }
        }

        Object result = scriptEngine.eval(script, ctx);
        log.info("Expression result {} = {}", script, result);
        return result;
    }

    @Override
    public Object evaluateExpression(String expression, List<ProcessVariable> variables) {
        Map<String, Object> context = new HashMap<>();
        if (variables != null) {
            for (ProcessVariable variable : variables) {
                context.put(variable.getName(), toContextValue(variable));
            }
        }

        EvaluationResult result = feelEngineApi.evaluateExpression(expression, context);
        if (!result.isSuccess()) {
            throw new IllegalStateException("FEEL expression '" + expression + "' failed: " + result.failure().message());
        }

        log.info("Expression result {} = {}", expression, result.result());
        return result.result();
    }

    private Object toContextValue(ProcessVariable variable) {
        ProcessVariableType type = variable.getType();
        if (type == ProcessVariableType.LONG) {
            return Long.valueOf(variable.getValue());
        } else if (type == ProcessVariableType.BOOLEAN) {
            return Boolean.valueOf(variable.getValue());
        } else if (type == ProcessVariableType.JSON) {
            return objectMapper.readValue(variable.getValue(), Object.class);
        }
        return variable.getValue();
    }

}
