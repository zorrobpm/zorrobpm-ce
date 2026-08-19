package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.TestMain;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class UserTaskQueryFiltersIntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Transactional
    @Test
    void findUserTasks_filtersByBpmnElementIdAndFormKey() throws Exception {
        // two definitions: MI task "miTask" (no formKey) and plain task "approveTask" (formKey=approve-form)
        ProcessDefinition mi = processDefinitionService.addProcessDefinition(
            Files.readString(Paths.get("src/test/files/test15.bpmn")));
        ProcessDefinition plain = processDefinitionService.addProcessDefinition(
            Files.readString(Paths.get("src/test/files/test16.bpmn")));

        start(mi.getId(), jsonVariable("items", "[{\"user\":\"u1\",\"groups\":[\"g1\"]},{\"user\":\"u2\",\"groups\":[\"g2\"]}]"));
        start(plain.getId(), stringVariable("approver", "alice"));

        List<UserTask> byElement = find(q -> q.setBpmnElementId("approveTask"));
        assertThat(byElement).hasSize(1);
        assertThat(byElement.get(0).getAssignee()).isEqualTo("alice");

        assertThat(find(q -> q.setBpmnElementId("miTask"))).hasSize(2);
        assertThat(find(q -> q.setBpmnElementId("noSuchElement"))).isEmpty();

        List<UserTask> byFormKey = find(q -> q.setFormKey("approve-form"));
        assertThat(byFormKey).hasSize(1);
        assertThat(byFormKey.get(0).getAssignee()).isEqualTo("alice");

        assertThat(find(q -> q.setFormKey("no-such-form"))).isEmpty();

        // combined with other filters (AND semantics)
        assertThat(find(q -> {
            q.setBpmnElementId("approveTask");
            q.setFormKey("approve-form");
            q.setCompleted(false);
        })).hasSize(1);
        assertThat(find(q -> {
            q.setBpmnElementId("miTask");
            q.setFormKey("approve-form");
        })).isEmpty();
    }

    private List<UserTask> find(Consumer<UserTaskQuery> customizer) {
        UserTaskQuery query = new UserTaskQuery();
        query.setPageSize(500);
        customizer.accept(query);
        PagedDataDTO<UserTask> page = queryService.findUserTasks(query);
        return page.getData();
    }

    private void start(java.util.UUID processDefinitionId, ProcessVariable variable) {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        dto.setVariables(List.of(variable));
        runtimeService.startProcessInstance(dto);
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
}
