package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.exception.TaskNotActiveException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.TimerEntity;
import com.zorrodev.bpm.engine.entity.TimerStatus;
import com.zorrodev.bpm.engine.listener.ServiceTaskCompleteListener;
import com.zorrodev.bpm.engine.listener.ServiceTaskFailedListener;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.TimerRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.TimerJobService;
import com.zorrodev.bpm.engine.test.MutableClock;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import com.zorrodev.bpm.exchange.ServiceTaskFailed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
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

/**
 * Boundary timers and the instance completion rule, end to end on the engine. Not @Transactional:
 * every command and every timer firing runs in a transaction of its own, as in production, and the
 * clock is moved by hand instead of waiting.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class BoundaryTimerIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ActivityService activityService;

    @Autowired
    private TimerJobService timerJobService;

    @Autowired
    private ServiceTaskCompleteListener serviceTaskCompleteListener;

    @Autowired
    private ServiceTaskFailedListener serviceTaskFailedListener;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private TimerRepository timerRepository;

    @Autowired
    private UserTaskRepository userTaskRepository;

    @Autowired
    private ServiceTaskRepository serviceTaskRepository;

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

    // ---------------------------------------------------------------- arming

    @Test
    void armsTimersOnEnteringEachHostType() {
        Instant now = clock.instant();

        UUID userTaskInstance = start("boundary/interrupting-user-task.bpmn");
        ActivityEntity approve = single(activities(userTaskInstance, "approve"));
        assertThat(timers(approve.getId())).extracting(TimerEntity::getBpmnElementId, TimerEntity::getDueAt, TimerEntity::getStatus)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("escalateTimer", now.plus(Duration.ofHours(1)), TimerStatus.SCHEDULED),
                org.assertj.core.groups.Tuple.tuple("giveUpTimer", now.plus(Duration.ofHours(2)), TimerStatus.SCHEDULED));

        UUID serviceTaskInstance = start("boundary/service-task.bpmn", string("sla", "PT10M"));
        ActivityEntity charge = single(activities(serviceTaskInstance, "charge"));
        assertThat(single(timers(charge.getId())).getDueAt()).isEqualTo(now.plus(Duration.ofMinutes(10)));

        deploy("boundary/child.bpmn");
        UUID callInstance = start("boundary/call-activity.bpmn");
        ActivityEntity call = single(activities(callInstance, "call"));
        assertThat(single(timers(call.getId())).getBpmnElementId()).isEqualTo("callTimeout");

        UUID multiInstance = start("boundary/multi-instance.bpmn", json("items", "[1,2,3]"));
        ActivityEntity scope = scope(multiInstance, "review");
        assertThat(timers(scope.getId())).hasSize(1);
        assertThat(children(scope.getId())).hasSize(3).allSatisfy(child -> assertThat(timers(child.getId())).isEmpty());
    }

    @Test
    void timerExpressionErrorBecomesIncidentOnHostAndResolveArmsIt() {
        UUID instance = start("boundary/service-task.bpmn");

        ActivityEntity failed = single(activities(instance, "charge"));
        assertThat(failed.getStatus()).isEqualTo(ActivityStatus.ERROR);
        assertThat(single(incidents(failed.getId())).getMessage()).contains("Boundary event 'deadline'");
        assertThat(timers(failed.getId())).isEmpty();
        assertThat(serviceTaskRepository.existsById(failed.getId())).isFalse();

        clock.advance(Duration.ofMinutes(5));
        Instant resolvedAt = clock.instant();
        UUID incidentId = single(incidents(failed.getId())).getId();
        inTx(() -> runtimeService.resolveIncident(incidentId, List.of(string("sla", "PT10M"))));

        assertThat(activityRepository.findById(failed.getId()).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        ActivityEntity charge = open(activities(instance, "charge"));
        assertThat(serviceTaskRepository.existsById(charge.getId())).isTrue();
        assertThat(single(timers(charge.getId())).getDueAt()).isEqualTo(resolvedAt.plus(Duration.ofMinutes(10)));
    }

    // ---------------------------------------------------------------- cancellation

    @Test
    void taskCompletedBeforeDueCancelsItsTimers() {
        UUID instance = start("boundary/interrupting-user-task.bpmn");
        UUID approveId = single(activities(instance, "approve")).getId();

        clock.advance(Duration.ofMinutes(10));
        completeUserTask(approveId);

        assertThat(timers(approveId)).extracting(TimerEntity::getStatus).containsOnly(TimerStatus.CANCELED);
        clock.advance(Duration.ofHours(3));
        fireAll();

        assertThat(activities(instance, "escalateTimer")).isEmpty();
        assertThat(activities(instance, "escalate")).isEmpty();
        assertThat(single(activities(instance, "done")).getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(completedAt(instance)).isNotNull();
    }

    @Test
    void multiInstanceTimerSurvivesCompletionOfOneInstance() {
        UUID instance = start("boundary/multi-instance.bpmn", json("items", "[1,2,3]"));
        ActivityEntity scope = scope(instance, "review");

        completeUserTask(children(scope.getId()).get(0).getId());

        assertThat(single(timers(scope.getId())).getStatus()).isEqualTo(TimerStatus.SCHEDULED);
    }

    @Test
    void completedMultiInstanceCancelsItsTimer() {
        UUID instance = start("boundary/multi-instance.bpmn", json("items", "[1,2]"));
        ActivityEntity scope = scope(instance, "review");

        children(scope.getId()).forEach(child -> completeUserTask(child.getId()));

        assertThat(single(timers(scope.getId())).getStatus()).isEqualTo(TimerStatus.CANCELED);
        assertThat(completedAt(instance)).isNotNull();
    }

    @Test
    void completedCallActivityCancelsItsTimer() {
        deploy("boundary/child.bpmn");
        UUID instance = start("boundary/call-activity.bpmn");
        ActivityEntity call = single(activities(instance, "call"));
        UUID child = childInstance(call.getId());

        completeUserTask(single(activities(child, "childTask")).getId());

        assertThat(single(timers(call.getId())).getStatus()).isEqualTo(TimerStatus.CANCELED);
        assertThat(activityRepository.findById(call.getId()).orElseThrow().getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(completedAt(instance)).isNotNull();
    }

    @Test
    void resolveWithReexecutionCancelsOldTimersAndArmsNewOnes() {
        UUID instance = start("boundary/assignment-error.bpmn", longValue("approver", 5));
        ActivityEntity failed = single(activities(instance, "approve"));
        assertThat(failed.getStatus()).isEqualTo(ActivityStatus.ERROR);
        // The host with an incident is still open, so its timer stays armed.
        assertThat(single(timers(failed.getId())).getStatus()).isEqualTo(TimerStatus.SCHEDULED);

        clock.advance(Duration.ofMinutes(30));
        Instant resolvedAt = clock.instant();
        UUID incidentId = single(incidents(failed.getId())).getId();
        inTx(() -> runtimeService.resolveIncident(incidentId, List.of(string("approver", "alice"))));

        assertThat(single(timers(failed.getId())).getStatus()).isEqualTo(TimerStatus.CANCELED);
        ActivityEntity approve = open(activities(instance, "approve"));
        assertThat(single(timers(approve.getId())).getDueAt()).isEqualTo(resolvedAt.plus(Duration.ofHours(1)));
    }

    // ---------------------------------------------------------------- interrupting

    @Test
    void interruptingTimerEscalatesOverdueTask() {
        UUID instance = start("boundary/interrupting-user-task.bpmn");
        UUID approveId = single(activities(instance, "approve")).getId();

        clock.advance(Duration.ofMinutes(59));
        assertThat(fireAll()).isZero();

        clock.advance(Duration.ofMinutes(1));
        fireAll();

        ActivityEntity approve = activityRepository.findById(approveId).orElseThrow();
        assertThat(approve.getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        assertThat(approve.getCompletedAt()).isNotNull();
        assertThat(userTaskRepository.findById(approveId).orElseThrow().getCanceledAt()).isNotNull();

        ActivityEntity timerActivity = single(activities(instance, "escalateTimer"));
        assertThat(timerActivity.getType()).isEqualTo(BpmnElementType.TIMER_BOUNDARY_EVENT);
        assertThat(timerActivity.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(timerActivity.getToken()).isEqualTo(approve.getToken());
        assertThat(single(activities(instance, "escalate")).getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(activities(instance, "done")).isEmpty();

        assertThat(timers(approveId)).extracting(TimerEntity::getBpmnElementId, TimerEntity::getStatus)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("escalateTimer", TimerStatus.FIRED),
                org.assertj.core.groups.Tuple.tuple("giveUpTimer", TimerStatus.CANCELED));

        clock.advance(Duration.ofHours(2));
        fireAll();
        assertThat(activities(instance, "giveUpTimer")).isEmpty();
        assertThat(activities(instance, "gaveUp")).isEmpty();
    }

    @Test
    void completingCanceledUserTaskIsRejected() {
        UUID instance = start("boundary/interrupting-user-task.bpmn");
        UUID approveId = single(activities(instance, "approve")).getId();
        clock.advance(Duration.ofHours(1));
        fireAll();

        assertThatThrownBy(() -> completeUserTask(approveId, string("approved", "yes")))
            .isInstanceOf(TaskNotActiveException.class);

        assertThat(activities(instance, "done")).isEmpty();
    }

    @Test
    void lateWorkerRepliesForInterruptedServiceTaskAreIgnored() {
        UUID instance = start("boundary/service-task.bpmn", string("sla", "PT10M"));
        UUID chargeId = single(activities(instance, "charge")).getId();
        clock.advance(Duration.ofMinutes(10));
        fireAll();

        assertThat(activityRepository.findById(chargeId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        assertThat(serviceTaskRepository.findById(chargeId).orElseThrow().getCanceledAt()).isNotNull();
        assertThat(serviceTaskRepository.findById(chargeId).orElseThrow().getCompletedAt()).isNotNull();
        assertThat(single(activities(instance, "timedOut")).getStatus()).isEqualTo(ActivityStatus.COMPLETED);

        ServiceTaskCompleted completed = new ServiceTaskCompleted();
        completed.setServiceTaskId(chargeId);
        completed.setVariables(List.of());
        serviceTaskCompleteListener.on(completed);
        ServiceTaskFailed failed = new ServiceTaskFailed();
        failed.setServiceTaskId(chargeId);
        failed.setMessage("too late");
        serviceTaskFailedListener.on(failed);

        assertThat(activities(instance, "done")).isEmpty();
        assertThat(incidents(chargeId)).isEmpty();
        assertThat(activityRepository.findById(chargeId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
    }

    @Test
    void redeliveredCompletionDoesNotAdvanceTwice() {
        UUID instance = start("boundary/service-task.bpmn", string("sla", "PT10M"));
        UUID chargeId = single(activities(instance, "charge")).getId();

        ServiceTaskCompleted completed = new ServiceTaskCompleted();
        completed.setServiceTaskId(chargeId);
        completed.setVariables(List.of());
        serviceTaskCompleteListener.on(completed);
        serviceTaskCompleteListener.on(completed);

        assertThat(activities(instance, "done")).hasSize(1);
    }

    @Test
    void interruptingTimerOnHostWithIncidentClosesIt() {
        UUID instance = start("boundary/service-task.bpmn", string("sla", "PT10M"));
        UUID chargeId = single(activities(instance, "charge")).getId();
        inTx(() -> activityService.failServiceTask(chargeId, "card declined"));
        assertThat(single(timers(chargeId)).getStatus()).isEqualTo(TimerStatus.SCHEDULED);

        clock.advance(Duration.ofMinutes(10));
        fireAll();

        assertThat(single(incidents(chargeId)).getCompletedAt()).isNotNull();
        assertThat(activityRepository.findById(chargeId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        assertThat(single(activities(instance, "timedOut")).getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(completedAt(instance)).isNotNull();
    }

    @Test
    void interruptingTimerTerminatesChildInstanceOfCallActivity() {
        deploy("boundary/child.bpmn");
        UUID instance = start("boundary/call-activity.bpmn");
        UUID callId = single(activities(instance, "call")).getId();
        UUID child = childInstance(callId);
        UUID childTaskId = single(activities(child, "childTask")).getId();

        clock.advance(Duration.ofHours(1));
        fireAll();

        assertThat(activityRepository.findById(callId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        assertThat(activityRepository.findById(childTaskId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        assertThat(userTaskRepository.findById(childTaskId).orElseThrow().getCanceledAt()).isNotNull();
        assertThat(completedAt(child)).isNotNull();
        assertThat(single(activities(instance, "timedOut")).getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(activities(instance, "done")).isEmpty();
        assertThat(completedAt(instance)).isNotNull();

        assertThatThrownBy(() -> completeUserTask(childTaskId)).isInstanceOf(TaskNotActiveException.class);
        assertThat(activities(instance, "done")).isEmpty();
    }

    @Test
    void interruptingTimerTerminatesNestedChildInstances() {
        deploy("boundary/child.bpmn");
        deploy("boundary/child-nested.bpmn");
        UUID instance = start("boundary/call-activity-nested.bpmn");
        UUID callId = single(activities(instance, "call")).getId();
        UUID child = childInstance(callId);
        UUID innerCallId = single(activities(child, "innerCall")).getId();
        UUID grandchild = childInstance(innerCallId);
        UUID deepTaskId = single(activities(grandchild, "childTask")).getId();

        clock.advance(Duration.ofHours(1));
        fireAll();

        assertThat(activityRepository.findById(innerCallId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        assertThat(activityRepository.findById(deepTaskId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        assertThat(userTaskRepository.findById(deepTaskId).orElseThrow().getCanceledAt()).isNotNull();
        assertThat(completedAt(child)).isNotNull();
        assertThat(completedAt(grandchild)).isNotNull();
        assertThat(single(activities(instance, "timedOut")).getStatus()).isEqualTo(ActivityStatus.COMPLETED);
    }

    @Test
    void interruptingTimerTerminatesMultiInstance() {
        UUID instance = start("boundary/multi-instance.bpmn", json("items", "[1,2,3]"));
        ActivityEntity scope = scope(instance, "review");
        List<ActivityEntity> children = children(scope.getId());
        completeUserTask(children.get(0).getId());

        clock.advance(Duration.ofHours(1));
        fireAll();

        assertThat(activityRepository.findById(scope.getId()).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        assertThat(activityRepository.findById(children.get(0).getId()).orElseThrow().getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        for (ActivityEntity open : children.subList(1, 3)) {
            assertThat(activityRepository.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
            assertThat(userTaskRepository.findById(open.getId()).orElseThrow().getCanceledAt()).isNotNull();
        }
        assertThat(single(activities(instance, "timedOut")).getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(activities(instance, "done")).isEmpty();
        assertThat(completedAt(instance)).isNotNull();
    }

    // ---------------------------------------------------------------- non-interrupting

    @Test
    void nonInterruptingTimerStartsParallelPathAndKeepsTheTask() {
        UUID instance = start("boundary/non-interrupting-user-task.bpmn");
        ActivityEntity approve = single(activities(instance, "approve"));

        clock.advance(Duration.ofHours(1));
        fireAll();

        assertThat(activityRepository.findById(approve.getId()).orElseThrow().getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(userTaskRepository.findById(approve.getId()).orElseThrow().getCanceledAt()).isNull();
        ActivityEntity timerActivity = single(activities(instance, "remindTimer"));
        assertThat(timerActivity.getToken()).isNotEqualTo(approve.getToken());
        ActivityEntity remind = single(activities(instance, "remind"));
        assertThat(remind.getToken()).isEqualTo(timerActivity.getToken());
        assertThat(single(timers(approve.getId())).getStatus()).isEqualTo(TimerStatus.FIRED);

        // The reminder branch ends first: the instance waits for the task.
        inTx(() -> runtimeService.completeServiceTask(remind.getId(), List.of()));
        assertThat(single(activities(instance, "reminded")).getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(completedAt(instance)).isNull();

        completeUserTask(approve.getId());
        assertThat(completedAt(instance)).isNotNull();
    }

    @Test
    void boundedCycleFiresExactlyItsRepetitions() {
        UUID instance = start("boundary/cycle-user-task.bpmn", string("cycle", "R3/PT1H"));
        UUID approveId = single(activities(instance, "approve")).getId();

        for (int hour = 0; hour < 5; hour++) {
            clock.advance(Duration.ofHours(1));
            fireAll();
        }

        assertThat(activities(instance, "remindTimer")).hasSize(3);
        assertThat(activities(instance, "reminded")).hasSize(3);
        assertThat(single(timers(approveId)).getStatus()).isEqualTo(TimerStatus.FIRED);
        assertThat(activityRepository.findById(approveId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(completedAt(instance)).isNull();
    }

    @Test
    void overdueCycleCatchesUpOnTheSameSchedule() {
        Instant enteredAt = clock.instant();
        UUID instance = start("boundary/cycle-user-task.bpmn", string("cycle", "R3/PT1H"));
        UUID approveId = single(activities(instance, "approve")).getId();

        clock.advance(Duration.ofHours(5));
        fireAll();

        assertThat(activities(instance, "reminded")).hasSize(3);
        assertThat(single(timers(approveId)).getDueAt()).isEqualTo(enteredAt.plus(Duration.ofHours(3)));
    }

    @Test
    void unboundedCycleStopsWhenTheTaskIsCompleted() {
        UUID instance = start("boundary/cycle-user-task.bpmn", string("cycle", "R/PT1H"));
        UUID approveId = single(activities(instance, "approve")).getId();

        clock.advance(Duration.ofHours(1));
        fireAll();
        clock.advance(Duration.ofHours(1));
        fireAll();
        clock.advance(Duration.ofMinutes(30));
        completeUserTask(approveId);
        clock.advance(Duration.ofHours(3));
        fireAll();

        assertThat(activities(instance, "reminded")).hasSize(2);
        assertThat(single(timers(approveId)).getStatus()).isEqualTo(TimerStatus.CANCELED);
        assertThat(completedAt(instance)).isNotNull();
    }

    @Test
    void timerOfClosedHostIsCanceledWithoutEffects() {
        UUID instance = start("boundary/interrupting-user-task.bpmn");
        UUID approveId = single(activities(instance, "approve")).getId();
        // Simulates a completion path that missed cancelling the timer.
        inTx(() -> activityRepository.setStatusAndCompletedAt(approveId, ActivityStatus.COMPLETED, Instant.now()));

        clock.advance(Duration.ofHours(1));
        fireAll();

        assertThat(timers(approveId)).filteredOn(t -> t.getBpmnElementId().equals("escalateTimer"))
            .extracting(TimerEntity::getStatus).containsExactly(TimerStatus.CANCELED);
        assertThat(activities(instance, "escalateTimer")).isEmpty();
    }

    // ---------------------------------------------------------------- instance completion

    @Test
    void parallelBranchesWithOwnEndEventsCompleteTheInstanceAtTheLastOne() {
        UUID instance = start("boundary/parallel-ends.bpmn");
        assertThat(single(activities(instance, "fork")).getStatus()).isEqualTo(ActivityStatus.COMPLETED);

        completeUserTask(single(activities(instance, "taskA")).getId());
        assertThat(single(activities(instance, "endA")).getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(completedAt(instance)).isNull();
        assertThat(single(activities(instance, "taskB")).getStatus()).isEqualTo(ActivityStatus.CREATED);

        completeUserTask(single(activities(instance, "taskB")).getId());
        assertThat(completedAt(instance)).isNotNull();
    }

    @Test
    void openIncidentKeepsTheInstanceOpen() {
        UUID instance = start("integration/incident-parallel.bpmn");

        completeUserTask(single(activities(instance, "goodTask")).getId());

        assertThat(single(activities(instance, "endGood")).getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(completedAt(instance)).isNull();
    }

    @Test
    void childWithOpenBranchDoesNotCompleteTheCallActivity() {
        deploy("boundary/child-parallel-ends.bpmn");
        UUID instance = start("boundary/call-parallel-child.bpmn");
        UUID callId = single(activities(instance, "call")).getId();
        UUID child = childInstance(callId);

        completeUserTask(single(activities(child, "taskA")).getId());

        assertThat(completedAt(child)).isNull();
        assertThat(activityRepository.findById(callId).orElseThrow().getCompletedAt()).isNull();
        assertThat(activities(instance, "done")).isEmpty();

        completeUserTask(single(activities(child, "taskB")).getId());

        assertThat(completedAt(child)).isNotNull();
        assertThat(activityRepository.findById(callId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(completedAt(instance)).isNotNull();
    }

    // ---------------------------------------------------------------- helpers

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

    private void completeUserTask(UUID id, ProcessVariable... variables) {
        inTx(() -> runtimeService.completeUserTask(id, List.of(variables)));
    }

    private List<ActivityEntity> activities(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> processInstanceId.equals(a.getProcessInstanceId()) && bpmnElementId.equals(a.getBpmnElementId()))
            .sorted(Comparator.comparing(ActivityEntity::getCreatedAt))
            .toList();
    }

    private ActivityEntity scope(UUID processInstanceId, String bpmnElementId) {
        return single(activities(processInstanceId, bpmnElementId).stream().filter(a -> a.getParentActivityId() == null).toList());
    }

    private List<ActivityEntity> children(UUID scopeId) {
        return activityRepository.findAll().stream()
            .filter(a -> scopeId.equals(a.getParentActivityId()))
            .sorted(Comparator.comparing(ActivityEntity::getLoopIndex))
            .toList();
    }

    private static ActivityEntity open(List<ActivityEntity> activities) {
        return single(activities.stream().filter(a -> a.getCompletedAt() == null).toList());
    }

    private List<TimerEntity> timers(UUID activityId) {
        return timerRepository.findByActivityId(activityId);
    }

    private List<IncidentEntity> incidents(UUID activityId) {
        return incidentRepository.findAll().stream().filter(i -> activityId.equals(i.getActivityId())).toList();
    }

    private UUID childInstance(UUID callActivityId) {
        return processInstanceRepository.findFirstByParentActivityId(callActivityId).map(ProcessInstanceEntity::getId).orElseThrow();
    }

    private Instant completedAt(UUID processInstanceId) {
        return processInstanceRepository.findById(processInstanceId).orElseThrow().getCompletedAt();
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

    private static ProcessVariable string(String name, String value) {
        return variable(name, ProcessVariableType.STRING, value);
    }

    private static ProcessVariable longValue(String name, long value) {
        return variable(name, ProcessVariableType.LONG, String.valueOf(value));
    }

    private static ProcessVariable json(String name, String value) {
        return variable(name, ProcessVariableType.JSON, value);
    }

    private static ProcessVariable variable(String name, ProcessVariableType type, String value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(type);
        variable.setValue(value);
        return variable;
    }
}
