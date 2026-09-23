package com.zorrodev.bpm.grpc;

import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which jobs are ready to be pushed and the compare-and-set lock on them. The dispatcher is kept away
 * from these jobs by a job type no stream subscribes to.
 */
@SpringBootTest(classes = GrpcTestApplication.class)
@TestPropertySource(properties = {
    "zorrobpm.grpc.poll-interval=1h",
    "spring.grpc.server.inprocess.name=zorrobpm-grpc-lock-test",
})
class ServiceTaskJobLockTests {

    @Autowired
    private DBService dbService;

    @Autowired
    private ServiceTaskRepository serviceTaskRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void endOpenTasks() {
        tx(() -> {
            serviceTaskRepository.findAll().stream()
                .filter(t -> t.getCompletedAt() == null)
                .forEach(t -> serviceTaskRepository.setCompletedAt(t.getId(), Instant.now()));
            return null;
        });
    }

    @Test
    void openTaskIsReadyAndLockedOnce() {
        UUID charge = startCharge();

        assertThat(ready()).contains(charge);
        assertThat(lock(charge, "a", Instant.now().plusSeconds(60))).isTrue();
        assertThat(lock(charge, "b", Instant.now().plusSeconds(60))).isFalse();
        assertThat(ready()).doesNotContain(charge);
        assertThat(serviceTask(charge).getLockedBy()).isEqualTo("a");
    }

    @Test
    void expiredLockMakesTheJobReadyAgain() {
        UUID charge = startCharge();
        assertThat(lock(charge, "a", Instant.now().minusSeconds(1))).isTrue();

        assertThat(ready()).contains(charge);
        assertThat(lock(charge, "b", Instant.now().plusSeconds(60))).isTrue();
    }

    @Test
    void releasedLockMakesTheJobReadyAgain() {
        UUID charge = startCharge();
        UUID other = startCharge();
        lock(charge, "a", Instant.now().plusSeconds(60));
        lock(other, "b", Instant.now().plusSeconds(60));

        tx(() -> dbService.releaseServiceTaskLocks("a"));

        assertThat(ready()).contains(charge).doesNotContain(other);
    }

    @Test
    void completedRetryingAndFailedTasksAreNotReady() {
        UUID completed = startCharge();
        UUID retrying = startCharge();
        UUID failed = startCharge();
        tx(() -> {
            serviceTaskRepository.setCompletedAt(completed, Instant.now());
            ServiceTaskEntity entity = serviceTaskRepository.findById(retrying).orElseThrow();
            entity.setNextRetryAt(Instant.now().plusSeconds(60));
            serviceTaskRepository.save(entity);
            activityRepository.setStatus(failed, ActivityStatus.ERROR);
            return null;
        });

        assertThat(ready()).doesNotContain(completed, retrying, failed);
        assertThat(lock(completed, "a", Instant.now().plusSeconds(60))).isFalse();
        assertThat(lock(retrying, "a", Instant.now().plusSeconds(60))).isFalse();
        assertThat(lock(failed, "a", Instant.now().plusSeconds(60))).isFalse();
    }

    @Test
    void concurrentLocksTakeEachJobOnce() throws Exception {
        List<UUID> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            jobs.add(startCharge());
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> taken = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            String owner = "owner-" + t;
            Callable<Integer> contender = () -> {
                go.await();
                int count = 0;
                for (UUID job : jobs) {
                    try {
                        if (lock(job, owner, Instant.now().plusSeconds(60))) {
                            count++;
                        }
                    } catch (RuntimeException e) {
                        // A concurrent update the database refused: this contender did not get the job.
                    }
                }
                return count;
            };
            taken.add(pool.submit(contender));
        }
        go.countDown();
        int total = 0;
        for (Future<Integer> f : taken) {
            total += f.get();
        }
        pool.shutdown();

        assertThat(total).isEqualTo(jobs.size());
        assertThat(jobs).allSatisfy(job -> assertThat(serviceTask(job).getLockedBy()).startsWith("owner-"));
    }

    private UUID startCharge() {
        UUID instance = GrpcTestSupport.start(processDefinitionService, runtimeService, transactionManager, "charge-lock.bpmn");
        return activityRepository.findAll().stream()
            .filter(a -> instance.equals(a.getProcessInstanceId()) && "charge".equals(a.getBpmnElementId()))
            .findFirst().orElseThrow().getId();
    }

    private List<UUID> ready() {
        return tx(() -> dbService.findReadyServiceTaskJobs(Set.of("lock-test"), 1000));
    }

    private boolean lock(UUID id, String owner, Instant until) {
        return tx(() -> dbService.lockServiceTaskJob(id, owner, until));
    }

    private ServiceTaskEntity serviceTask(UUID id) {
        return serviceTaskRepository.findById(id).orElseThrow();
    }

    private <T> T tx(Callable<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                return action.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
