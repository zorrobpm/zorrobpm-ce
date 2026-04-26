package com.zorrodev.bpm.event;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class UserTaskInstanceCreatedEvent extends TaskInstanceEvent {
    private String name;
    private String formKey;
    private Instant createdAt;
}
