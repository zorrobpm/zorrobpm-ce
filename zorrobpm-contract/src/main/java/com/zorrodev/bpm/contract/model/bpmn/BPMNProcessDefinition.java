package com.zorrodev.bpm.contract.model.bpmn;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BPMNProcessDefinition {
    private List<BPMNElementModel> elements;
}
