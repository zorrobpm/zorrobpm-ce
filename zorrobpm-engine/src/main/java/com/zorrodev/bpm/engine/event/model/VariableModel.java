package com.zorrodev.bpm.engine.event.model;

import com.zorrodev.bpm.contract.model.ProcessVariableType;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class VariableModel {
    private UUID id;
    private UUID processInstanceId;
    private String name;
    private String value;
    private ProcessVariableType type;
}
