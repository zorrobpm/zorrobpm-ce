package com.zorrodev.bpm.engine.bpmn.model;

import java.util.List;

/**
 * Input mapping of an element: what the executor of the step (worker, child process, person)
 * sees, computed from the instance variables without changing them.
 *
 * @param inputs the inputs in declaration order, never empty
 */
public record InputMappingModel(List<Input> inputs) {

    /**
     * One input.
     *
     * @param target     the name of the input variable
     * @param source     a FEEL expression without the leading {@code =}, or a string literal
     * @param expression whether {@code source} is a FEEL expression
     */
    public record Input(String target, String source, boolean expression) {
    }
}
