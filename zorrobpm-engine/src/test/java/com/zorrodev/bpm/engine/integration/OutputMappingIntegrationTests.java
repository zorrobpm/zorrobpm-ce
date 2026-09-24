package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.exception.VariableMappingException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.TimerStatus;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.listener.ServiceTaskCompleteListener;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.TimerRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Output mapping on service tasks, user tasks and call activities, end to end on the engine. */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class OutputMappingIntegrationTests {

    private static final String SERVICE = "output-mapping/service-task.bpmn";
    private static final String SERVICE_FAILING = "output-mapping/service-task-failing.bpmn";
    private static final String SERVICE_ERROR = "output-mapping/service-task-error.bpmn";
    private static final String SERVICE_PLAIN = "retry/service-task-no-retries.bpmn";
    private static final String USER = "output-mapping/user-task.bpmn";
    private static final String USER_FAILING = "output-mapping/user-task-failing.bpmn";
    private static final String USER_MULTI = "output-mapping/user-task-multi.bpmn";
    private static final String CALL = "output-mapping/call.bpmn";
    private static final String CALL_FAILING = "output-mapping/call-failing.bpmn";
    private static final String CHILD = "output-mapping/child.bpmn";

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private ActivityService activityService;
    @Autowired private DBService dbService;
    @Autowired private ServiceTaskCompleteListener serviceTaskCompleteListener;
    @MockitoSpyBean private ServiceTaskEnqueueService serviceTaskEnqueueService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private ServiceTaskRepository serviceTaskRepository;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private TimerRepository timerRepository;
    @Autowired private IncidentRepository incidentRepository;
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
    void onlyTheMappedResultIsWritten() {
        UUID instance = start(SERVICE, order(100), variable("fee", ProcessVariableType.LONG, "5"));
        UUID chargeId = chargeId(instance);

        complete(chargeId, variable("result", ProcessVariableType.JSON, "{\"transactionId\":\"T-1\"}"),
            variable("debug", "trace"), variable("fee", ProcessVariableType.LONG, "7"));

        Map<String, ProcessVariable> vars = variables(instance);
        assertThat(vars).containsOnlyKeys("order", "fee", "paymentId", "charged");
        assertThat(vars.get("paymentId").getValue()).isEqualTo("T-1");
        assertThat(vars.get("paymentId").getType()).isEqualTo(ProcessVariableType.STRING);
        // The result's fee (7) shadows the instance's fee (5) in the mapping context; fee itself is not written.
        assertThat(vars.get("charged").getValue()).isEqualTo("107");
        assertThat(vars.get("fee").getValue()).isEqualTo("5");
        assertThat(serviceTask(chargeId).getCompletedAt()).isNotNull();
        assertThat(activities(instance, "done")).hasSize(1);
    }

    @Test
    void withoutMappingTheWholeResultIsWritten() {
        UUID instance = start(SERVICE_PLAIN, order(100));

        complete(chargeId(instance), variable("paid", ProcessVariableType.BOOLEAN, "true"), variable("debug", "trace"));

        assertThat(variables(instance)).containsOnlyKeys("order", "paid", "debug");
    }

    @Test
    void mappingFailureOpensIncidentAndKeepsTheTaskOpen() {
        UUID instance = start(SERVICE_FAILING, order(100), variable("fee", ProcessVariableType.LONG, "5"));
        UUID chargeId = chargeId(instance);

        complete(chargeId, variable("debug", "trace"));

        IncidentEntity incident = single(incidents(chargeId));
        assertThat(incident.getErrorCode()).isEqualTo("OUTPUT_MAPPING_FAILED");
        assertThat(incident.getMessage()).contains("Output 'paymentId' of 'charge'");
        assertThat(incident.getCompletedAt()).isNull();
        assertThat(activityRepository.findById(chargeId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.ERROR);
        assertThat(serviceTask(chargeId).getCompletedAt()).isNull();
        assertThat(serviceTask(chargeId).getNextRetryAt()).isNull();
        assertThat(variables(instance)).containsOnlyKeys("order", "fee");
        assertThat(activities(instance, "done")).isEmpty();

        // A repeated failing result does not open a second incident.
        complete(chargeId, variable("debug", "again"));
        assertThat(incidents(chargeId)).hasSize(1);

        // A result the mapping accepts completes the task and closes the incident.
        complete(chargeId, variable("result", ProcessVariableType.JSON, "{\"transactionId\":\"T-2\"}"));
        assertThat(serviceTask(chargeId).getCompletedAt()).isNotNull();
        assertThat(single(incidents(chargeId)).getCompletedAt()).isNotNull();
        assertThat(variables(instance).get("paymentId").getValue()).isEqualTo("T-2");
        assertThat(activities(instance, "done")).hasSize(1);
    }

    @Test
    void resolvingTheMappingIncidentHandsTheJobToTheWorkerAgain() {
        UUID instance = start(SERVICE_FAILING, order(100), variable("fee", ProcessVariableType.LONG, "5"));
        UUID chargeId = chargeId(instance);
        complete(chargeId, variable("debug", "trace"));
        UUID incidentId = single(incidents(chargeId)).getId();

        inTx(() -> runtimeService.resolveIncident(incidentId, List.of(variable("retry", ProcessVariableType.BOOLEAN, "true"))));

        verify(serviceTaskEnqueueService, times(2)).enqueueAfterCommit(chargeId);
        assertThat(serviceTask(chargeId).getRetries()).isEqualTo(2);
        // Variables of the resolve are written as before, not through the mapping.
        assertThat(variables(instance)).containsKey("retry");
        complete(chargeId, variable("result", ProcessVariableType.JSON, "{\"transactionId\":\"T-3\"}"));
        assertThat(variables(instance).get("paymentId").getValue()).isEqualTo("T-3");
    }

    @Test
    void bpmnErrorVariablesAreWrittenWithoutTheMapping() {
        UUID instance = start(SERVICE_ERROR, order(100));
        UUID chargeId = chargeId(instance);

        inTx(() -> activityService.throwBpmnError(chargeId, "CARD_DECLINED", "declined", List.of(variable("reason", "limit"))));

        assertThat(variables(instance)).containsOnlyKeys("order", "reason");
        assertThat(activities(instance, "handled")).hasSize(1);
    }

    // ---------------------------------------------------------------- user task

    @Test
    void userTaskCompletionIsWrittenThroughTheMapping() {
        UUID instance = start(USER, order(100));
        UUID taskId = single(userTasks(instance)).getId();

        inTx(() -> runtimeService.completeUserTask(taskId, List.of(variable("decision", "approve"), variable("comment", "ok"))));

        Map<String, ProcessVariable> vars = variables(instance);
        assertThat(vars).containsOnlyKeys("order", "approved");
        assertThat(vars.get("approved").getType()).isEqualTo(ProcessVariableType.BOOLEAN);
        assertThat(vars.get("approved").getValue()).isEqualTo("true");
        assertThat(activities(instance, "done")).hasSize(1);
    }

    @Test
    void multiInstanceMapsEachInstanceAndKeepsTheOutputCollection() {
        UUID instance = start(USER_MULTI, variable("items", ProcessVariableType.JSON, "[\"a\",\"b\"]"));
        List<UserTaskEntity> tasks = userTasks(instance).stream().sorted(Comparator.comparing(UserTaskEntity::getLoopIndex)).toList();
        assertThat(tasks).hasSize(2);

        inTx(() -> runtimeService.completeUserTask(tasks.get(0).getId(), List.of(variable("score", ProcessVariableType.LONG, "3"))));
        assertThat(variables(instance).get("doubled").getValue()).isEqualTo("6");
        assertThat(variables(instance)).doesNotContainKey("score");

        inTx(() -> runtimeService.completeUserTask(tasks.get(1).getId(), List.of(variable("score", ProcessVariableType.LONG, "4"))));
        assertThat(variables(instance).get("doubled").getValue()).isEqualTo("8");
        assertThat(variables(instance).get("scores").getValue()).isEqualTo("[3,4]");
        assertThat(activities(instance, "done")).hasSize(1);
    }

    @Test
    void userTaskMappingFailureRejectsTheCompletion() {
        UUID instance = start(USER_FAILING, order(100));
        UUID taskId = single(userTasks(instance)).getId();

        assertThatThrownBy(() -> inTx(() -> runtimeService.completeUserTask(taskId, List.of(variable("comment", "ok")))))
            .isInstanceOf(VariableMappingException.class)
            .hasMessageContaining("Output 'approved' of 'review'");

        assertThat(userTaskRepository.findById(taskId).orElseThrow().getCompletedAt()).isNull();
        assertThat(variables(instance)).containsOnlyKeys("order");
        assertThat(activities(instance, "done")).isEmpty();
        assertThat(incidents(taskId)).isEmpty();
    }

    // ---------------------------------------------------------------- call activity

    @Test
    void onlyTheMappedChildVariablesReachTheParent() {
        UUID parent = start(CALL, order(100));
        UUID child = child(parent);
        UUID childTask = single(activities(child, "childTask")).getId();

        inTx(() -> runtimeService.completeUserTask(childTask, List.of(variable("result", "ok"), variable("internal", "x"))));

        assertThat(variables(parent)).containsOnlyKeys("order", "childResult");
        assertThat(variables(parent).get("childResult").getValue()).isEqualTo("ok");
        assertThat(activities(parent, "done")).hasSize(1);
        assertThat(processInstanceRepository.findById(child).orElseThrow().getCompletedAt()).isNotNull();
    }

    @Test
    void callActivityMappingFailureRejectsTheChildCompletion() {
        UUID parent = start(CALL_FAILING, order(100));
        UUID child = child(parent);
        UUID childTask = single(activities(child, "childTask")).getId();

        assertThatThrownBy(() -> inTx(() -> runtimeService.completeUserTask(childTask, List.of(variable("internal", "x")))))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("422")
            .hasMessageContaining("Output 'childResult' of 'call'");

        assertThat(userTaskRepository.findById(childTask).orElseThrow().getCompletedAt()).isNull();
        assertThat(processInstanceRepository.findById(child).orElseThrow().getCompletedAt()).isNull();
        assertThat(variables(child)).containsOnlyKeys("order");
        assertThat(variables(parent)).containsOnlyKeys("order");
        assertThat(activityRepository.findById(single(activities(parent, "call")).getId()).orElseThrow().getCompletedAt()).isNull();
        assertThat(activities(parent, "done")).isEmpty();
        assertThat(incidentRepository.findAll()).noneMatch(i -> child.equals(processInstanceOf(i.getActivityId())));
    }

    // ---------------------------------------------------------------- helpers

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

    private UUID start(String file, ProcessVariable... variables) {
        return inTx(() -> {
            try {
                if (file.startsWith("output-mapping/call")) {
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

    private UUID processInstanceOf(UUID activityId) {
        return activityRepository.findById(activityId).map(ActivityEntity::getProcessInstanceId).orElse(null);
    }

    private ServiceTaskEntity serviceTask(UUID id) {
        return serviceTaskRepository.findById(id).orElseThrow();
    }

    private List<UserTaskEntity> userTasks(UUID processInstanceId) {
        return userTaskRepository.findAll().stream().filter(t -> processInstanceId.equals(t.getProcessInstanceId())).toList();
    }

    private Map<String, ProcessVariable> variables(UUID processInstanceId) {
        return dbService.getVariables(processInstanceId).stream().collect(Collectors.toMap(ProcessVariable::getName, v -> v));
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
