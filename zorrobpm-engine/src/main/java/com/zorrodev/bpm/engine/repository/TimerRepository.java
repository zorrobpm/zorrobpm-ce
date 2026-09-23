package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.TimerEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TimerRepository extends JpaRepository<TimerEntity, UUID> {

    @Query("SELECT t FROM TimerEntity t WHERE t.status = com.zorrodev.bpm.engine.entity.TimerStatus.SCHEDULED AND t.dueAt <= :now ORDER BY t.dueAt")
    List<TimerEntity> findDue(Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TimerEntity t WHERE t.id = :id")
    Optional<TimerEntity> findByIdForUpdate(UUID id);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE TimerEntity t SET t.status = com.zorrodev.bpm.engine.entity.TimerStatus.CANCELED, t.completedAt = :completedAt WHERE t.activityId = :activityId AND t.status = com.zorrodev.bpm.engine.entity.TimerStatus.SCHEDULED")
    int cancelScheduled(UUID activityId, Instant completedAt);

    List<TimerEntity> findByActivityId(UUID activityId);
}
