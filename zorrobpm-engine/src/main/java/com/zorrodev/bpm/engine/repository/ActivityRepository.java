package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivityRepository extends JpaRepository<ActivityEntity, UUID> {

    @Modifying
    @Query("UPDATE ActivityEntity e SET e.status = :status, e.completedAt = :completedAt WHERE e.id = :id")
    void setStatusAndCompletedAt(UUID id, ActivityStatus status, Instant completedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ActivityEntity e SET e.status = :status WHERE e.id = :id")
    void setStatus(UUID id, ActivityStatus status);

    List<ActivityEntity> findByTokenAndBpmnElementId(UUID token, String bpmnElementId);

    Optional<ActivityEntity> findFirstByTokenAndBpmnElementIdAndParentActivityIdIsNullAndCompletedAtIsNullOrderByCreatedAtDesc(UUID token, String bpmnElementId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ActivityEntity e WHERE e.id = :id")
    Optional<ActivityEntity> findByIdForUpdate(UUID id);

    /**
     * Locks the row unless another transaction holds it (SKIP LOCKED): the timer poller must not
     * wait on a host that is being completed, or fired by another node.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT e FROM ActivityEntity e WHERE e.id = :id")
    Optional<ActivityEntity> findByIdForUpdateSkipLocked(UUID id);

    /**
     * The parent of an activity, read as a scalar so that the activity is not loaded into the
     * persistence context before it is locked.
     */
    @Query("SELECT e.parentActivityId FROM ActivityEntity e WHERE e.id = :id")
    List<UUID> findParentActivityId(UUID id);

    long countByProcessInstanceIdAndCompletedAtIsNull(UUID processInstanceId);

    List<ActivityEntity> findByProcessInstanceIdAndCompletedAtIsNull(UUID processInstanceId);

    long countByParentActivityIdAndStatus(UUID parentActivityId, ActivityStatus status);

    List<ActivityEntity> findByParentActivityIdAndStatus(UUID parentActivityId, ActivityStatus status);
}
