package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "timers")
public class TimerEntity {
    @Id
    private UUID id;
    private UUID processInstanceId;
    @Enumerated(EnumType.STRING)
    private TimerKind kind = TimerKind.BOUNDARY;
    /** The host activity the timer belongs to. */
    private UUID activityId;
    /** The boundary event; for a retry — the service task. */
    private String bpmnElementId;
    private Instant dueAt;
    @Enumerated(EnumType.STRING)
    private TimerStatus status;
    /** ISO-8601 duration between firings of a cycle; null for a one-shot timer. */
    private String cycleInterval;
    /** Firings left after the next one; null for an unbounded cycle or a one-shot timer. */
    private Integer remainingRepetitions;
    private Instant createdAt;
    private Instant completedAt;
}
