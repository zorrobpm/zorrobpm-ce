package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class ProcessDefinition {
    private UUID id;
    private String key;
    private Integer version;
    private String name;
    private String sha256;
    private Instant createdAt;
    private String startFormKey;
}
