package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@XmlRootElement(name = "definitions")
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnDefinitionsModel {
    @XmlAttribute
    private String id;
    @XmlElement(name = "process", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnProcessDefinitionModel process;
    @XmlAttribute(namespace = "http://camunda.org/schema/modeler/1.0", name = "executionPlatformVersion")
    private String executionPlatformVersion;
}
