package com.zorrodev.bpm.engine.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Boundary timers: the engine's time source and the scheduler that runs the timer poller.
 * Settings: {@code zorrobpm.timers.enabled} (default true), {@code zorrobpm.timers.poll-interval}
 * (default 1s), {@code zorrobpm.timers.batch-size} (default 100).
 */
@Configuration
@EnableScheduling
public class TimerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock engineClock() {
        return Clock.systemUTC();
    }
}
