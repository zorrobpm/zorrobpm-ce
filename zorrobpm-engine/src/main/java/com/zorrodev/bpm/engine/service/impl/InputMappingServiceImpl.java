package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.bpmn.model.InputMappingModel;
import com.zorrodev.bpm.engine.exception.InputMappingException;
import com.zorrodev.bpm.engine.service.InputMappingService;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InputMappingServiceImpl implements InputMappingService {

    private final ScriptService scriptService;
    private final ObjectMapper objectMapper;

    @Override
    public List<ProcessVariable> evaluate(String elementId, InputMappingModel mapping, List<ProcessVariable> context) {
        List<ProcessVariable> result = new ArrayList<>(mapping.inputs().size());
        for (InputMappingModel.Input input : mapping.inputs()) {
            try {
                result.add(input.expression()
                    ? toVariable(input.target(), scriptService.evaluateExpression(input.source(), context))
                    : variable(input.target(), ProcessVariableType.STRING, input.source()));
            } catch (RuntimeException e) {
                throw new InputMappingException("Input '" + input.target() + "' of '" + elementId + "': " + e.getMessage(), e);
            }
        }
        return result;
    }

    /**
     * Types a FEEL result: string, whole number and boolean get their own types, date/time values
     * their ISO-8601 text, everything else (context, list, fraction, null) is JSON.
     */
    private ProcessVariable toVariable(String name, Object value) {
        if (value instanceof String s) {
            return variable(name, ProcessVariableType.STRING, s);
        }
        if (value instanceof Boolean b) {
            return variable(name, ProcessVariableType.BOOLEAN, b.toString());
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return variable(name, ProcessVariableType.LONG, String.valueOf(((Number) value).longValue()));
        }
        if (value instanceof BigInteger n && n.bitLength() < 64) {
            return variable(name, ProcessVariableType.LONG, n.toString());
        }
        if (value instanceof BigDecimal d && isWholeLong(d)) {
            return variable(name, ProcessVariableType.LONG, d.toBigIntegerExact().toString());
        }
        if ((value instanceof Double || value instanceof Float) && isWholeLong(BigDecimal.valueOf(((Number) value).doubleValue()))) {
            return variable(name, ProcessVariableType.LONG, String.valueOf(((Number) value).longValue()));
        }
        if (value instanceof TemporalAccessor || value instanceof TemporalAmount) {
            return variable(name, ProcessVariableType.STRING, value.toString());
        }
        return variable(name, ProcessVariableType.JSON, objectMapper.writeValueAsString(value));
    }

    private static boolean isWholeLong(BigDecimal d) {
        if (d.stripTrailingZeros().scale() > 0) {
            return false;
        }
        BigInteger i = d.toBigIntegerExact();
        return i.bitLength() < 64;
    }

    private static ProcessVariable variable(String name, ProcessVariableType type, String value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(type);
        variable.setValue(value);
        return variable;
    }
}
