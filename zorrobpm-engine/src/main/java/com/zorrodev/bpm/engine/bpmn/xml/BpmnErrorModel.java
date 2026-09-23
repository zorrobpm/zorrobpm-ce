package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/**
 * A root {@code bpmn:error}: the code that error events refer to through {@code errorRef}.
 */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnErrorModel {
    @XmlAttribute
    private String id;

    @XmlAttribute
    private String name;

    @XmlAttribute
    private String errorCode;
}
