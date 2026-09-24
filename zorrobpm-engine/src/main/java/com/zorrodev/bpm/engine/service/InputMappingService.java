package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.InputMappingModel;
import com.zorrodev.bpm.engine.exception.InputMappingException;

import java.util.List;

/** Evaluates the input mapping of an element: what the executor of the step sees. */
public interface InputMappingService {

    /**
     * Evaluates every input of the mapping against the same context, in declaration order.
     * The context is not changed and inputs do not see each other.
     *
     * @throws InputMappingException when an expression fails or its result cannot be represented
     */
    List<ProcessVariable> evaluate(String elementId, InputMappingModel mapping, List<ProcessVariable> context);
}
