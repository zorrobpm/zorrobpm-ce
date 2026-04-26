package com.zorrodev.bpm.engine.entity;

import com.zorrodev.bpm.contract.model.ProcessVariableType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "variables")
@IdClass(ProcessVariableEntityId.class)
public class ProcessVariableEntity {
    @Id
    private UUID processInstanceId;
    @Id
    private String name;
    private String textValue;
    @Enumerated(EnumType.STRING)
    private ProcessVariableType type;
}
