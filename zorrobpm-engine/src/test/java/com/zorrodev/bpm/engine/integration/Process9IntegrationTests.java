package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.exception.UserTaskAlreadyAssignedException;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Process9IntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Transactional
    @Test
    void testProcess() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test9.bpmn"));

        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        UUID processDefinitionId = model.getId();

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        assertThat(findUserTasks(processInstanceId, q -> q.setCandidateGroup("group1"))).hasSize(1);
        assertThat(findUserTasks(processInstanceId, q -> q.setCandidateGroup("nope"))).isEmpty();
        assertThat(findUserTasks(processInstanceId, q -> q.setCandidateUser("user1"))).hasSize(1);
        assertThat(findUserTasks(processInstanceId, q -> q.setAssigned(false))).hasSize(1);
        assertThat(findUserTasks(processInstanceId, q -> q.setAssigned(true))).isEmpty();

        UserTask userTask = findUserTasks(processInstanceId, q -> {}).get(0);
        UUID userTaskId = userTask.getId();
        assertThat(userTask.getAssignee()).isNull();
        assertThat(userTask.getCandidateGroups()).containsExactlyInAnyOrder("group1", "group2");
        assertThat(userTask.getCandidateUsers()).containsExactly("user1");

        runtimeService.claimUserTask(userTaskId, "alice");
        assertThat(findUserTasks(processInstanceId, q -> q.setAssignee("alice"))).hasSize(1);
        assertThat(findUserTasks(processInstanceId, q -> q.setAssigned(true))).hasSize(1);

        assertThatThrownBy(() -> runtimeService.claimUserTask(userTaskId, "bob"))
            .isInstanceOf(UserTaskAlreadyAssignedException.class);

        runtimeService.unclaimUserTask(userTaskId);
        assertThat(findUserTasks(processInstanceId, q -> q.setAssigned(false))).hasSize(1);

        runtimeService.claimUserTask(userTaskId, "bob");
        runtimeService.completeUserTask(userTaskId, List.of());

        assertThat(findUserTasks(processInstanceId, q -> q.setCompleted(true))).hasSize(1);
        assertThat(findUserTasks(processInstanceId, q -> q.setCompleted(false))).isEmpty();

        ProcessInstance processInstance = queryService.getProcessInstance(processInstanceId);
        assertThat(processInstance.getCompletedAt()).isNotNull();
    }

    private List<UserTask> findUserTasks(UUID processInstanceId, java.util.function.Consumer<UserTaskQuery> customizer) {
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        customizer.accept(query);
        PagedDataDTO<UserTask> page = queryService.findUserTasks(query);
        return page.getData();
    }
}
