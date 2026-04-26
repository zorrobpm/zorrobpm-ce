package com.zorrodev.bpm.engine.dto;



import java.util.UUID;

public record NewIncidentDTO(
    String bpmnElementId,
    UUID processInstanceId,
    UUID processDefinitionId,
    String description
) {}
