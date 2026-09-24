package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.TimerStatus;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.contract.exception.VariableMappingException;
import com.zorrodev.bpm.engine.listener.ServiceTaskCompleteListener;
import com.zorrodev.bpm.engine.listener.ServiceTaskFailedListener;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.TimerRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.InputMappingFailureService;
import com.zorrodev.bpm.engine.service.JobDetailFactory;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.engine.service.TimerJobService;
import com.zorrodev.bpm.engine.service.impl.ServiceTaskEnqueueServiceImpl;
import com.zorrodev.bpm.engine.test.MutableClock;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import com.zorrodev.bpm.exchange.ServiceTaskFailed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Input mapping on service tasks, user tasks and call activities, end to end on the engine. The
 * job of a service task is built with the same factory both transports use.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class InputMappingIntegrationTests {

    private static final String SERVICE = "input-mapping/service-task.bpmn";
    private static final String SERVICE_FAILING = "input-mapping/service-task-failing.bpmn";
    private static final String SERVICE_PLAIN = "retry/service-task-no-retries.bpmn";
    private static final String USER = "input-mapping/user-task.bpmn";
    private static final String USER_FAILING = "input-mapping/user-task-failing.bpmn";
    private static final String USER_MULTI = "input-mapping/user-task-multi.bpmn";
    private static final String CALL = "input-mapping/call.bpmn";
    private static final String CALL_FAILING = "input-mapping/call-failing.bpmn";
    private static final String CHILD = "input-mapping/child.bpmn";

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private DBService dbService;
    @Autowired private JobDetailFactory jobDetailFactory;
    @Autowired private InputMappingFailureService inputMappingFailureService;
    @Autowired private TimerJobService timerJobService;
    @Autowired private ServiceTaskCompleteListener serviceTaskCompleteListener;
    @Autowired private ServiceTaskFailedListener serviceTaskFailedListener;
    @MockitoSpyBean private ServiceTaskEnqueueService serviceTaskEnqueueService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private ServiceTaskRepository serviceTaskRepository;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private TimerRepository timerRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private MutableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void cancelLeftoverTimers() {
        inTx(() -> timerRepository.findAll().stream()
            .filter(t -> t.getStatus() == TimerStatus.SCHEDULED)
            .forEach(t -> {
                t.setStatus(TimerStatus.CANCELED);
                timerRepository.save(t);
            }));
    }

    // ---------------------------------------------------------------- service task

    @Test
    void jobCarriesOnlyTheMappedVariables() {
        UUID instance = start(SERVICE, order(100), variable("customerId", "c1"));
        UUID chargeId = chargeId(instance);

        JobDetailModel job = job(chargeId);

        assertThat(job.getVariables()).containsOnlyKeys("amount", "currency");
        assertThat(job.getVariables().get("amount").getValue()).isEqualTo("100");
        assertThat(job.getVariables().get("amount").getType()).isEqualTo("LONG");
        assertThat(job.getVariables().get("currency").getValue()).isEqualTo("KZT");
        assertThat(job.getVariables().get("currency").getType()).isEqualTo("STRING");
        verify(serviceTaskEnqueueService, times(1)).enqueueAfterCommit(chargeId);
    }

    @Test
    void withoutMappingJobCarriesAllVariables() {
        UUID instance = start(SERVICE_PLAIN, order(100), variable("customerId", "c1"));

        assertThat(job(chargeId(instance)).getVariables()).containsOnlyKeys("order", "customerId");
    }

    @Test
    void instanceVariablesAreNotChangedAndWorkerResultIsWrittenAsBefore() {
        UUID instance = start(SERVICE, order(100));
        UUID chargeId = chargeId(instance);
        job(chargeId);
        assertThat(names(instance)).containsExactly("order");

        complete(chargeId, variable("paid", ProcessVariableType.BOOLEAN, "true"));

        assertThat(names(instance)).containsExactlyInAnyOrder("order", "paid");
        assertThat(activities(instance, "done")).hasSize(1);
    }

    @Test
    void retryRebuildsTheJobOnTheCurrentVariables() {
        UUID instance = start(SERVICE, order(100));
        UUID chargeId = chargeId(instance);
        fail(chargeId, null);
        inTx(() -> dbService.setVariables(instance, List.of(order(120))));

        clock.advance(Duration.ofMinutes(1));
        assertThat(fireAll()).isEqualTo(1);

        verify(serviceTaskEnqueueService, times(2)).enqueueAfterCommit(chargeId);
        assertThat(job(chargeId).getVariables().get("amount").getValue()).isEqualTo("120");
    }

    @Test
    void resolveRebuildsTheJobOnTheResolvedVariables() {
        UUID instance = start(SERVICE, order(100));
        UUID chargeId = chargeId(instance);
        fail(chargeId, 0);
        UUID incidentId = single(incidents(chargeId)).getId();

        inTx(() -> runtimeService.resolveIncident(incidentId, List.of(order(150))));

        verify(serviceTaskEnqueueService, times(2)).enqueueAfterCommit(chargeId);
        assertThat(job(chargeId).getVariables().get("amount").getValue()).isEqualTo("150");
    }

    @Test
    void mappingFailureOnEntryIsAnEngineIncidentWithoutAJob() {
        UUID instance = start(SERVICE_FAILING);
        ActivityEntity charge = single(activities(instance, "charge"));

        assertThat(charge.getStatus()).isEqualTo(ActivityStatus.ERROR);
        IncidentEntity incident = single(incidents(charge.getId()));
        assertThat(incident.getMessage()).contains("Input 'amount' of 'charge'");
        assertThat(serviceTaskRepository.findById(charge.getId())).isEmpty();
        verify(serviceTaskEnqueueService, times(0)).enqueueAfterCommit(charge.getId());

        inTx(() -> runtimeService.resolveIncident(incident.getId(), List.of(order(100))));

        // The element is executed afresh: a new service task with a job.
        List<ActivityEntity> charges = activities(instance, "charge");
        assertThat(charges).hasSize(2);
        UUID chargeId = charges.get(1).getId();
        assertThat(serviceTaskRepository.findById(chargeId)).isPresent();
        verify(serviceTaskEnqueueService, times(1)).enqueueAfterCommit(chargeId);
        assertThat(job(chargeId).getVariables().get("amount").getValue()).isEqualTo("100");
    }

    @Test
    void mappingFailureWhenTheJobIsBuiltOpensIncidentWithoutRetries() {
        UUID instance = start(SERVICE_FAILING, order(100));
        UUID chargeId = chargeId(instance);
        assertThat(job(chargeId).getVariables().get("amount").getValue()).isEqualTo("100");
        // A string has no 'total': the assertion of the mapping fails from now on.
        inTx(() -> dbService.setVariables(instance, List.of(variable("order", "abc"))));
        List<Object> published = new ArrayList<>();
        ApplicationEventPublisher publisher = published::add;

        new ServiceTaskEnqueueServiceImpl(jobDetailFactory, publisher, inputMappingFailureService).publishJob(chargeId);

        assertThat(published).isEmpty();
        IncidentEntity incident = single(incidents(chargeId));
        assertThat(incident.getErrorCode()).isEqualTo("INPUT_MAPPING_FAILED");
        assertThat(incident.getMessage()).contains("Input 'amount' of 'charge'");
        assertThat(activityRepository.findById(chargeId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.ERROR);
        // Like any incident of a service task: no retry is scheduled, and the resolve restores the counter.
        assertThat(serviceTaskRepository.findById(chargeId).orElseThrow().getNextRetryAt()).isNull();
        assertThat(timerRepository.findByActivityId(chargeId)).isEmpty();

        inTx(() -> runtimeService.resolveIncident(incident.getId(), List.of(order(100))));

        assertThat(serviceTaskRepository.findById(chargeId).orElseThrow().getRetries()).isEqualTo(2);
        verify(serviceTaskEnqueueService, times(2)).enqueueAfterCommit(chargeId);
        assertThat(job(chargeId).getVariables().get("amount").getValue()).isEqualTo("100");
    }

    @Test
    void repeatedMappingFailureWhileTheIncidentIsOpenChangesNothing() {
        UUID instance = start(SERVICE_FAILING, order(100));
        UUID chargeId = chargeId(instance);
        inTx(() -> dbService.setVariables(instance, List.of(variable("order", "abc"))));
        VariableMappingException failure = catchThrowableOfType(VariableMappingException.class, () -> job(chargeId));
        assertThat(failure).isNotNull();

        inputMappingFailureService.reportJobInputMappingFailure(chargeId, failure);
        inputMappingFailureService.reportJobInputMappingFailure(chargeId, failure);

        assertThat(incidents(chargeId)).hasSize(1);
    }

    // ---------------------------------------------------------------- call activity

    @Test
    void childStartsWithTheMappedVariablesOnly() {
        UUID parent = start(CALL, order(100), variable("secret", "s"));
        UUID child = child(parent);

        assertThat(dbService.getVariables(child)).extracting(ProcessVariable::getName, ProcessVariable::getValue)
            .containsExactly(tuple("orderId", "A-1"));

        UUID childTask = single(activities(child, "childTask")).getId();
        inTx(() -> runtimeService.completeUserTask(childTask, List.of(variable("result", "ok"))));

        assertThat(names(parent)).containsExactlyInAnyOrder("order", "secret", "orderId", "result");
        assertThat(activities(parent, "done")).hasSize(1);
    }

    @Test
    void callActivityMappingFailureIsAnIncidentWithoutAChild() {
        UUID parent = start(CALL_FAILING);
        ActivityEntity call = single(activities(parent, "call"));

        assertThat(call.getStatus()).isEqualTo(ActivityStatus.ERROR);
        assertThat(single(incidents(call.getId())).getMessage()).contains("Input 'orderId' of 'call'");
        assertThat(processInstanceRepository.findAll()).noneMatch(pi -> call.getId().equals(pi.getParentActivityId()));
    }

    // ---------------------------------------------------------------- user task

    @Test
    void userTaskKeepsItsInputs() {
        UUID instance = start(USER, order(100));
        UserTaskEntity task = single(userTasks(instance));

        UserTask dto = userTask(task.getId());
        assertThat(dto.getInputs()).extracting(ProcessVariable::getName, ProcessVariable::getValue, ProcessVariable::getType)
            .containsExactly(tuple("orderId", "A-1", ProcessVariableType.STRING));
        assertThat(names(instance)).containsExactly("order");

        inTx(() -> dbService.setVariables(instance, List.of(variable("order", ProcessVariableType.JSON, "{\"id\":\"B-2\"}"))));
        assertThat(userTask(task.getId()).getInputs()).extracting(ProcessVariable::getValue).containsExactly("A-1");

        inTx(() -> runtimeService.completeUserTask(task.getId(), List.of(variable("approved", ProcessVariableType.BOOLEAN, "true"))));
        assertThat(names(instance)).containsExactlyInAnyOrder("order", "approved");
        assertThat(activities(instance, "done")).hasSize(1);
    }

    @Test
    void userTaskWithoutMappingHasNoInputs() {
        UUID parent = start(CALL, order(100));
        UUID childTask = single(activities(child(parent), "childTask")).getId();

        assertThat(userTaskRepository.findById(childTask).orElseThrow().getInputs()).isNull();
        assertThat(userTask(childTask).getInputs()).isEmpty();
    }

    @Test
    void multiInstanceUserTaskMapsEachInstance() {
        UUID instance = start(USER_MULTI, variable("items", ProcessVariableType.JSON, "[\"a\",\"b\"]"));

        List<UserTaskEntity> tasks = userTasks(instance).stream().sorted(Comparator.comparing(UserTaskEntity::getLoopIndex)).toList();
        assertThat(tasks).hasSize(2);
        assertThat(userTask(tasks.get(0).getId()).getInputs()).extracting(ProcessVariable::getName, ProcessVariable::getValue)
            .containsExactly(tuple("code", "a"), tuple("n", "1"));
        assertThat(userTask(tasks.get(1).getId()).getInputs()).extracting(ProcessVariable::getName, ProcessVariable::getValue)
            .containsExactly(tuple("code", "b"), tuple("n", "2"));
    }

    @Test
    void userTaskMappingFailureIsAnIncidentWithoutATask() {
        UUID instance = start(USER_FAILING);
        ActivityEntity review = single(activities(instance, "review"));

        assertThat(review.getStatus()).isEqualTo(ActivityStatus.ERROR);
        assertThat(single(incidents(review.getId())).getMessage()).contains("Input 'orderId' of 'review'");
        assertThat(userTasks(instance)).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private JobDetailModel job(UUID serviceTaskId) {
        return inTx(() -> jobDetailFactory.create(serviceTaskId));
    }

    private void fail(UUID serviceTaskId, Integer retries) {
        ServiceTaskFailed failed = new ServiceTaskFailed();
        failed.setServiceTaskId(serviceTaskId);
        failed.setErrorCode("java.io.IOException");
        failed.setMessage("reset");
        failed.setRetries(retries);
        serviceTaskFailedListener.on(failed);
    }

    private void complete(UUID serviceTaskId, ProcessVariable... variables) {
        ServiceTaskCompleted completed = new ServiceTaskCompleted();
        completed.setServiceTaskId(serviceTaskId);
        completed.setVariables(List.of(variables).stream().map(v -> {
            com.zorrodev.bpm.exchange.ProcessVariable pv = new com.zorrodev.bpm.exchange.ProcessVariable();
            pv.setName(v.getName());
            pv.setValue(v.getValue());
            pv.setType(v.getType().name());
            return pv;
        }).toList());
        serviceTaskCompleteListener.on(completed);
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
                if (file.startsWith("input-mapping/call")) {
                    processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/" + CHILD)));
                }
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

    private UUID child(UUID parent) {
        UUID callId = single(activities(parent, "call")).getId();
        return single(processInstanceRepository.findAll().stream().filter(pi -> callId.equals(pi.getParentActivityId())).toList()).getId();
    }

    private UserTask userTask(UUID id) {
        return inTx(() -> queryService.getUserTask(id));
    }

    private List<UserTaskEntity> userTasks(UUID processInstanceId) {
        return userTaskRepository.findAll().stream().filter(t -> processInstanceId.equals(t.getProcessInstanceId())).toList();
    }

    private List<String> names(UUID processInstanceId) {
        return dbService.getVariables(processInstanceId).stream().map(ProcessVariable::getName).toList();
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

    private static ProcessVariable order(int total) {
        return variable("order", ProcessVariableType.JSON, "{\"id\":\"A-1\",\"total\":" + total + "}");
    }

    private static ProcessVariable variable(String name, String value) {
        return variable(name, ProcessVariableType.STRING, value);
    }

    private static ProcessVariable variable(String name, ProcessVariableType type, String value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(type);
        variable.setValue(value);
        return variable;
    }
}
