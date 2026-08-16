package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.UserTaskCandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserTaskCandidateRepository extends JpaRepository<UserTaskCandidateEntity, UUID> {

    List<UserTaskCandidateEntity> findByTaskId(UUID taskId);
}
