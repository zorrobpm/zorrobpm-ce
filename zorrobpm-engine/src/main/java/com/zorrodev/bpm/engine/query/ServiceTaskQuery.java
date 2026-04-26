package com.zorrodev.bpm.engine.query;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class ServiceTaskQuery extends BaseQuery {
    private UUID processInstanceId;
    private String jobType;
    private Boolean completed;
}
