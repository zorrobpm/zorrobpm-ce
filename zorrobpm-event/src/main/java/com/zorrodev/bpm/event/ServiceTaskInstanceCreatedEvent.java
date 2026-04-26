package com.zorrodev.bpm.event;

import com.zorrodev.bpm.event.data.Variable;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ServiceTaskInstanceCreatedEvent extends TaskInstanceEvent {
    private String name;
    private String jobType;
    private Instant createdAt;
    private List<Variable> variables;
}
