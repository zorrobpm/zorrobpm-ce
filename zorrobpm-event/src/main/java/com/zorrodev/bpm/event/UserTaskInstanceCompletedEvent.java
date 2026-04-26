package com.zorrodev.bpm.event;

import com.zorrodev.bpm.event.data.Variable;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class UserTaskInstanceCompletedEvent extends TaskInstanceEvent {
    private Instant completedAt;
    private List<Variable> variables;
}
