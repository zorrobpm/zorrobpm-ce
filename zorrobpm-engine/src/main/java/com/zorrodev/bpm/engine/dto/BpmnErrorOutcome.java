package com.zorrodev.bpm.engine.dto;

import java.util.UUID;

/**
 * What a BPMN error thrown by a service task led to: the error boundary event that caught it, or an
 * incident on the service task when nothing did.
 *
 * @param caught            whether an error boundary event caught the error
 * @param boundaryEventId   the catching boundary event; null when not caught
 * @param processInstanceId the process instance of the catching boundary event; null when not caught
 * @param incidentId        the open incident of an uncaught error; null when caught
 */
public record BpmnErrorOutcome(boolean caught, String boundaryEventId, UUID processInstanceId, UUID incidentId) {
}
