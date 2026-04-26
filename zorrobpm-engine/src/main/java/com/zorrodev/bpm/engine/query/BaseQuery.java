package com.zorrodev.bpm.engine.query;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class BaseQuery {
    private UUID id;
    private Integer pageIndex = 0;
    private Integer pageSize = 10;
}
