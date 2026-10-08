package com.zorrodev.bpm.event;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The body of a message of the {@code zorrobpm.user-task-events} queue: the state of a user
 * task at the moment of its change. Delivery is at-least-once: a consumer deduplicates by
 * {@link #eventId}. Variables are not part of the event.
 */
@Getter
@Setter
public class UserTaskLifecycleEvent {
    private UUID eventId;
    private UserTaskEventType type;
    private Instant occurredAt;

    private UUID userTaskId;
    private String bpmnElementId;
    private String name;
    private String formKey;
    private String assignee;
    private List<String> candidateUsers = List.of();
    private List<String> candidateGroups = List.of();
    private Instant createdAt;
    private Instant completedAt;
    private Instant canceledAt;
    private Integer loopIndex;
    private Integer loopTotal;

    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String processDefinitionKey;
    private Integer processDefinitionVersion;
}
