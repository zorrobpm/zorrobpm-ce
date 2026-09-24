package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.exception.VariableMappingException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.VariableMappingModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evaluates the input or output mapping of an element. */
public interface VariableMappingService {

    enum Kind {
        INPUT("Input"), OUTPUT("Output");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Evaluates every entry of the mapping against the same context, in declaration order. The
     * context is not changed and entries do not see each other.
     *
     * @throws VariableMappingException when an expression fails or its result cannot be represented
     */
    List<ProcessVariable> evaluate(String elementId, Kind kind, VariableMappingModel mapping, List<ProcessVariable> context);

    /** The context of an output mapping: the result of the step over the instance variables, the result winning by name. */
    static List<ProcessVariable> resultContext(List<ProcessVariable> instanceVariables, List<ProcessVariable> result) {
        Map<String, ProcessVariable> byName = new LinkedHashMap<>();
        for (ProcessVariable v : instanceVariables) {
            byName.put(v.getName(), v);
        }
        for (ProcessVariable v : result) {
            byName.put(v.getName(), v);
        }
        return new ArrayList<>(byName.values());
    }
}
