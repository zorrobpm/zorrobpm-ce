package com.zorrodev.bpm.engine.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "user_task_candidates")
public class UserTaskCandidateEntity {
    @Id
    private UUID id;
    private UUID taskId;
    @Enumerated(EnumType.STRING)
    private UserTaskCandidateType candidateType;
    private String candidateValue;
}
