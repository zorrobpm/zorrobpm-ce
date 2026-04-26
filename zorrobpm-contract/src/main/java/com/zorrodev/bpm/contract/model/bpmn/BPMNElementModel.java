package com.zorrodev.bpm.contract.model.bpmn;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;
import lombok.Setter;

import static com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY;
import static com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME;

@Getter
@Setter
@JsonTypeInfo(use = NAME, include = PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value=BPMNServiceTaskModel.class, name = "SERVICE_TASK"),
    @JsonSubTypes.Type(value= BPMNUserTaskModel.class, name = "USER_TASK")
})
public class BPMNElementModel {
    private String id;
    private String name;
    private String type;
}
