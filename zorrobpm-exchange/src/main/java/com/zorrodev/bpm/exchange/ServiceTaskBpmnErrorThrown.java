package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ServiceTaskBpmnErrorThrown {
    private UUID serviceTaskId;
    private String errorCode;
    private String message;
    private List<ProcessVariable> variables;
}
