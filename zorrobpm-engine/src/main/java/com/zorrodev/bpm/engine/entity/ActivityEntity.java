package com.zorrodev.bpm.engine.entity;

import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
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
@Table(name = "activities")
public class ActivityEntity {
    @Id
    private UUID id;
    private UUID processInstanceId;
    private String bpmnElementId;
    private Instant createdAt;
    private Instant completedAt;
    @Enumerated(EnumType.STRING)
    private BpmnElementType type;
    @Enumerated(EnumType.STRING)
    private ActivityStatus status;
    private UUID token;
}
