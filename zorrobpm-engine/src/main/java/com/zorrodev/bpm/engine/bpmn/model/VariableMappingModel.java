package com.zorrodev.bpm.engine.bpmn.model;

import java.util.List;

/**
 * An input or output mapping of an element: input — what the executor of the step sees, computed
 * from the instance variables; output — what the step writes into the instance, computed from its
 * result over the instance variables.
 *
 * @param mappings the entries in declaration order, never empty
 */
public record VariableMappingModel(List<Mapping> mappings) {

    /**
     * One entry.
     *
     * @param target     the name of the resulting variable
     * @param source     a FEEL expression without the leading {@code =}, or a string literal
     * @param expression whether {@code source} is a FEEL expression
     */
    public record Mapping(String target, String source, boolean expression) {
    }
}
