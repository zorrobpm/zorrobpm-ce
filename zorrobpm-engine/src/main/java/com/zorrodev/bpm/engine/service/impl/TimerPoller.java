package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.service.TimerJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks for due boundary timers every {@code zorrobpm.timers.poll-interval}; switched off with
 * {@code zorrobpm.timers.enabled=false} (for example, on nodes that should not run timers).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "zorrobpm.timers.enabled", havingValue = "true", matchIfMissing = true)
public class TimerPoller {

    private final TimerJobService timerJobService;

    @Scheduled(fixedDelayString = "${zorrobpm.timers.poll-interval:1s}")
    public void poll() {
        int fired = timerJobService.fireDueTimers();
        if (fired > 0) {
            log.debug("Fired {} timers", fired);
        }
    }
}
