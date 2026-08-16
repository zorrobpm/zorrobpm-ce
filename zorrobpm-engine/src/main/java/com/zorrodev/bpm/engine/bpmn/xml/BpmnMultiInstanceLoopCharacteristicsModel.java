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
public class BpmnMultiInstanceLoopCharacteristicsModel {
    @XmlAttribute
    private Boolean isSequential;
    @XmlElement(name = "extensionElements", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private ExtensionElements extensionElements;
    @XmlElement(name = "completionCondition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnConditionExpressionModel completionCondition;
}
