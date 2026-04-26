package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class Activity {
    private UUID activityId;
    private UUID processInstanceId;
    private String activityType;
    private String modelElementId;
    private String modelElementType;
    private String modelElementName;
    private Instant createdAt;
    private Instant processedAt;


}
