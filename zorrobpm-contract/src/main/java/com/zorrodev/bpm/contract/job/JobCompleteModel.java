package com.zorrodev.bpm.contract.job;

import com.zorrodev.bpm.contract.model.ProcessVariable;
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
