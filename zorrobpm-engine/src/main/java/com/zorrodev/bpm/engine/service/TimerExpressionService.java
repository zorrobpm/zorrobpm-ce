package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.dto.TimerSchedule;

import java.time.Instant;
import java.util.List;

public interface TimerExpressionService {

    /**
     * Computes when the timer of a boundary event fires first, and how a cycle repeats.
     *
     * @throws IllegalStateException when the value cannot be evaluated or is not supported; the
     *                               message names the boundary event
     */
    TimerSchedule schedule(BpmnElementModel boundaryEvent, List<ProcessVariable> variables, Instant now);

    /**
     * The firing of a cycle that follows the one due at {@code previousDueAt}.
     */
    Instant next(String cycleInterval, Instant previousDueAt);
}
