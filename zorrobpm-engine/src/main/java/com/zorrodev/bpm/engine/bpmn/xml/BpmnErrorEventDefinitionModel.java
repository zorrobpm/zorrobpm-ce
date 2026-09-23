package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnErrorEventDefinitionModel {
    @XmlAttribute
    private String id;

    /** Id of a root {@code bpmn:error}; none — the event catches any error code. */
    @XmlAttribute
    private String errorRef;
}
