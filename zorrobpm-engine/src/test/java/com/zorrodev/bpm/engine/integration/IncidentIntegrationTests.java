package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class IncidentIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private EntityManager entityManager;

    @Transactional
    @Test
    void gatewayConditionErrorAfterUserTaskBecomesIncident() throws Exception {
        UUID processInstanceId = start("integration/incident-gateway.bpmn");
        UUID reviewId = singleOpenUserTask(processInstanceId).getId();

        runtimeService.completeUserTask(reviewId, List.of());
        refresh();

        assertThat(activityRepository.findById(reviewId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.COMPLETED);

        ActivityEntity gateway = single(activities(processInstanceId, "xor"));
        assertThat(gateway.getStatus()).isEqualTo(ActivityStatus.ERROR);
        IncidentEntity incident = single(incidents(gateway.getId()));
        assertThat(incident.getCompletedAt()).isNull();
        assertThat(incident.getMessage()).contains("ScriptException").contains("amount");

        assertThat(activities(processInstanceId, "small")).isEmpty();
        assertThat(activities(processInstanceId, "big")).isEmpty();
        assertThat(openUserTasks(processInstanceId)).isEmpty();
    }

    @Transactional
    @Test
    void assignmentErrorOnStartBecomesIncident() throws Exception {
        UUID processInstanceId = start("test16.bpmn", variable("approver", ProcessVariableType.LONG, "5"));

        assertThat(processInstanceId).isNotNull();
        refresh();

        ActivityEntity approve = single(activities(processInstanceId, "approveTask"));
        assertThat(approve.getStatus()).isEqualTo(ActivityStatus.ERROR);
        assertThat(single(incidents(approve.getId())).getMessage()).contains("Assignee expression");
        assertThat(openUserTasks(processInstanceId)).isEmpty();
    }

    @Transactional
    @Test
    void errorInOneParallelBranchKeepsTheOtherBranch() throws Exception {
        UUID processInstanceId = start("integration/incident-parallel.bpmn");
        refresh();

        ActivityEntity badXor = single(activities(processInstanceId, "badXor"));
        assertThat(badXor.getStatus()).isEqualTo(ActivityStatus.ERROR);
        assertThat(incidents(badXor.getId())).hasSize(1);
        assertThat(activities(processInstanceId, "badTask")).isEmpty();

        assertThat(openUserTasks(processInstanceId)).extracting(UserTask::getCode).containsExactly("goodTask");
    }

    @Transactional
    @Test
    void completingUnknownServiceTaskIsStillAnError() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> runtimeService.completeServiceTask(unknown, List.of()))
            .isInstanceOf(NoSuchElementException.class);
        assertThat(incidentRepository.findAll()).noneMatch(incident -> unknown.equals(incident.getActivityId()));
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

    private List<UserTask> openUserTasks(UUID processInstanceId) {
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        query.setCompleted(false);
        PagedDataDTO<UserTask> page = queryService.findUserTasks(query);
        return page.getData();
    }

    private UserTask singleOpenUserTask(UUID processInstanceId) {
        return single(openUserTasks(processInstanceId));
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
