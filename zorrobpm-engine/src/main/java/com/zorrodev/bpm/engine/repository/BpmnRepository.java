package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.BpmnEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BpmnRepository extends JpaRepository<BpmnEntity, UUID> {

}
