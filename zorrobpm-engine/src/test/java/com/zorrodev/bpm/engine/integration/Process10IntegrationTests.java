package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
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

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Process10IntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Transactional
    @Test
    void testParallelMultiInstance() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test10.bpmn"));

        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID processInstanceId = start(model.getId(), "[\"a\",\"b\",\"c\"]");

        List<UserTask> tasks = new ArrayList<>(findUserTasks(processInstanceId, q -> q.setCompleted(false)));
        assertThat(tasks).hasSize(3);
        tasks.sort(Comparator.comparing(UserTask::getLoopIndex));
        assertThat(tasks).extracting(UserTask::getLoopIndex).containsExactly(0, 1, 2);
        assertThat(tasks).extracting(UserTask::getLoopTotal).containsOnly(3);
        assertThat(tasks).extracting(UserTask::getLoopItem).containsExactly("\"a\"", "\"b\"", "\"c\"");

        // complete out of order: 1, 0, 2
        runtimeService.completeUserTask(tasks.get(1).getId(), List.of(variable("result", "r1")));
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();
        assertThat(findUserTasks(processInstanceId, q -> q.setCompleted(false))).hasSize(2);

        runtimeService.completeUserTask(tasks.get(0).getId(), List.of(variable("result", "r0")));
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();

        runtimeService.completeUserTask(tasks.get(2).getId(), List.of(variable("result", "r2")));

        ProcessInstance processInstance = queryService.getProcessInstance(processInstanceId);
        assertThat(processInstance.getCompletedAt()).isNotNull();
        assertThat(findVariable(processInstanceId, "results").getValue()).isEqualTo("[\"r0\",\"r1\",\"r2\"]");
    }

    @Transactional
    @Test
    void testEmptyCollectionCompletesImmediately() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test10.bpmn"));

        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        UUID processInstanceId = start(model.getId(), "[]");

        assertThat(findUserTasks(processInstanceId, q -> {})).isEmpty();
        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();
        assertThat(findVariable(processInstanceId, "results").getValue()).isEqualTo("[]");
    }

    private UUID start(UUID processDefinitionId, String itemsJson) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        ProcessVariable items = new ProcessVariable();
        items.setName("items");
        items.setType(ProcessVariableType.JSON);
        items.setValue(itemsJson);
        dto.setVariables(List.of(items));
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        return startResult.getId();
    }

    private static ProcessVariable variable(String name, String value) {
        ProcessVariable variable = new ProcessVariable();
        variable.setName(name);
        variable.setType(ProcessVariableType.STRING);
        variable.setValue(value);
        return variable;
    }

    private ProcessVariable findVariable(UUID processInstanceId, String name) {
        VariableQuery query = new VariableQuery();
        query.setProcessInstanceId(processInstanceId);
        query.setName(name);
        PagedDataDTO<ProcessVariable> page = queryService.findVariables(query);
        assertThat(page.getData()).hasSize(1);
        return page.getData().get(0);
    }

    private List<UserTask> findUserTasks(UUID processInstanceId, Consumer<UserTaskQuery> customizer) {
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        customizer.accept(query);
        PagedDataDTO<UserTask> page = queryService.findUserTasks(query);
        return page.getData();
    }
}
