package com.zorrodev.bpm.engine.dto;

import java.time.Duration;

/**
 * The worker's say in the retry decision of a failed service task.
 *
 * @param retries      retries still available at this failure, replacing the counter of the service task;
 *                     {@code 0} — no retry; null — use the counter
 * @param retryTimeout delay before this retry; null — use the interval from BPMN
 */
public record RetryOverride(Integer retries, Duration retryTimeout) {

    public static final RetryOverride NONE = new RetryOverride(null, null);

    /** No retry whatever the service task allows: the failure goes straight to an incident. */
    public static final RetryOverride NO_RETRY = new RetryOverride(0, null);
}
