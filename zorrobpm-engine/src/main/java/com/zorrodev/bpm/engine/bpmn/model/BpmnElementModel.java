package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

@Getter
@Setter
public class BpmnElementModel {
    @Getter
    @Setter
    private String id;
    @Getter
    @Setter
    private String name;
    @Getter
    @Setter
    private BpmnProcessDefinitionModel processDefinition;
    @Getter
    @Setter
    private BpmnElementType type;
    @Getter
    private List<String> outgoing = new LinkedList<>();
    @Getter
    private List<String> incoming = new LinkedList<>();
    @Getter
    @Setter
    private BpmnElementExtensionModel extensions;
}
