package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ServiceTaskMessage {
    private String job;
    private UUID serviceTaskId;
    private String businessKey;
    private List<ProcessVariable> variables;
}
