package com.zorrodev.bpm.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class CreateServiceTaskIncidentDTO {
    private UUID serviceTaskId;
    private String message;
}
