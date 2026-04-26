package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ServiceTaskReplyMessage {
    private UUID serviceTaskId;
    private List<ProcessVariable> variables;
}
