package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TimerEventExtensionModel {
    private TimerEventType type;
    /** The raw value of timeDate / timeDuration / timeCycle: an ISO-8601 literal or a FEEL expression with a leading "=". */
    private String expression;
}
