package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ServiceTaskCompleteData {
    private UUID serviceTaskId;
    private ServiceTaskResultStatus status;
    private String message;
    private String errorCode;
    private String details;
    private List<ProcessVariable> variables;
}
