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
@Table(name="incidents")
public class IncidentEntity {
    @Id
    private UUID id;
    private UUID activityId;
    private String message;
    private Instant createdAt;
    private Instant completedAt;
}
