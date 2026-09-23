package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.exception.TaskNotActiveException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.BpmnErrorOutcome;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.TimerEntity;
import com.zorrodev.bpm.engine.entity.TimerStatus;
import com.zorrodev.bpm.engine.listener.ServiceTaskBpmnErrorThrownListener;
import com.zorrodev.bpm.engine.listener.ServiceTaskFailedListener;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.TimerRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.engine.service.TimerJobService;
import com.zorrodev.bpm.engine.test.MutableClock;
import com.zorrodev.bpm.exchange.ServiceTaskBpmnErrorThrown;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * BPMN errors thrown by service tasks, end to end on the engine. Not @Transactional: every reply and
 * timer firing runs in a transaction of its own, as it does from the queue. Worker replies are
 * simulated with the events the queue listener publishes.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class BpmnErrorIntegrationTests {

    private static final String LOOKUP = "bpmn-error/lookup.bpmn";
    private static final String CODE_ONLY = "bpmn-error/lookup-code-only.bpmn";
    private static final String ANY = "bpmn-error/lookup-any.bpmn";
    private static final String PLAIN = "bpmn-error/lookup-plain.bpmn";
    private static final String CHILD = "bpmn-error/child.bpmn";
    private static final String CALL = "bpmn-error/call.bpmn";
    private static final String CHILD_CATCHING = "bpmn-error/child-catching.bpmn";
    private static final String CALL_CATCHING = "bpmn-error/call-catching.bpmn";

    private static final String NOT_FOUND = "CUSTOMER_NOT_FOUND";

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ActivityService activityService;

    @Autowired
    private DBService dbService;

    @Autowired
    private TimerJobService timerJobService;

    @Autowired
    private ServiceTaskBpmnErrorThrownListener bpmnErrorListener;

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
    private ProcessInstanceRepository processInstanceRepository;

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

    // ---------------------------------------------------------------- caught on the service task

    @Test
    void errorCaughtByCodeContinuesOnBoundaryEvent() {
        UUID instance = start(LOOKUP);
        UUID lookupId = taskId(instance, "lookup");

        throwError(lookupId, NOT_FOUND, "no customer 42", variable("customerId", "42"));

        assertThat(activityRepository.findById(lookupId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        assertThat(serviceTask(lookupId).getCanceledAt()).isNotNull();
        assertThat(incidents(lookupId)).isEmpty();
        assertThat(variableValue(instance, "customerId")).isEqualTo("42");
        assertThat(single(activities(instance, "notFound")).getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(single(activities(instance, "createCustomer")).getCompletedAt()).isNull();
        assertThat(activities(instance, "anyError")).isEmpty();
        assertThat(activities(instance, "done")).isEmpty();
    }

    @Test
    void eventWithoutCodeCatchesAnyCode() {
        UUID instance = start(ANY);
        UUID lookupId = taskId(instance, "lookup");

        throwError(lookupId, "ANYTHING", null);

        assertThat(activities(instance, "handled")).hasSize(1);
        assertThat(incidents(lookupId)).isEmpty();
        assertThat(completedAt(instance)).isNotNull();
    }

    @Test
    void eventWithCodeWinsOverEventWithoutCode() {
        UUID instance = start(LOOKUP);

        throwError(taskId(instance, "lookup"), NOT_FOUND, null);

        assertThat(activities(instance, "notFound")).hasSize(1);
        assertThat(activities(instance, "anyError")).isEmpty();
        assertThat(activities(instance, "failed")).isEmpty();
    }

    @Test
    void otherCodeFallsBackToEventWithoutCode() {
        UUID instance = start(LOOKUP);

        throwError(taskId(instance, "lookup"), "CARD_DECLINED", null);

        assertThat(activities(instance, "anyError")).hasSize(1);
        assertThat(activities(instance, "notFound")).isEmpty();
    }

    @Test
    void caughtErrorDoesNotUseRetries() {
        UUID instance = start(LOOKUP);
        UUID lookupId = taskId(instance, "lookup");
        assertThat(serviceTask(lookupId).getRetries()).isEqualTo(3);

        throwError(lookupId, NOT_FOUND, null);

        assertThat(serviceTask(lookupId).getRetries()).isEqualTo(3);
        assertThat(serviceTask(lookupId).getNextRetryAt()).isNull();
        verify(serviceTaskEnqueueService, times(1)).enqueueAfterCommit(lookupId);
    }

    @Test
    void boundaryTimerDoesNotFireAfterCaughtError() {
        UUID instance = start(LOOKUP);
        UUID lookupId = taskId(instance, "lookup");
        TimerEntity deadline = single(timerRepository.findByActivityId(lookupId));

        clock.advance(Duration.ofMinutes(1));
        throwError(lookupId, NOT_FOUND, null);

        assertThat(timerRepository.findById(deadline.getId()).orElseThrow().getStatus()).isEqualTo(TimerStatus.CANCELED);
        clock.advance(Duration.ofMinutes(5));
        assertThat(fireAll()).isZero();
        assertThat(activities(instance, "timedOut")).isEmpty();
    }

    // ---------------------------------------------------------------- caught on a call activity

    @Test
    void errorPropagatesToCallActivity() {
        deploy(CHILD);
        UUID instance = start(CALL);
        UUID callId = taskId(instance, "call");
        UUID child = childInstance(callId);
        UUID childLookupId = taskId(child, "childLookup");

        BpmnErrorOutcome outcome = throwErrorDirectly(childLookupId, NOT_FOUND, "missing", variable("reason", "missing"));

        assertThat(outcome.caught()).isTrue();
        assertThat(outcome.boundaryEventId()).isEqualTo("callNotFound");
        assertThat(outcome.processInstanceId()).isEqualTo(instance);
        assertThat(activityRepository.findById(childLookupId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        assertThat(serviceTask(childLookupId).getCanceledAt()).isNotNull();
        assertThat(completedAt(child)).isNotNull();
        assertThat(activityRepository.findById(callId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        assertThat(incidents(childLookupId)).isEmpty();
        assertThat(variableValue(instance, "reason")).isEqualTo("missing");
        assertThat(dbService.getVariables(child)).extracting(ProcessVariable::getName).doesNotContain("reason");
        assertThat(activities(instance, "handled")).hasSize(1);
        assertThat(activities(instance, "done")).isEmpty();
        assertThat(completedAt(instance)).isNotNull();
    }

    @Test
    void nearestLevelCatchesTheError() {
        deploy(CHILD_CATCHING);
        UUID instance = start(CALL_CATCHING);
        UUID callId = taskId(instance, "call");
        UUID child = childInstance(callId);

        BpmnErrorOutcome outcome = throwErrorDirectly(taskId(child, "childLookup"), NOT_FOUND, null);

        assertThat(outcome.boundaryEventId()).isEqualTo("childAny");
        assertThat(outcome.processInstanceId()).isEqualTo(child);
        assertThat(activities(child, "childHandled")).hasSize(1);
        // The child ends normally, and the call activity completes instead of being interrupted.
        assertThat(activityRepository.findById(callId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(activities(instance, "handled")).isEmpty();
        assertThat(activities(instance, "done")).hasSize(1);
    }

    // ---------------------------------------------------------------- not caught

    @Test
    void uncaughtErrorOpensIncident() {
        UUID instance = start(PLAIN);
        UUID lookupId = taskId(instance, "lookup");

        throwError(lookupId, NOT_FOUND, "no customer 42", variable("customerId", "42"));

        IncidentEntity incident = single(incidents(lookupId));
        assertThat(incident.getErrorCode()).isEqualTo(NOT_FOUND);
        assertThat(incident.getMessage()).isEqualTo("no customer 42");
        assertThat(incident.getDetails()).contains("No error boundary event catches BPMN error 'CUSTOMER_NOT_FOUND'").contains("'lookup'");
        assertThat(incident.getCompletedAt()).isNull();
        assertThat(activityRepository.findById(lookupId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.ERROR);
        assertThat(serviceTask(lookupId).getCompletedAt()).isNull();
        assertThat(serviceTask(lookupId).getNextRetryAt()).isNull();
        assertThat(serviceTask(lookupId).getRetries()).isEqualTo(2);
        assertThat(dbService.getVariables(instance)).extracting(ProcessVariable::getName).doesNotContain("customerId");
        assertThat(activities(instance, "done")).isEmpty();
    }

    @Test
    void uncaughtErrorWithoutMessageNamesTheCode() {
        UUID instance = start(PLAIN);
        UUID lookupId = taskId(instance, "lookup");

        throwError(lookupId, NOT_FOUND, null);

        assertThat(single(incidents(lookupId)).getMessage()).isEqualTo("Unhandled BPMN error 'CUSTOMER_NOT_FOUND'");
    }

    @Test
    void notMatchingCodeOpensIncident() {
        UUID instance = start(CODE_ONLY);
        UUID lookupId = taskId(instance, "lookup");

        throwError(lookupId, "CARD_DECLINED", "declined");

        assertThat(single(incidents(lookupId)).getErrorCode()).isEqualTo("CARD_DECLINED");
        assertThat(activities(instance, "notFound")).isEmpty();
        assertThat(activities(instance, "handled")).isEmpty();
    }

    @Test
    void uncaughtErrorInChildOpensIncidentOnTheServiceTask() {
        deploy(CHILD);
        UUID instance = start(CALL);
        UUID callId = taskId(instance, "call");
        UUID child = childInstance(callId);
        UUID childLookupId = taskId(child, "childLookup");

        throwError(childLookupId, "CARD_DECLINED", "declined");

        assertThat(single(incidents(childLookupId)).getErrorCode()).isEqualTo("CARD_DECLINED");
        assertThat(completedAt(child)).isNull();
        assertThat(activityRepository.findById(callId).orElseThrow().getCompletedAt()).isNull();
    }

    @Test
    void resolvingUncaughtErrorRequeuesTheJob() {
        UUID instance = start(PLAIN);
        UUID lookupId = taskId(instance, "lookup");
        throwError(lookupId, NOT_FOUND, null);
        IncidentEntity incident = single(incidents(lookupId));

        inTx(() -> runtimeService.resolveIncident(incident.getId(), List.of()));

        assertThat(incidentRepository.findById(incident.getId()).orElseThrow().getCompletedAt()).isNotNull();
        assertThat(activityRepository.findById(lookupId).orElseThrow().getStatus()).isNotEqualTo(ActivityStatus.ERROR);
        assertThat(taskId(instance, "lookup")).isEqualTo(lookupId);
        verify(serviceTaskEnqueueService, times(2)).enqueueAfterCommit(lookupId);
    }

    // ---------------------------------------------------------------- ignored

    @Test
    void repeatedErrorAfterCatchChangesNothing() {
        UUID instance = start(CODE_ONLY);
        UUID lookupId = taskId(instance, "lookup");
        throwError(lookupId, NOT_FOUND, null);

        throwError(lookupId, NOT_FOUND, null);

        assertThat(activities(instance, "notFound")).hasSize(1);
        assertThat(activities(instance, "handled")).hasSize(1);
        assertThatThrownBy(() -> throwErrorDirectly(lookupId, NOT_FOUND, null)).isInstanceOf(TaskNotActiveException.class);
    }

    @Test
    void errorWhileRetryIsPendingChangesNothing() {
        UUID instance = start(CODE_ONLY);
        UUID lookupId = taskId(instance, "lookup");
        fail(lookupId, "java.io.IOException", "reset", null);
        ServiceTaskEntity before = serviceTask(lookupId);
        assertThat(before.getNextRetryAt()).isNotNull();

        throwError(lookupId, NOT_FOUND, null);

        ServiceTaskEntity after = serviceTask(lookupId);
        assertThat(after.getRetries()).isEqualTo(before.getRetries());
        assertThat(after.getNextRetryAt()).isEqualTo(before.getNextRetryAt());
        assertThat(activities(instance, "notFound")).isEmpty();
        assertThatThrownBy(() -> throwErrorDirectly(lookupId, NOT_FOUND, null)).isInstanceOf(TaskNotActiveException.class);
    }

    @Test
    void errorWithOpenIncidentReturnsIt() {
        UUID instance = start(PLAIN);
        UUID lookupId = taskId(instance, "lookup");
        throwError(lookupId, NOT_FOUND, null);
        UUID incidentId = single(incidents(lookupId)).getId();

        BpmnErrorOutcome outcome = throwErrorDirectly(lookupId, "OTHER", "other");

        assertThat(outcome.caught()).isFalse();
        assertThat(outcome.incidentId()).isEqualTo(incidentId);
        assertThat(single(incidents(lookupId)).getErrorCode()).isEqualTo(NOT_FOUND);
    }

    @Test
    void bpmnErrorWithoutCodeOpensIncidentWithoutRetry() {
        // The queue listener turns a BPMN_ERROR without a code into this failure.
        UUID instance = start(CODE_ONLY);
        UUID lookupId = taskId(instance, "lookup");

        fail(lookupId, "INVALID_BPMN_ERROR", "BPMN error without an error code", 0);

        assertThat(single(incidents(lookupId)).getErrorCode()).isEqualTo("INVALID_BPMN_ERROR");
        assertThat(serviceTask(lookupId).getNextRetryAt()).isNull();
        assertThat(activities(instance, "handled")).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private void throwError(UUID serviceTaskId, String errorCode, String message, ProcessVariable... variables) {
        ServiceTaskBpmnErrorThrown thrown = new ServiceTaskBpmnErrorThrown();
        thrown.setServiceTaskId(serviceTaskId);
        thrown.setErrorCode(errorCode);
        thrown.setMessage(message);
        thrown.setVariables(List.of(variables).stream().map(v -> {
            com.zorrodev.bpm.exchange.ProcessVariable wire = new com.zorrodev.bpm.exchange.ProcessVariable();
            wire.setName(v.getName());
            wire.setValue(v.getValue());
            wire.setType(v.getType().name());
            return wire;
        }).toList());
        bpmnErrorListener.on(thrown);
    }

    private BpmnErrorOutcome throwErrorDirectly(UUID serviceTaskId, String errorCode, String message, ProcessVariable... variables) {
        return inTx(() -> activityService.throwBpmnError(serviceTaskId, errorCode, message, List.of(variables)));
    }

    private void fail(UUID serviceTaskId, String errorCode, String message, Integer retries) {
        ServiceTaskFailed failed = new ServiceTaskFailed();
        failed.setServiceTaskId(serviceTaskId);
        failed.setErrorCode(errorCode);
        failed.setMessage(message);
        failed.setRetries(retries);
        serviceTaskFailedListener.on(failed);
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

    private void deploy(String file) {
        inTx(() -> {
            try {
                return processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/" + file)));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private UUID start(String file) {
        return inTx(() -> {
            try {
                UUID definitionId = processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/" + file))).getId();
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(definitionId);
                dto.setVariables(List.of());
                return runtimeService.startProcessInstance(dto).getId();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private UUID taskId(UUID processInstanceId, String bpmnElementId) {
        return single(activities(processInstanceId, bpmnElementId)).getId();
    }

    private UUID childInstance(UUID callActivityId) {
        return processInstanceRepository.findFirstByParentActivityId(callActivityId).map(ProcessInstanceEntity::getId).orElseThrow();
    }

    private Instant completedAt(UUID processInstanceId) {
        return processInstanceRepository.findById(processInstanceId).orElseThrow().getCompletedAt();
    }

    private ServiceTaskEntity serviceTask(UUID id) {
        return serviceTaskRepository.findById(id).orElseThrow();
    }

    private String variableValue(UUID processInstanceId, String name) {
        return single(dbService.getVariables(processInstanceId).stream().filter(v -> name.equals(v.getName())).toList()).getValue();
    }

    private List<ActivityEntity> activities(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> processInstanceId.equals(a.getProcessInstanceId()) && bpmnElementId.equals(a.getBpmnElementId()))
            .sorted(Comparator.comparing(ActivityEntity::getCreatedAt))
            .toList();
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

    private static ProcessVariable variable(String name, String value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(com.zorrodev.bpm.contract.model.ProcessVariableType.STRING);
        variable.setValue(value);
        return variable;
    }
}
