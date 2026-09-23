package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.TimerEntity;
import com.zorrodev.bpm.engine.entity.TimerKind;
import com.zorrodev.bpm.engine.entity.TimerStatus;
import com.zorrodev.bpm.engine.listener.ServiceTaskCompleteListener;
import com.zorrodev.bpm.engine.listener.ServiceTaskFailedListener;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.TimerRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.engine.service.TimerJobService;
import com.zorrodev.bpm.engine.test.MutableClock;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import com.zorrodev.bpm.exchange.ServiceTaskFailed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Retries of a failed service task, end to end on the engine. Not @Transactional: every failure,
 * completion and timer firing runs in a transaction of its own, and the clock is moved by hand.
 * Worker replies are simulated with the events the queue listener publishes.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ServiceTaskRetryIntegrationTests {

    private static final String RETRIES = "retry/service-task-retries.bpmn";
    private static final String NO_RETRIES = "retry/service-task-no-retries.bpmn";
    private static final String DEADLINE = "retry/service-task-retries-deadline.bpmn";

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TimerJobService timerJobService;

    @Autowired
    private ServiceTaskCompleteListener serviceTaskCompleteListener;

    @Autowired
    private ServiceTaskFailedListener serviceTaskFailedListener;

    @MockitoSpyBean
    private ServiceTaskEnqueueService serviceTaskEnqueueService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ServiceTaskRepository serviceTaskRepository;

    @Autowired
    private TimerRepository timerRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cancelLeftoverTimers() {
        // Timers of one test must not fire when a later test moves the shared clock.
        inTx(() -> timerRepository.findAll().stream()
            .filter(t -> t.getStatus() == TimerStatus.SCHEDULED)
            .forEach(t -> {
                t.setStatus(TimerStatus.CANCELED);
                timerRepository.save(t);
            }));
    }

    // ---------------------------------------------------------------- retry instead of an incident

    @Test
    void failureWithRetriesLeftSchedulesRetryWithoutIncident() {
        UUID instance = start(RETRIES);
        UUID chargeId = chargeId(instance);
        assertThat(serviceTask(chargeId).getRetries()).isEqualTo(2);
        Instant failedAt = clock.instant();

        fail(chargeId, "java.net.SocketTimeoutException", "connect timed out");

        ServiceTaskEntity serviceTask = serviceTask(chargeId);
        assertThat(serviceTask.getRetries()).isEqualTo(1);
        assertThat(serviceTask.getNextRetryAt()).isEqualTo(failedAt.plus(Duration.ofMinutes(1)));
        assertThat(serviceTask.getLastErrorCode()).isEqualTo("java.net.SocketTimeoutException");
        assertThat(serviceTask.getLastErrorMessage()).isEqualTo("connect timed out");
        assertThat(serviceTask.getCompletedAt()).isNull();
        assertThat(incidents(chargeId)).isEmpty();
        assertThat(activityRepository.findById(chargeId).orElseThrow().getStatus()).isNotEqualTo(ActivityStatus.ERROR);
        assertThat(single(retryTimers(chargeId)).getStatus()).isEqualTo(TimerStatus.SCHEDULED);
    }

    @Test
    void retryRequeuesJobOnlyWhenDue() {
        UUID instance = start(RETRIES);
        UUID chargeId = chargeId(instance);
        verify(serviceTaskEnqueueService, times(1)).enqueueAfterCommit(chargeId);
        fail(chargeId, "java.io.IOException", "reset");

        clock.advance(Duration.ofSeconds(30));
        assertThat(fireAll()).isZero();
        verify(serviceTaskEnqueueService, times(1)).enqueueAfterCommit(chargeId);

        clock.advance(Duration.ofSeconds(30));
        assertThat(fireAll()).isEqualTo(1);
        verify(serviceTaskEnqueueService, times(2)).enqueueAfterCommit(chargeId);
        assertThat(serviceTask(chargeId).getNextRetryAt()).isNull();
        assertThat(single(retryTimers(chargeId)).getStatus()).isEqualTo(TimerStatus.FIRED);

        clock.advance(Duration.ofMinutes(10));
        assertThat(fireAll()).isZero();
        verify(serviceTaskEnqueueService, times(2)).enqueueAfterCommit(chargeId);
    }

    @Test
    void exhaustedRetriesOpenIncidentWithLastError() {
        UUID instance = start(RETRIES);
        UUID chargeId = chargeId(instance);

        fail(chargeId, "java.io.IOException", "first");
        retryNow();
        fail(chargeId, "java.io.IOException", "second");
        retryNow();
        fail(chargeId, "java.net.SocketTimeoutException", "third");

        IncidentEntity incident = single(incidents(chargeId));
        assertThat(incident.getMessage()).isEqualTo("third");
        assertThat(incident.getErrorCode()).isEqualTo("java.net.SocketTimeoutException");
        assertThat(incident.getCompletedAt()).isNull();
        assertThat(activityRepository.findById(chargeId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.ERROR);
        assertThat(serviceTask(chargeId).getRetries()).isZero();
        assertThat(serviceTask(chargeId).getNextRetryAt()).isNull();
        verify(serviceTaskEnqueueService, times(3)).enqueueAfterCommit(chargeId);
    }

    @Test
    void successAfterRetryContinuesProcess() {
        UUID instance = start(RETRIES);
        UUID chargeId = chargeId(instance);
        fail(chargeId, "java.io.IOException", "reset");
        retryNow();

        complete(chargeId);

        assertThat(serviceTask(chargeId).getCompletedAt()).isNotNull();
        assertThat(incidents(chargeId)).isEmpty();
        assertThat(activities(instance, "done")).hasSize(1);
    }

    @Test
    void repeatedFailureWhileRetryIsPendingChangesNothing() {
        UUID instance = start(RETRIES);
        UUID chargeId = chargeId(instance);
        fail(chargeId, "java.io.IOException", "reset");
        ServiceTaskEntity before = serviceTask(chargeId);

        clock.advance(Duration.ofSeconds(10));
        fail(chargeId, "java.io.IOException", "redelivered");

        ServiceTaskEntity after = serviceTask(chargeId);
        assertThat(after.getRetries()).isEqualTo(before.getRetries());
        assertThat(after.getNextRetryAt()).isEqualTo(before.getNextRetryAt());
        assertThat(after.getLastErrorMessage()).isEqualTo("reset");
        assertThat(retryTimers(chargeId)).hasSize(1);
        assertThat(incidents(chargeId)).isEmpty();
    }

    // ---------------------------------------------------------------- worker override

    @Test
    void workerWithZeroRetriesGetsIncidentAtOnce() {
        UUID instance = start(RETRIES);
        UUID chargeId = chargeId(instance);

        fail(chargeId, "CARD_DECLINED", "card declined", 0, null);

        assertThat(single(incidents(chargeId)).getErrorCode()).isEqualTo("CARD_DECLINED");
        assertThat(retryTimers(chargeId)).isEmpty();
        assertThat(serviceTask(chargeId).getRetries()).isZero();
    }

    @Test
    void workerSetsRetryTimeout() {
        UUID instance = start(RETRIES);
        UUID chargeId = chargeId(instance);
        Instant failedAt = clock.instant();

        fail(chargeId, "GATEWAY_DOWN", "gateway down", null, "PT10M");

        assertThat(serviceTask(chargeId).getNextRetryAt()).isEqualTo(failedAt.plus(Duration.ofMinutes(10)));
        assertThat(serviceTask(chargeId).getRetries()).isEqualTo(1);
    }

    @Test
    void workerEnablesRetriesForTaskWithoutThem() {
        UUID instance = start(NO_RETRIES);
        UUID chargeId = chargeId(instance);
        Instant failedAt = clock.instant();

        fail(chargeId, "GATEWAY_DOWN", "gateway down", 1, null);

        assertThat(incidents(chargeId)).isEmpty();
        assertThat(serviceTask(chargeId).getRetries()).isZero();
        assertThat(serviceTask(chargeId).getNextRetryAt()).isEqualTo(failedAt);
        assertThat(fireAll()).isEqualTo(1);
        verify(serviceTaskEnqueueService, times(2)).enqueueAfterCommit(chargeId);
    }

    @Test
    void taskWithoutRetriesOpensIncidentAtOnce() {
        UUID instance = start(NO_RETRIES);
        UUID chargeId = chargeId(instance);

        fail(chargeId, "java.lang.IllegalStateException", "boom");

        assertThat(single(incidents(chargeId)).getMessage()).isEqualTo("boom");
        assertThat(retryTimers(chargeId)).isEmpty();
    }

    @Test
    void unsupportedResultStatusIsNotRetried() {
        // What the queue listener publishes for a status the engine does not know.
        UUID instance = start(RETRIES);
        UUID chargeId = chargeId(instance);

        fail(chargeId, "UNSUPPORTED_RESULT_STATUS", "Unsupported service task result status", 0, null);

        assertThat(single(incidents(chargeId)).getErrorCode()).isEqualTo("UNSUPPORTED_RESULT_STATUS");
        assertThat(retryTimers(chargeId)).isEmpty();
    }

    // ---------------------------------------------------------------- resolve, completion, boundary timers

    @Test
    void resolveRestoresRetryCounter() {
        UUID instance = start(RETRIES);
        UUID chargeId = chargeId(instance);
        fail(chargeId, "java.io.IOException", "reset", 0, null);
        UUID incidentId = single(incidents(chargeId)).getId();

        inTx(() -> runtimeService.resolveIncident(incidentId, List.of()));

        assertThat(serviceTask(chargeId).getRetries()).isEqualTo(2);
        fail(chargeId, "java.io.IOException", "again");
        assertThat(incidents(chargeId)).extracting(IncidentEntity::getCompletedAt).doesNotContainNull();
        assertThat(serviceTask(chargeId).getNextRetryAt()).isNotNull();
        assertThat(serviceTask(chargeId).getRetries()).isEqualTo(1);
    }

    @Test
    void manualCompletionCancelsPendingRetry() {
        UUID instance = start(RETRIES);
        UUID chargeId = chargeId(instance);
        fail(chargeId, "java.io.IOException", "reset");

        inTx(() -> runtimeService.completeServiceTask(chargeId, List.of()));

        assertThat(single(retryTimers(chargeId)).getStatus()).isEqualTo(TimerStatus.CANCELED);
        assertThat(serviceTask(chargeId).getNextRetryAt()).isNull();
        clock.advance(Duration.ofMinutes(5));
        assertThat(fireAll()).isZero();
        verify(serviceTaskEnqueueService, times(1)).enqueueAfterCommit(chargeId);
        assertThat(activities(instance, "done")).hasSize(1);
    }

    @Test
    void boundaryTimerInterruptsPendingRetry() {
        UUID instance = start(DEADLINE);
        UUID chargeId = chargeId(instance);
        fail(chargeId, "java.io.IOException", "reset");
        assertThat(serviceTask(chargeId).getNextRetryAt()).isNotNull();

        clock.advance(Duration.ofMinutes(5));
        fireAll();

        assertThat(activityRepository.findById(chargeId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        assertThat(activities(instance, "timedOut")).hasSize(1);
        assertThat(single(retryTimers(chargeId)).getStatus()).isEqualTo(TimerStatus.CANCELED);
        assertThat(serviceTask(chargeId).getNextRetryAt()).isNull();

        clock.advance(Duration.ofMinutes(10));
        assertThat(fireAll()).isZero();
        verify(serviceTaskEnqueueService, times(1)).enqueueAfterCommit(chargeId);
        assertThat(activities(instance, "done")).isEmpty();
    }

    @Test
    void boundaryTimerCountsFromFirstEntry() {
        UUID instance = start(DEADLINE);
        UUID chargeId = chargeId(instance);

        clock.advance(Duration.ofMinutes(2));
        fail(chargeId, "java.io.IOException", "reset", null, "PT1M");
        clock.advance(Duration.ofMinutes(1));
        assertThat(fireAll()).isEqualTo(1);
        verify(serviceTaskEnqueueService, times(2)).enqueueAfterCommit(chargeId);

        clock.advance(Duration.ofMinutes(1));
        assertThat(fireAll()).isZero();
        assertThat(activities(instance, "timedOut")).isEmpty();

        clock.advance(Duration.ofMinutes(1));
        assertThat(fireAll()).isEqualTo(1);
        assertThat(activities(instance, "timedOut")).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private void fail(UUID serviceTaskId, String errorCode, String message) {
        fail(serviceTaskId, errorCode, message, null, null);
    }

    private void fail(UUID serviceTaskId, String errorCode, String message, Integer retries, String retryTimeout) {
        ServiceTaskFailed failed = new ServiceTaskFailed();
        failed.setServiceTaskId(serviceTaskId);
        failed.setErrorCode(errorCode);
        failed.setMessage(message);
        failed.setRetries(retries);
        failed.setRetryTimeout(retryTimeout);
        serviceTaskFailedListener.on(failed);
    }

    private void complete(UUID serviceTaskId) {
        ServiceTaskCompleted completed = new ServiceTaskCompleted();
        completed.setServiceTaskId(serviceTaskId);
        completed.setVariables(List.of());
        serviceTaskCompleteListener.on(completed);
    }

    /** Moves the clock to the pending retry and fires it. */
    private void retryNow() {
        clock.advance(Duration.ofMinutes(1));
        assertThat(fireAll()).isEqualTo(1);
    }

    private int fireAll() {
        int total = 0;
        for (int round = 0; round < 20; round++) {
            int fired = timerJobService.fireDueTimers();
            if (fired == 0) {
                break;
            }
            total += fired;
        }
        return total;
    }

    private UUID start(String file, ProcessVariable... variables) {
        return inTx(() -> {
            try {
                UUID definitionId = processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/" + file))).getId();
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(definitionId);
                dto.setVariables(List.of(variables));
                return runtimeService.startProcessInstance(dto).getId();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private UUID chargeId(UUID processInstanceId) {
        return single(activities(processInstanceId, "charge")).getId();
    }

    private ServiceTaskEntity serviceTask(UUID id) {
        return serviceTaskRepository.findById(id).orElseThrow();
    }

    private List<ActivityEntity> activities(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> processInstanceId.equals(a.getProcessInstanceId()) && bpmnElementId.equals(a.getBpmnElementId()))
            .sorted(Comparator.comparing(ActivityEntity::getCreatedAt))
            .toList();
    }

    private List<TimerEntity> retryTimers(UUID activityId) {
        return timerRepository.findByActivityId(activityId).stream().filter(t -> t.getKind() == TimerKind.RETRY).toList();
    }

    private List<IncidentEntity> incidents(UUID activityId) {
        return incidentRepository.findAll().stream().filter(i -> activityId.equals(i.getActivityId())).toList();
    }

    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private void inTx(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private static <T> T single(List<T> items) {
        assertThat(items).hasSize(1);
        return items.get(0);
    }
}
