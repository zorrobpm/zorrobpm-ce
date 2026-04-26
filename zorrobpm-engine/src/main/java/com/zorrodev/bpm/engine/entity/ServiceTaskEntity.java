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
}
