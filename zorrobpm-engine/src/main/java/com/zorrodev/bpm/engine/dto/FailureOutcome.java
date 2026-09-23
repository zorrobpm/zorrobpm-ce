package com.zorrodev.bpm.engine.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * What a service task failure led to: an open incident, or a retry scheduled instead of it.
 *
 * @param incidentId  the open incident; null while a retry is scheduled
 * @param retries     retries still available
 * @param nextRetryAt due time of the scheduled retry; null when there is none
 */
public record FailureOutcome(UUID incidentId, int retries, Instant nextRetryAt) {
}
