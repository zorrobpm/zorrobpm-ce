package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.listener.ServiceTaskBpmnErrorThrownListener;
import com.zorrodev.bpm.engine.listener.ServiceTaskCompleteListener;
import com.zorrodev.bpm.engine.listener.ServiceTaskFailedListener;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.exchange.ServiceTaskBpmnErrorThrown;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import com.zorrodev.bpm.exchange.ServiceTaskFailed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Worker replies for a service task the engine does not know: the queue listener must be able to
 * acknowledge them, so the event listeners swallow them without touching the data. Not
 * @Transactional: every reply runs in a transaction of its own, as it does from the queue.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class ServiceTaskUnknownResultIntegrationTests {

    private static final String PROCESS = "retry/service-task-no-retries.bpmn";

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ServiceTaskCompleteListener serviceTaskCompleteListener;

    @Autowired
    private ServiceTaskFailedListener serviceTaskFailedListener;

    @Autowired
    private ServiceTaskBpmnErrorThrownListener serviceTaskBpmnErrorThrownListener;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void repliesForUnknownServiceTaskAreIgnored() {
        UUID unknown = UUID.randomUUID();
        long incidentsBefore = incidentRepository.count();
        long activitiesBefore = activityRepository.count();

        assertThatCode(() -> complete(unknown)).doesNotThrowAnyException();
        assertThatCode(() -> fail(unknown)).doesNotThrowAnyException();
        assertThatCode(() -> throwBpmnError(unknown)).doesNotThrowAnyException();

        assertThat(incidentRepository.count()).isEqualTo(incidentsBefore);
        assertThat(activityRepository.count()).isEqualTo(activitiesBefore);
    }

    @Test
    void replyAfterUnknownOneCompletesServiceTask() {
        UUID instance = start();
        UUID chargeId = single(activities(instance, "charge")).getId();

        complete(UUID.randomUUID());
        complete(chargeId);

        assertThat(activityRepository.findById(chargeId).orElseThrow().getCompletedAt()).isNotNull();
        assertThat(activities(instance, "done")).hasSize(1);
    }

    @Test
    void repeatedSuccessAdvancesProcessOnce() {
        UUID instance = start();
        UUID chargeId = single(activities(instance, "charge")).getId();

        complete(chargeId);
        complete(chargeId);

        assertThat(activities(instance, "charge")).hasSize(1);
        assertThat(activities(instance, "done")).hasSize(1);
    }

    private void complete(UUID serviceTaskId) {
        ServiceTaskCompleted completed = new ServiceTaskCompleted();
        completed.setServiceTaskId(serviceTaskId);
        completed.setVariables(List.of());
        serviceTaskCompleteListener.on(completed);
    }

    private void fail(UUID serviceTaskId) {
        ServiceTaskFailed failed = new ServiceTaskFailed();
        failed.setServiceTaskId(serviceTaskId);
        failed.setErrorCode("java.io.IOException");
        failed.setMessage("reset");
        serviceTaskFailedListener.on(failed);
    }

    private void throwBpmnError(UUID serviceTaskId) {
        ServiceTaskBpmnErrorThrown thrown = new ServiceTaskBpmnErrorThrown();
        thrown.setServiceTaskId(serviceTaskId);
        thrown.setErrorCode("CUSTOMER_NOT_FOUND");
        thrown.setVariables(List.of());
        serviceTaskBpmnErrorThrownListener.on(thrown);
    }

    private UUID start() {
        return inTx(() -> {
            try {
                UUID definitionId = processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/" + PROCESS))).getId();
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(definitionId);
                dto.setVariables(List.of());
                return runtimeService.startProcessInstance(dto).getId();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private List<ActivityEntity> activities(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> processInstanceId.equals(a.getProcessInstanceId()) && bpmnElementId.equals(a.getBpmnElementId()))
            .toList();
    }

    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private static <T> T single(List<T> items) {
        assertThat(items).hasSize(1);
        return items.get(0);
    }
}
