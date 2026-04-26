package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ProcessDefinitionRepository extends JpaRepository<ProcessDefinitionEntity, UUID>, JpaSpecificationExecutor<ProcessDefinitionEntity> {

    Optional<ProcessDefinitionEntity> findBySha256(String sha256);

    @Query("SELECT MAX(pd.version) FROM ProcessDefinitionEntity pd WHERE pd.key = :key")
    Optional<Integer> findMaxByKey(String key);

    Optional<ProcessDefinitionEntity> findByKeyAndVersion(String key, Integer version);

    @Query("SELECT pd1 FROM ProcessDefinitionEntity pd1 JOIN (SELECT pd2.key AS key, MAX(pd2.version) AS version FROM ProcessDefinitionEntity pd2 GROUP BY pd2.key) AS pd3 ON pd1.key = pd3.key AND pd1.version = pd3.version")
    Page<ProcessDefinitionEntity> findAllLatest(Pageable page);
}
