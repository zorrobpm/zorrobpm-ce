package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
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
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Process11IntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Transactional
    @Test
    void testSequentialMultiInstance() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test11.bpmn"));

        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        ProcessVariable items = new ProcessVariable();
        items.setName("items");
        items.setType(ProcessVariableType.JSON);
        items.setValue("[\"x\",\"y\"]");
        dto.setVariables(List.of(items));
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        List<UserTask> open = findUserTasks(processInstanceId, q -> q.setCompleted(false));
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getLoopIndex()).isEqualTo(0);
        assertThat(open.get(0).getLoopTotal()).isEqualTo(2);
        assertThat(open.get(0).getLoopItem()).isEqualTo("\"x\"");

        runtimeService.completeUserTask(open.get(0).getId(), List.of(variable("result", "rx")));

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNull();
        open = findUserTasks(processInstanceId, q -> q.setCompleted(false));
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getLoopIndex()).isEqualTo(1);
        assertThat(open.get(0).getLoopItem()).isEqualTo("\"y\"");

        runtimeService.completeUserTask(open.get(0).getId(), List.of(variable("result", "ry")));

        assertThat(queryService.getProcessInstance(processInstanceId).getCompletedAt()).isNotNull();
        assertThat(findUserTasks(processInstanceId, q -> q.setCompleted(true))).hasSize(2);

        VariableQuery variableQuery = new VariableQuery();
        variableQuery.setProcessInstanceId(processInstanceId);
        variableQuery.setName("results");
        PagedDataDTO<ProcessVariable> page = queryService.findVariables(variableQuery);
        assertThat(page.getData()).hasSize(1);
        assertThat(page.getData().get(0).getValue()).isEqualTo("[\"rx\",\"ry\"]");
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
