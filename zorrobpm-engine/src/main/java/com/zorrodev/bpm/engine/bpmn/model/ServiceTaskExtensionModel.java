package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

@Getter
@Setter
public class ServiceTaskExtensionModel {
    private String job;
    /** Retries after the first attempt before an incident is created. */
    private int retries;
    /** Delay before each retry. */
    private Duration retryTimeout = Duration.ZERO;
}
