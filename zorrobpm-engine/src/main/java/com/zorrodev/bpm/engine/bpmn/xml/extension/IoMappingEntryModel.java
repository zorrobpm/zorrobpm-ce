package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import lombok.Getter;
import lombok.Setter;

/** {@code zeebe:input} or {@code zeebe:output}: {@code source} and {@code target}. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class IoMappingEntryModel {
    @XmlAttribute
    private String source;
    @XmlAttribute
    private String target;
}
