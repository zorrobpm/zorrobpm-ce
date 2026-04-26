package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnTimerEventDefinitionModel {
    @XmlAttribute
    private String id;

    @XmlElement(name = "timeDate", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private String timeDate;

    @XmlElement(name = "timeDuration", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private String timeDuration;
}
