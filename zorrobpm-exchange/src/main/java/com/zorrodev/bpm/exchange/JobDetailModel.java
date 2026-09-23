package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

@Getter
@Setter
public class JobDetailModel {
    private UUID serviceTaskId;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String serviceTaskKey;
    private String job;
    /** Retries still available for this service task if this attempt fails. */
    private Integer retries;
    private Map<String, ProcessVariable> variables;
}
