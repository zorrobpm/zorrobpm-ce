package com.zorrodev.bpm.engine.service;

public interface TimerJobService {

    /**
     * Fires the boundary timers that are due now, each in its own transaction.
     *
     * @return how many timers fired
     */
    int fireDueTimers();
}
