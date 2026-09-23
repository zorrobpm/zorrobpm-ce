package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "service_tasks")
public class ServiceTaskEntity {
    @Id
    private UUID id;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String bpmnElementId;
    private Instant createdAt;
    private Instant completedAt;
    private Instant canceledAt;
    private String jobType;
    /** Retries still available. */
    private int retries;
    /** Due time of the pending retry; null when none is scheduled. */
    private Instant nextRetryAt;
    /** Code and text of the error the last retry was scheduled for. */
    private String lastErrorCode;
    private String lastErrorMessage;
    /** Until when the job is locked for the gRPC subscription that got it; null when it is not locked. */
    private Instant lockedUntil;
    /** The gRPC subscription holding the lock. */
    private String lockedBy;
}
