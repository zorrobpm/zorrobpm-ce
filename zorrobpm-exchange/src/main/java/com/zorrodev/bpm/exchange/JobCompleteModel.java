package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class JobCompleteModel {
    private UUID serviceTaskId;
    private List<ProcessVariable> variables;
}
