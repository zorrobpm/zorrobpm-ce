package com.zorrodev.bpm.contract.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * What a thrown BPMN error led to: the error boundary event that caught it, or an open incident on
 * the service task when nothing did.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class BpmnErrorOutcomeDTO {
    /** Whether an error boundary event caught the error. */
    private boolean caught;
    /** The catching boundary event; null when not caught. */
    private String boundaryEventId;
    /** The process instance of the catching boundary event; null when not caught. */
    private UUID processInstanceId;
    /** The open incident on the service task; null when caught. */
    private UUID incidentId;
}
