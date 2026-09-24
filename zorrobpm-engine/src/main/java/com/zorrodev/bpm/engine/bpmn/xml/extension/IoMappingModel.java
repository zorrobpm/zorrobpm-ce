package com.zorrodev.bpm.engine.bpmn.xml.extension;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** {@code zeebe:ioMapping}. Only {@code zeebe:input} is bound: outputs are ignored. */
@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
public class IoMappingModel {
    @XmlElement(name = "input", namespace = "http://camunda.org/schema/zeebe/1.0")
    private List<IoInputModel> inputs = new ArrayList<>();
}
