package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.exception.IncidentAlreadyResolvedException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.exchange.ServiceTaskFailed;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class IncidentResolveIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private DBService dbService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private EntityManager entityManager;

    @MockitoSpyBean
    private ServiceTaskEnqueueService serviceTaskEnqueueService;

    @Transactional
    @Test
    void jobFailureCreatesIncidentAndResolveRequeuesJob() throws Exception {
        UUID processInstanceId = start("process2.bpmn");
        UUID serviceTaskId = singleOpenServiceTask(processInstanceId).getId();

        fail(serviceTaskId, "java.lang.IllegalArgumentException: card declined");
        fail(serviceTaskId, "java.lang.IllegalArgumentException: card declined");
        refresh();

        assertThat(activityRepository.findById(serviceTaskId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.ERROR);
        IncidentEntity incident = single(incidents(serviceTaskId));
        assertThat(incident.getMessage()).contains("card declined");
        assertThat(incident.getCompletedAt()).isNull();
        assertThat(singleOpenServiceTask(processInstanceId).getId()).isEqualTo(serviceTaskId);
        assertThat(activities(processInstanceId, "endEvent")).isEmpty();

        runtimeService.resolveIncident(incident.getId(), List.of(variable("retry", ProcessVariableType.BOOLEAN, "true")));
        refresh();

        assertThat(incidentRepository.findById(incident.getId()).orElseThrow().getCompletedAt()).isNotNull();
        assertThat(activityRepository.findById(serviceTaskId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.CREATED);
        assertThat(singleOpenServiceTask(processInstanceId).getId()).isEqualTo(serviceTaskId);
        assertThat(dbService.getVariables(processInstanceId)).extracting(ProcessVariable::getName).contains("retry");
        verify(serviceTaskEnqueueService, times(2)).enqueueAfterCommit(serviceTaskId);

        runtimeService.completeServiceTask(serviceTaskId, List.of());
        refresh();

        assertThat(processInstanceRepository.findById(processInstanceId).orElseThrow().getCompletedAt()).isNotNull();
    }

    @Transactional
    @Test
    void completingFailedServiceTaskClosesIncident() throws Exception {
        UUID processInstanceId = start("process2.bpmn");
        UUID serviceTaskId = singleOpenServiceTask(processInstanceId).getId();
        fail(serviceTaskId, "boom");

        runtimeService.completeServiceTask(serviceTaskId, List.of());
        refresh();

        assertThat(single(incidents(serviceTaskId)).getCompletedAt()).isNotNull();
        assertThat(activityRepository.findById(serviceTaskId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.COMPLETED);
        assertThat(processInstanceRepository.findById(processInstanceId).orElseThrow().getCompletedAt()).isNotNull();
    }

    @Transactional
    @Test
    void resolveWithFixingVariableRetriesElement() throws Exception {
        UUID processInstanceId = start("test16.bpmn", variable("approver", ProcessVariableType.LONG, "5"));
        refresh();
        ActivityEntity failed = single(activities(processInstanceId, "approveTask"));
        IncidentEntity incident = single(incidents(failed.getId()));

        runtimeService.resolveIncident(incident.getId(), List.of(variable("approver", ProcessVariableType.STRING, "alice")));
        refresh();

        assertThat(incidentRepository.findById(incident.getId()).orElseThrow().getCompletedAt()).isNotNull();
        assertThat(activityRepository.findById(failed.getId()).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        UserTask task = single(openUserTasks(processInstanceId));
        assertThat(task.getAssignee()).isEqualTo("alice");
        assertThat(incidents(task.getId())).isEmpty();
    }

    @Transactional
    @Test
    void resolveWithoutFixCreatesNewIncident() throws Exception {
        UUID processInstanceId = start("test16.bpmn", variable("approver", ProcessVariableType.LONG, "5"));
        refresh();
        ActivityEntity failed = single(activities(processInstanceId, "approveTask"));
        IncidentEntity incident = single(incidents(failed.getId()));

        runtimeService.resolveIncident(incident.getId(), List.of());
        refresh();

        assertThat(incidentRepository.findById(incident.getId()).orElseThrow().getCompletedAt()).isNotNull();
        List<ActivityEntity> attempts = activities(processInstanceId, "approveTask").stream()
            .sorted(Comparator.comparing(ActivityEntity::getCreatedAt))
            .toList();
        assertThat(attempts).extracting(ActivityEntity::getStatus).containsExactly(ActivityStatus.TERMINATED, ActivityStatus.ERROR);
        IncidentEntity second = single(incidents(attempts.get(1).getId()));
        assertThat(second.getCompletedAt()).isNull();
        assertThat(openUserTasks(processInstanceId)).isEmpty();
    }

    @Transactional
    @Test
    void resolvingClosedIncidentIsConflict() throws Exception {
        UUID processInstanceId = start("process2.bpmn");
        UUID serviceTaskId = singleOpenServiceTask(processInstanceId).getId();
        fail(serviceTaskId, "boom");
        refresh();
        UUID incidentId = single(incidents(serviceTaskId)).getId();
        runtimeService.resolveIncident(incidentId, List.of());
        refresh();

        assertThatThrownBy(() -> runtimeService.resolveIncident(incidentId, List.of()))
            .isInstanceOf(IncidentAlreadyResolvedException.class);
    }

    private void fail(UUID serviceTaskId, String message) {
        ServiceTaskFailed event = new ServiceTaskFailed();
        event.setServiceTaskId(serviceTaskId);
        event.setMessage(message);
        publisher.publishEvent(event);
    }

    private UUID start(String file, ProcessVariable... variables) throws Exception {
        ProcessDefinition model = processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/" + file)));
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        dto.setVariables(List.of(variables));
        return runtimeService.startProcessInstance(dto).getId();
    }

    private void refresh() {
        entityManager.flush();
        entityManager.clear();
    }

    private List<ActivityEntity> activities(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> processInstanceId.equals(a.getProcessInstanceId()) && bpmnElementId.equals(a.getBpmnElementId()))
            .toList();
    }

    private List<IncidentEntity> incidents(UUID activityId) {
        return incidentRepository.findAll().stream()
            .filter(i -> activityId.equals(i.getActivityId()))
            .toList();
    }

    private ServiceTask singleOpenServiceTask(UUID processInstanceId) {
        ServiceTaskQuery query = new ServiceTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        query.setCompleted(false);
        PagedDataDTO<ServiceTask> page = queryService.findServiceTasks(query);
        return single(page.getData());
    }

    private List<UserTask> openUserTasks(UUID processInstanceId) {
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        query.setCompleted(false);
        return queryService.findUserTasks(query).getData();
    }

    private static <T> T single(List<T> items) {
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    private static ProcessVariable variable(String name, ProcessVariableType type, String value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(type);
        variable.setValue(value);
        return variable;
    }
}
