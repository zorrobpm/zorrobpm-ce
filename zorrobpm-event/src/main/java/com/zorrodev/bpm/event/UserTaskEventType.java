package com.zorrodev.bpm.event;

/** A change of a user task published to the {@code zorrobpm.user-task-events} queue. */
public enum UserTaskEventType {
    CREATED,
    ASSIGNED,
    UNASSIGNED,
    COMPLETED,
    CANCELED
}
