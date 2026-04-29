package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
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

@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
public class Process3IntegrationTests {

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private QueryService queryService;

    @Transactional
    @Test
    void testProcess() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/test3.bpmn"));

        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        UUID processDefinitionId = model.getId();

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        IdDTO startResult = runtimeService.startProcessInstance(dto);

        UUID processInstanceId = startResult.getId();

        ServiceTaskQuery serviceTaskQuery = new ServiceTaskQuery();
        serviceTaskQuery.setProcessInstanceId(processInstanceId);
        PagedDataDTO<ServiceTask> serviceTasks = queryService.findServiceTasks(serviceTaskQuery);

        assertThat(serviceTasks.getData()).hasSize(1);
        UUID serviceTaskId = serviceTasks.getData().get(0).getId();

        runtimeService.completeServiceTask(serviceTaskId, List.of());

        ServiceTask serviceTask = queryService.getServiceTask(serviceTaskId);
        assertThat(serviceTask).isNotNull();
        assertThat(serviceTask.getCompletedAt()).isNotNull();

        ProcessInstance processInstance = queryService.getProcessInstance(processInstanceId);
        assertThat(processInstance).isNotNull();
        assertThat(processInstance.getCompletedAt()).isNotNull();

    }
}
