package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Process15IntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Transactional
    @Test
    void testMultiInstanceExpressionAssignment() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test15.bpmn"));

        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID processInstanceId = start(model.getId(), jsonVariable("items",
            "[{\"user\":\"u1\",\"groups\":[\"g1\",\"g2\"]},{\"user\":\"u2\",\"groups\":[\"g3\"]}]"));

        List<UserTask> tasks = new ArrayList<>(findUserTasks(processInstanceId));
        assertThat(tasks).hasSize(2);
        tasks.sort(Comparator.comparing(UserTask::getLoopIndex));

        assertThat(tasks).extracting(UserTask::getAssignee).containsExactly("u1", "u2");
        assertThat(tasks.get(0).getCandidateGroups()).containsExactlyInAnyOrder("g1", "g2");
        assertThat(tasks.get(1).getCandidateGroups()).containsExactlyInAnyOrder("g3");
        // literal candidateUsers stays static across all instances
        assertThat(tasks.get(0).getCandidateUsers()).containsExactlyInAnyOrder("lead1", "lead2");
        assertThat(tasks.get(1).getCandidateUsers()).containsExactlyInAnyOrder("lead1", "lead2");
    }

    @Transactional
    @Test
    void testPlainTaskExpressionAssignee() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test16.bpmn"));

        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID processInstanceId = start(model.getId(), stringVariable("approver", "alice"));

        List<UserTask> tasks = findUserTasks(processInstanceId);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getAssignee()).isEqualTo("alice");
        assertThat(tasks.get(0).getCandidateGroups()).containsExactlyInAnyOrder("managers");
    }

    private UUID start(UUID processDefinitionId, ProcessVariable variable) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        dto.setVariables(List.of(variable));
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        return startResult.getId();
    }

    private static ProcessVariable jsonVariable(String name, String json) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(ProcessVariableType.JSON);
        variable.setValue(json);
        return variable;
    }

    private static ProcessVariable stringVariable(String name, String value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(ProcessVariableType.STRING);
        variable.setValue(value);
        return variable;
    }

    private List<UserTask> findUserTasks(UUID processInstanceId) {
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        query.setCompleted(false);
        PagedDataDTO<UserTask> page = queryService.findUserTasks(query);
        return page.getData();
    }
}
