package com.zorrodev.bpm.engine.bpmn.xml.extension;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserTaskExtensionModel {
    private String assignee;
    private String candidateUsers;
    private String candidateGroups;
    private String formKey;
}
