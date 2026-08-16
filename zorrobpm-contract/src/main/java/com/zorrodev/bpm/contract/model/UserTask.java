package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class UserTask {
    private UUID id;
    private String code;
    private String name;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String formKey;
    private Instant createdAt;
    private Instant completedAt;
    private String assignee;
    private List<String> candidateGroups = new ArrayList<>();
    private List<String> candidateUsers = new ArrayList<>();
    private Integer loopIndex;
    private Integer loopTotal;
    private String loopItem;
}
