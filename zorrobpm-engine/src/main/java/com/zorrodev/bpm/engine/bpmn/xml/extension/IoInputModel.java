package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/** {@code zeebe:input source target}. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class IoInputModel {
    @XmlAttribute
    private String source;
    @XmlAttribute
    private String target;
}
