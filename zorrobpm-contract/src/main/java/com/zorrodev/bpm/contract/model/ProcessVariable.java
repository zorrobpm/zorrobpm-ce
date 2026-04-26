package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcessVariable {
    private String name;
    private String value;
    private ProcessVariableType type;
}
