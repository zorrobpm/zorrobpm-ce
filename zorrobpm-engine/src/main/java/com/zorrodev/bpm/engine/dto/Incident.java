package com.zorrodev.bpm.engine.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class Incident {
    private UUID id;
    private UUID activityId;
    private String message;
    private Instant createdAt;
    private Instant completedAt;
}
