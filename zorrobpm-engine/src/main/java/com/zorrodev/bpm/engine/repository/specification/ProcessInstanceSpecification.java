package com.zorrodev.bpm.engine.repository.specification;

import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

public class ProcessInstanceSpecification {

    public static Specification<ProcessInstanceEntity> byProcessInstanceId(UUID processInstanceId) {
        return (root, query, builder) -> builder.equal(root.get("id"), processInstanceId);
    }

    public static Specification<ProcessInstanceEntity> byProcessDefinitionId(UUID processDefinitionId) {
        return (root, query, builder) -> builder.equal(root.get("processDefinitionId"), processDefinitionId);
    }

}
