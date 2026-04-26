package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VariableRepository extends JpaRepository<ProcessVariableEntity, UUID>, JpaSpecificationExecutor<ProcessVariableEntity> {

    static Specification<ProcessVariableEntity> byProcessInstanceId(UUID processInstanceId) {
        return ((root, query, criteriaBuilder) ->  criteriaBuilder.equal(root.get("processInstanceId"), processInstanceId));
    }

    static Specification<ProcessVariableEntity> byName(String name) {
        return ((root, query, criteriaBuilder) ->  criteriaBuilder.equal(root.get("name"), name));
    }

    static Specification<ProcessVariableEntity> byType(ProcessVariableType type) {
        return ((root, query, criteriaBuilder) ->  criteriaBuilder.equal(root.get("type"), type));
    }

    static Specification<ProcessVariableEntity> byValue(String value) {
        return ((root, query, criteriaBuilder) ->  criteriaBuilder.equal(root.get("value"), value));
    }

    boolean existsByNameAndProcessInstanceId(String name, UUID processInstanceId);

    Optional<ProcessVariableEntity> findByNameAndProcessInstanceId(String name, UUID processInstanceId);

    List<ProcessVariableEntity> findByProcessInstanceId(UUID processInstanceId);

    @Modifying
    @Query("UPDATE ProcessVariableEntity pve SET pve.type = :type, pve.textValue = :textValue WHERE pve.processInstanceId = :processInstanceId AND pve.name = :name")
    void updateVariableTextValueAndType(UUID processInstanceId, String name, ProcessVariableType type, String textValue);
}
