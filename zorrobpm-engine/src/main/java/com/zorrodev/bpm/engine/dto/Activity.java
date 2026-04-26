package com.zorrodev.bpm.engine.dto;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class Activity {
    private UUID id;
    private UUID processInstanceId;
    private UUID token;
    private String bpmnElementId;
    private BpmnElementType type;
    private ActivityStatus status;
    private Instant createdAt;
    private Instant completedAt;
}
