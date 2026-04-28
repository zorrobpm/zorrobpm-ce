package com.zorrodev.bpm.engine.entity;

import com.zorrodev.bpm.contract.model.ProcessVariableType;
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
@Table(name = "variables")
public class ProcessVariableEntity {
    @Id
    private UUID id;
    private UUID processInstanceId;
    private String name;
    private String textValue;
    @Enumerated(EnumType.STRING)
    private ProcessVariableType type;
}
