package com.zorrodev.bpm.engine.entity;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ProcessVariableEntityId {
    private UUID processInstanceId;
    private String name;
}
