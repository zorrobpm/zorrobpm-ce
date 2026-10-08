package com.zorrodev.bpm.exchange;

import java.time.Instant;
import java.util.UUID;

/**
 * One user task event taken from the outbox for publication; {@code payload} is the JSON body as it
 * was written when the event happened.
 */
public record UserTaskEventMessage(UUID eventId, String type, UUID userTaskId,
                                   Instant occurredAt, String payload) {
}
