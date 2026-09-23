package com.zorrodev.bpm.engine.dto;

import java.time.Instant;

/**
 * When a boundary timer fires first and, for a cycle, how it repeats.
 *
 * @param dueAt                the first firing
 * @param cycleInterval        ISO-8601 duration between firings of a cycle; null for a one-shot timer
 * @param remainingRepetitions firings left after the first one; null for an unbounded cycle or a one-shot timer
 */
public record TimerSchedule(Instant dueAt, String cycleInterval, Integer remainingRepetitions) {

    public static TimerSchedule once(Instant dueAt) {
        return new TimerSchedule(dueAt, null, null);
    }
}
