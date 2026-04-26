package com.zorrodev.bpm.engine.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class Token {
    private UUID id;
    private UUID parentId;
}
