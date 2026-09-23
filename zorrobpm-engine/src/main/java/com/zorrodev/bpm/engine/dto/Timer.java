package com.zorrodev.bpm.engine.dto;

import com.zorrodev.bpm.engine.entity.TimerStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class Timer {
    private UUID id;
    private UUID processInstanceId;
    private UUID activityId;
    private String bpmnElementId;
    private Instant dueAt;
    private TimerStatus status;
    private String cycleInterval;
    private Integer remainingRepetitions;
}
