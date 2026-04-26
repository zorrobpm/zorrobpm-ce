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
@Table(name="process_instances")
public class ProcessInstanceEntity {
    @Id
    private UUID id;
    private UUID processDefinitionId;
    private Instant startedAt;
    private Instant completedAt;
    private UUID parentActivityId;
}
