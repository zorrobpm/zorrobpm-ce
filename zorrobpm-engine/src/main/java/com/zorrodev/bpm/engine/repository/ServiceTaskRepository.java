package com.zorrodev.bpm.engine.repository;

import com.zorrodev.bpm.contract.model.BpmnElementStatistics;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ServiceTaskRepository extends JpaRepository<ServiceTaskEntity, UUID>, JpaSpecificationExecutor<ServiceTaskEntity> {

    static Specification<ServiceTaskEntity> byProcessDefinitionId(UUID processDefinitionId) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("processDefinitionId"), processDefinitionId);
    }

    static Specification<ServiceTaskEntity> byProcessInstanceId(UUID processInstanceId) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("processInstanceId"), processInstanceId);
    }

    static Specification<ServiceTaskEntity> byId(UUID id) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("id"), id);
    }

    static Specification<ServiceTaskEntity> byJobType(String jobType) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("jobType"), jobType);
    }

    static Specification<ServiceTaskEntity> byCompleted(boolean completed) {
        return (root, query, criteriaBuilder) -> completed
            ? criteriaBuilder.isNotNull(root.get("completedAt"))
            : criteriaBuilder.isNull(root.get("completedAt"));
    }

    List<ServiceTaskEntity> findByProcessInstanceId(UUID processInstanceId);

    /**
     * Jobs ready to be pushed to a gRPC worker, oldest first: the service task is open, has no
     * incident (its activity is not in ERROR), no pending retry and no lock that is still valid.
     */
    @Query("""
        SELECT e.id FROM ServiceTaskEntity e
        WHERE e.jobType IN :jobs AND e.completedAt IS NULL AND e.nextRetryAt IS NULL
          AND (e.lockedUntil IS NULL OR e.lockedUntil < :now)
          AND NOT EXISTS (SELECT a.id FROM ActivityEntity a WHERE a.id = e.id
                          AND a.status = com.zorrodev.bpm.engine.entity.ActivityStatus.ERROR)
        ORDER BY e.createdAt""")
    List<UUID> findReadyJobs(List<String> jobs, Instant now, Limit limit);

    /**
     * Locks a ready job for a gRPC subscription: a compare-and-set on the same conditions as
     * {@link #findReadyJobs}, so that of several engine nodes only one gets the job.
     *
     * @return 1 if the job is now locked by {@code owner}, 0 if it is not ready any more
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE ServiceTaskEntity e SET e.lockedUntil = :until, e.lockedBy = :owner
        WHERE e.id = :id AND e.completedAt IS NULL AND e.nextRetryAt IS NULL
          AND (e.lockedUntil IS NULL OR e.lockedUntil < :now)
          AND NOT EXISTS (SELECT a.id FROM ActivityEntity a WHERE a.id = e.id
                          AND a.status = com.zorrodev.bpm.engine.entity.ActivityStatus.ERROR)""")
    int tryLock(UUID id, String owner, Instant now, Instant until);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ServiceTaskEntity e SET e.lockedUntil = null, e.lockedBy = null WHERE e.id = :id")
    int releaseLock(UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ServiceTaskEntity e SET e.lockedUntil = null, e.lockedBy = null WHERE e.id = :id AND e.lockedBy = :owner")
    int releaseLock(UUID id, String owner);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ServiceTaskEntity e SET e.lockedUntil = null, e.lockedBy = null WHERE e.lockedBy = :owner")
    int releaseLocks(String owner);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ServiceTaskEntity e SET e.completedAt = :completedAt, e.nextRetryAt = null WHERE e.id = :id")
    void setCompletedAt(UUID id, Instant completedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ServiceTaskEntity e SET e.canceledAt = :canceledAt, e.completedAt = :canceledAt, e.nextRetryAt = null WHERE e.id = :id")
    void setCanceledAt(UUID id, Instant canceledAt);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM ServiceTaskEntity e WHERE e.processInstanceId = :processInstanceId GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findStatsByProcessInstanceId(UUID processInstanceId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM ServiceTaskEntity e WHERE e.processDefinitionId = :processDefinitionId AND e.completedAt IS NULL GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findActiveStatsByProcessDefinitionId(UUID processDefinitionId);

    @Query("SELECT e.bpmnElementId AS bpmnElementId, COUNT(e.id) AS count FROM ServiceTaskEntity e WHERE e.processDefinitionId = :processDefinitionId AND e.completedAt IS NOT NULL GROUP BY e.bpmnElementId")
    List<BpmnElementStatistics> findCompletedStatsByProcessDefinitionId(UUID processDefinitionId);
}
