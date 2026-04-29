package com.zorrodev.bpm.contract.dto.query;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class UserTaskQuery extends BaseQuery {
    private UUID processInstanceId;
    private String assignee;
    private String candidateGroup;
    private String candidateUser;
    private Boolean completed;
    private Boolean assigned;
}
