package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class AssignmentDefinitionModel {
    @XmlAttribute
    private String assignee;
    @XmlAttribute
    private String candidateGroups;
    @XmlAttribute
    private String candidateUsers;
}
