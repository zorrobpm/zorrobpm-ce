package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.AssigneeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;


public interface AssigneeRepository extends JpaRepository<AssigneeEntity, UUID> {
}
