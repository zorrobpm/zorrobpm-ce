package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAnyElement;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnBoundaryEventModel {
    @XmlAttribute
    private String id;

    @XmlAttribute
    private String name;

    @XmlAttribute
    private String attachedToRef;

    @XmlAttribute
    private Boolean cancelActivity;

    @XmlElement(name = "outgoing", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<String> outgoing;

    @XmlElement(name = "timerEventDefinition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnTimerEventDefinitionModel timerEventDefinition;

    @XmlElement(name = "errorEventDefinition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnErrorEventDefinitionModel errorEventDefinition;

    /**
     * Every other child element: other event definitions (message, signal, ...) are only
     * detected here so that deployment can reject them.
     */
    @XmlAnyElement
    private List<Object> otherElements;
}
