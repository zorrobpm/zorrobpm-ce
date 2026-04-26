package com.zorrodev.bpm.engine.bpmn.xml;

import com.zorrodev.bpm.engine.bpmn.xml.extension.AssignmentDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.CalledElementModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.FormDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.TaskDefinitionModel;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class ExtensionElements {
    @XmlElement(name = "taskDefinition", namespace = "http://camunda.org/schema/zeebe/1.0")
    private TaskDefinitionModel taskDefinition;
    @XmlElement(name = "assignmentDefinition", namespace = "http://camunda.org/schema/zeebe/1.0")
    private AssignmentDefinitionModel assignmentDefinition;
    @XmlElement(name = "formDefinition", namespace = "http://camunda.org/schema/zeebe/1.0")
    private FormDefinitionModel formDefinition;
    @XmlElement(name = "properties", namespace = "http://camunda.org/schema/zeebe/1.0")
    private PropertiesModel properties;
    @XmlElement(name = "calledElement", namespace = "http://camunda.org/schema/zeebe/1.0")
    private CalledElementModel calledElement;
}
