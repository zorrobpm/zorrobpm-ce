package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ServiceTask;
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
public class Process14IntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Transactional
    @Test
    void testServiceTaskQueryFilters() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test14.bpmn"));

        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        IdDTO startResult = runtimeService.startProcessInstance(dto);
        UUID processInstanceId = startResult.getId();

        assertThat(findServiceTasks(processInstanceId, q -> q.setJobType("job1"))).hasSize(1);
        assertThat(findServiceTasks(processInstanceId, q -> q.setJobType("nope"))).isEmpty();
        assertThat(findServiceTasks(processInstanceId, q -> q.setCompleted(false))).hasSize(1);
        assertThat(findServiceTasks(processInstanceId, q -> q.setCompleted(true))).isEmpty();

        UUID serviceTaskId = findServiceTasks(processInstanceId, q -> {}).get(0).getId();
        runtimeService.completeServiceTask(serviceTaskId, List.of());

        assertThat(findServiceTasks(processInstanceId, q -> q.setCompleted(true))).hasSize(1);
        assertThat(findServiceTasks(processInstanceId, q -> q.setCompleted(false))).isEmpty();
        assertThat(findServiceTasks(processInstanceId, q -> {
            q.setJobType("job1");
            q.setCompleted(true);
        })).hasSize(1);
    }

    private List<ServiceTask> findServiceTasks(UUID processInstanceId, Consumer<ServiceTaskQuery> customizer) {
        ServiceTaskQuery query = new ServiceTaskQuery();
        query.setProcessInstanceId(processInstanceId);
        customizer.accept(query);
        PagedDataDTO<ServiceTask> page = queryService.findServiceTasks(query);
        return page.getData();
    }
}
