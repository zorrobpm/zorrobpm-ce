package com.zorrodev.bpm.engine.dto;

import java.time.Instant;

/**
 * @param retries     retries still available
 * @param nextRetryAt due time of the pending retry; null when none is scheduled
 */
public record ServiceTaskRetryState(int retries, Instant nextRetryAt) {
}
