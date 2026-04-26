package com.zorrodev.bpm.engine.event;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ProcessInstanceCreated {
    private UUID processDefinitionId;
    private UUID processInstanceId;
}
