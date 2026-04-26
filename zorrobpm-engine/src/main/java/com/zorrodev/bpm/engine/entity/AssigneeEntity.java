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
@Table(name="assignees")
public class
AssigneeEntity {
    @Id
    private UUID id;
    private Instant createdAt;
    private UUID taskId;
    private UUID processInstanceId;
}
