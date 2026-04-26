package com.zorrodev.bpm.engine.bpmn.xml;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class BpmnProcessDefinitionModel {
    @XmlAttribute
    private String id;
    @XmlAttribute
    private String name;
    @XmlAttribute
    private Boolean isExecutable;
    @XmlElement(name = "startEvent", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnStartEventModel> startEvents;
    @XmlElement(name = "endEvent", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnEndEventModel> endEvents;
    @XmlElement(name = "sequenceFlow", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnSequenceFlowModel> flows;
    @XmlElement(name = "serviceTask", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnServiceTaskModel> serviceTasks;
    @XmlElement(name = "userTask", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnUserTaskModel> userTasks;
    @XmlElement(name = "exclusiveGateway", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnExclusiveGatewayModel> exclusiveGateways;
    @XmlElement(name = "parallelGateway", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnParallelGatewayModel> parallelGateways;
    @XmlElement(name = "intermediateCatchEvent", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnIntermediateCatchEventModel> intermediateCatchEvents;
    @XmlElement(name = "intermediateThrowEvent", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnIntermediateThrowEventModel> intermediateThrowEvents;
    @XmlElement(name = "callActivity", namespace = "http://www.omg.org/spec/BPMN/20100524/MODEL")
    private List<BpmnCallActivityModel> callActivities;
}
