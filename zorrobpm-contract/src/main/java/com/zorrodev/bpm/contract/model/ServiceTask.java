package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class ServiceTask {
    private UUID id;
    private String code;
    private String name;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String job;
    private Instant createdAt;
    private Instant completedAt;
    /** Retries still available. */
    private int retries;
    /** Due time of the pending retry; null when none is scheduled. */
    private Instant nextRetryAt;
    /** Code and text of the error the last retry was scheduled for. */
    private String lastErrorCode;
    private String lastErrorMessage;
}
