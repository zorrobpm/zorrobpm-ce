package com.zorrodev.bpm.event.inner;

import com.zorrodev.bpm.event.data.Variable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ServiceTaskCompleted {
    private UUID serviceTaskId;
    private List<Variable> variables;
}
