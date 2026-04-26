package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BpmnStartEventModel extends BpmnBaseElementModel {
    @XmlElement(name = "messageEventDefinition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnMessageEventDefinitionModel messageEventDefinition;

    @XmlElement(name = "timerEventDefinition", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private BpmnTimerEventDefinitionModel timerEventDefinition;

    @XmlElement(name = "extensionElements", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private ExtensionElements extensionElements;
}
