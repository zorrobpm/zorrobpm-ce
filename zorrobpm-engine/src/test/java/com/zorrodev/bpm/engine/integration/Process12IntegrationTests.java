package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.exception.UserTaskAlreadyAssignedException;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Process12IntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Transactional
    @Test
    void testCompletionConditionCancelsRemainingInstances() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test12.bpmn"));

        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        ProcessVariable items = new ProcessVariable();
        items.setName("items");
        items.setType(ProcessVariableType.JSON);
        items.setValue("[\"a\",\"b\",\"c\"]");
        dto.setVariables(List.of(items));
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        List<UserTask> tasks = new ArrayList<>(findUserTasks(processInstanceId, q -> q.setCompleted(false)));
        assertThat(tasks).hasSize(3);
        tasks.sort(Comparator.comparing(UserTask::getLoopIndex));

        runtimeService.completeUserTask(tasks.get(0).getId(), List.of(variable("result", "r0")));
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();
        assertThat(findUserTasks(processInstanceId, q -> q.setCompleted(false))).hasSize(2);

        runtimeService.completeUserTask(tasks.get(1).getId(), List.of(variable("result", "r1")));

        // condition numberOfCompletedInstances >= 2 fired: process done, third instance canceled
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();
        assertThat(findUserTasks(processInstanceId, q -> q.setCompleted(false))).isEmpty();
        assertThat(findUserTasks(processInstanceId, q -> q.setCompleted(true))).hasSize(2);

        UserTask canceled = findUserTasks(processInstanceId, q -> {}).stream()
            .filter(task -> task.getLoopIndex() == 2)
            .findFirst().orElseThrow();
        assertThat(canceled.getCanceledAt()).isNotNull();
        assertThat(canceled.getCompletedAt()).isNull();

        assertThatThrownBy(() -> runtimeService.claimUserTask(canceled.getId(), "alice"))
            .isInstanceOf(UserTaskAlreadyAssignedException.class);

        VariableQuery variableQuery = new VariableQuery();
        variableQuery.setProcessInstanceId(processInstanceId);
        variableQuery.setName("results");
        PagedDataDTO<ProcessVariable> page = queryService.findVariables(variableQuery);
        assertThat(page.getData()).hasSize(1);
        assertThat(page.getData().get(0).getValue()).isEqualTo("[\"r0\",\"r1\",null]");
    }

    private static ProcessVariable variable(String name, String value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(ProcessVariableType.STRING);
        variable.setValue(value);
        return variable;
    }

    private List<UserTask> findUserTasks(UUID processInstanceId, Consumer<UserTaskQuery> customizer) {
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        customizer.accept(query);
        PagedDataDTO<UserTask> page = queryService.findUserTasks(query);
        return page.getData();
    }
}
