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
public class BpmnSequenceFlowModel {
    @XmlAttribute
    private String id;
    @XmlAttribute
    private String targetRef;
    @XmlAttribute
    private String sourceRef;
    @XmlElement(name = "conditionExpression", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnConditionExpressionModel conditionExpression;
}
