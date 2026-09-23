package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.dto.Activity;
import com.zorrodev.bpm.engine.dto.Timer;
import com.zorrodev.bpm.engine.dto.TimerSchedule;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.TimerEntity;
import com.zorrodev.bpm.engine.entity.TimerStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.TimerRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Storage of boundary timers and the locks the poller relies on, against the real schema.
 * Not @Transactional: the lock checks need two committed, concurrent transactions.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class TimerPersistenceIntegrationTests {

    @Autowired
    private DBService dbService;

    @Autowired
    private TimerRepository timerRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @AfterEach
    void cancelLeftoverTimers() {
        // These timers belong to hosts without a real model; the poller of another test must not pick them.
        run(() -> timerRepository.findAll().stream()
            .filter(t -> t.getStatus() == TimerStatus.SCHEDULED)
            .forEach(t -> {
                t.setStatus(TimerStatus.CANCELED);
                timerRepository.save(t);
            }));
    }

    @Test
    void findsDueScheduledTimersInDueOrder() {
        Instant base = Instant.parse("2000-01-01T00:00:00Z");
        UUID activityId = inTx(this::createActivity);
        UUID processInstanceId = activityRepository.findById(activityId).orElseThrow().getProcessInstanceId();

        UUID later = inTx(() -> dbService.createTimer(processInstanceId, activityId, "later", TimerSchedule.once(base.plusSeconds(20))));
        UUID first = inTx(() -> dbService.createTimer(processInstanceId, activityId, "first", TimerSchedule.once(base.plusSeconds(10))));
        UUID notDue = inTx(() -> dbService.createTimer(processInstanceId, activityId, "notDue", TimerSchedule.once(base.plusSeconds(60))));
        UUID fired = inTx(() -> dbService.createTimer(processInstanceId, activityId, "fired", TimerSchedule.once(base.plusSeconds(5))));
        run(() -> dbService.setTimerStatus(fired, TimerStatus.FIRED));

        List<UUID> due = inTx(() -> dbService.findDueTimers(base.plusSeconds(30), 100)).stream()
            .filter(t -> activityId.equals(t.getActivityId()))
            .map(Timer::getId)
            .toList();

        assertThat(due).containsExactly(first, later);
        assertThat(timerRepository.findById(fired).orElseThrow().getCompletedAt()).isNotNull();
        assertThat(timerRepository.findById(notDue).orElseThrow().getStatus()).isEqualTo(TimerStatus.SCHEDULED);
    }

    @Test
    void limitsTheBatch() {
        Instant base = Instant.parse("1990-01-01T00:00:00Z");
        UUID activityId = inTx(this::createActivity);
        UUID processInstanceId = activityRepository.findById(activityId).orElseThrow().getProcessInstanceId();
        for (int i = 0; i < 3; i++) {
            Instant dueAt = base.plusSeconds(i);
            run(() -> dbService.createTimer(processInstanceId, activityId, "t", TimerSchedule.once(dueAt)));
        }

        assertThat(inTx(() -> dbService.findDueTimers(base.plusSeconds(10), 2))).hasSize(2);
    }

    @Test
    void cancelsOnlyScheduledTimersOfTheActivity() {
        Instant dueAt = Instant.now().plus(1, ChronoUnit.DAYS);
        UUID activityId = inTx(this::createActivity);
        UUID otherActivityId = inTx(this::createActivity);
        UUID processInstanceId = activityRepository.findById(activityId).orElseThrow().getProcessInstanceId();
        UUID otherProcessInstanceId = activityRepository.findById(otherActivityId).orElseThrow().getProcessInstanceId();

        UUID scheduled = inTx(() -> dbService.createTimer(processInstanceId, activityId, "a", new TimerSchedule(dueAt, "PT1H", 2)));
        UUID fired = inTx(() -> dbService.createTimer(processInstanceId, activityId, "b", TimerSchedule.once(dueAt)));
        run(() -> dbService.setTimerStatus(fired, TimerStatus.FIRED));
        UUID other = inTx(() -> dbService.createTimer(otherProcessInstanceId, otherActivityId, "c", TimerSchedule.once(dueAt)));

        run(() -> dbService.cancelTimers(activityId));

        TimerEntity canceled = timerRepository.findById(scheduled).orElseThrow();
        assertThat(canceled.getStatus()).isEqualTo(TimerStatus.CANCELED);
        assertThat(canceled.getCompletedAt()).isNotNull();
        assertThat(canceled.getCycleInterval()).isEqualTo("PT1H");
        assertThat(canceled.getRemainingRepetitions()).isEqualTo(2);
        assertThat(timerRepository.findById(fired).orElseThrow().getStatus()).isEqualTo(TimerStatus.FIRED);
        assertThat(timerRepository.findById(other).orElseThrow().getStatus()).isEqualTo(TimerStatus.SCHEDULED);
    }

    @Test
    void reschedulesTimer() {
        Instant dueAt = Instant.now().plus(1, ChronoUnit.DAYS);
        UUID activityId = inTx(this::createActivity);
        UUID processInstanceId = activityRepository.findById(activityId).orElseThrow().getProcessInstanceId();
        UUID timerId = inTx(() -> dbService.createTimer(processInstanceId, activityId, "a", new TimerSchedule(dueAt, "PT1H", 2)));

        run(() -> dbService.rescheduleTimer(timerId, dueAt.plus(Duration.ofHours(1)), 1));

        Timer timer = inTx(() -> dbService.getTimerForUpdate(timerId)).orElseThrow();
        assertThat(timer.getDueAt()).isEqualTo(dueAt.plus(Duration.ofHours(1)));
        assertThat(timer.getRemainingRepetitions()).isEqualTo(1);
        assertThat(timer.getStatus()).isEqualTo(TimerStatus.SCHEDULED);
    }

    @Test
    void skipLockedReturnsEmptyWhileAnotherTransactionHoldsTheRow() throws Exception {
        UUID activityId = inTx(this::createActivity);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> inTx(() -> {
                dbService.getActivityForUpdate(activityId);
                locked.countDown();
                await(release);
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Optional<Activity> whileHeld = inTx(() -> dbService.findActivityForUpdateSkipLocked(activityId));

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            assertThat(whileHeld).isEmpty();
            assertThat(inTx(() -> dbService.findActivityForUpdateSkipLocked(activityId))).isPresent();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void countsAndListsOpenActivitiesOfTheInstance() {
        UUID activityId = inTx(this::createActivity);
        UUID processInstanceId = activityRepository.findById(activityId).orElseThrow().getProcessInstanceId();
        UUID completedId = inTx(() -> createActivity(processInstanceId));
        run(() -> dbService.completeActivity(completedId));

        assertThat(inTx(() -> dbService.countOpenActivities(processInstanceId))).isEqualTo(1);
        assertThat(inTx(() -> dbService.findOpenActivities(processInstanceId))).extracting(Activity::getId).containsExactly(activityId);
        assertThat(inTx(() -> dbService.getProcessInstanceForUpdate(processInstanceId)).getId()).isEqualTo(processInstanceId);
    }

    @Test
    void findsChildProcessInstanceOfCallActivity() {
        UUID activityId = inTx(this::createActivity);
        UUID childId = inTx(() -> createProcessInstance(activityId));

        assertThat(inTx(() -> dbService.findChildProcessInstance(activityId))).get().extracting(p -> p.getId()).isEqualTo(childId);
        assertThat(inTx(() -> dbService.findChildProcessInstance(childId))).isEmpty();
    }

    private UUID createActivity() {
        return createActivity(createProcessInstance(null));
    }

    private UUID createProcessInstance(UUID parentActivityId) {
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(UUID.randomUUID());
        pi.setProcessDefinitionId(processDefinitionId());
        pi.setStartedAt(Instant.now());
        pi.setParentActivityId(parentActivityId);
        processInstanceRepository.save(pi);
        return pi.getId();
    }

    private UUID processDefinitionId() {
        try {
            return processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/boundary/boundary-parse.bpmn"))).getId();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID createActivity(UUID processInstanceId) {
        ActivityEntity activity = new ActivityEntity();
        activity.setId(UUID.randomUUID());
        activity.setProcessInstanceId(processInstanceId);
        activity.setBpmnElementId("task");
        activity.setCreatedAt(Instant.now());
        activity.setType(BpmnElementType.USER_TASK);
        activity.setStatus(ActivityStatus.CREATED);
        activity.setToken(UUID.randomUUID());
        activityRepository.save(activity);
        return activity.getId();
    }

    private <T> T inTx(java.util.function.Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }

    private void run(Runnable action) {
        inTx(() -> {
            action.run();
            return null;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
