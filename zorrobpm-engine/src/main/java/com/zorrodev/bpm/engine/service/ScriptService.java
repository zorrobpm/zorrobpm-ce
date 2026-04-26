package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.event.model.VariableModel;

import java.util.List;

public interface ScriptService {

    Object evaluateScript(String script, List<ProcessVariable> variables);

}
