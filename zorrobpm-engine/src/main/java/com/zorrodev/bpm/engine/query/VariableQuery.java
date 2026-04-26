package com.zorrodev.bpm.engine.query;

import com.zorrodev.bpm.contract.model.ProcessVariableType;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class VariableQuery {
    private Integer pageIndex = 0;
    private Integer pageSize = 10;
    private UUID processInstanceId;
    private String name;
    private ProcessVariableType type;
    private String value;
}
