package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivityRepository extends JpaRepository<ActivityEntity, UUID> {

    @Modifying
    @Query("UPDATE ActivityEntity e SET e.status = :status, e.completedAt = :completedAt WHERE e.id = :id")
    void setStatusAndCompletedAt(UUID id, ActivityStatus status, Instant completedAt);

    List<ActivityEntity> findByTokenAndBpmnElementId(UUID token, String bpmnElementId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ActivityEntity e WHERE e.id = :id")
    Optional<ActivityEntity> findByIdForUpdate(UUID id);

    long countByParentActivityIdAndStatus(UUID parentActivityId, ActivityStatus status);
}
