package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class CalledElementModel {
    @XmlAttribute
    private String processId;
    @XmlAttribute
    private String bindingType;
    @XmlAttribute
    private Boolean propagateAllChildVariables;
}
