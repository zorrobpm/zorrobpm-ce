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
    /** Retries still available at this failure; overrides the engine's counter. {@code null} — let the engine decide. */
    private Integer retries;
    /** ISO-8601 duration until this retry; overrides the interval from BPMN. {@code null} — use BPMN. */
    private String retryTimeout;
    private List<ProcessVariable> variables;
}
