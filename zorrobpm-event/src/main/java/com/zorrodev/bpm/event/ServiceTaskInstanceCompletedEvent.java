package com.zorrodev.bpm.event;

import com.zorrodev.bpm.event.data.Variable;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ServiceTaskInstanceCompletedEvent extends TaskInstanceEvent {
    private UUID serviceTaskId;
    private Instant completedAt;
    private List<Variable> variables;
}
