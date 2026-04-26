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
@Table(name = "user_tasks")
public class UserTaskEntity {
    @Id
    private UUID id;
    private String bpmnElementId;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private Instant createdAt;
    private Instant completedAt;
    private String formKey;
    private String assignee;
}
