package com.zorrodev.bpm.engine.entity;

/** What a timer does when it fires. */
public enum TimerKind {
    /** A boundary timer event of the host activity. */
    BOUNDARY,
    /** Re-queues the job of a failed service task. */
    RETRY
}
