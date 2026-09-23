package com.zorrodev.bpm.contract.dto;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ThrowBpmnErrorDTO {
    /** The code error boundary events are matched against; required. */
    private String errorCode;
    private String message;
    /** Written into the process instance of the catching boundary event; dropped when nothing catches the error. */
    private List<ProcessVariable> variables = new ArrayList<>();
}
