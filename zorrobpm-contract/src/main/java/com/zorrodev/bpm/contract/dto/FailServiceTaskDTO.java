package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FailServiceTaskDTO {
    private String message;
    private String errorCode;
    private String details;
    /** Retries still available at this failure; overrides the counter of the service task. */
    private Integer retries;
    /** ISO-8601 duration before this retry; overrides the interval from BPMN. */
    private String retryTimeout;
}
