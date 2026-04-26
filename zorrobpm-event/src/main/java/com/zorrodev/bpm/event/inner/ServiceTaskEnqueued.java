package com.zorrodev.bpm.event.inner;

import com.zorrodev.bpm.event.data.Variable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ServiceTaskEnqueued {
    private String job;
    private UUID serviceTaskId;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private List<Variable> variables;
}
