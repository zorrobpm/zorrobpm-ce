package com.zorrodev.bpm.rest.resource;

import com.jayway.jsonpath.JsonPath;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Output mapping failures as seen over HTTP, against the real engine and database. */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class OutputMappingResourceIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private UserTaskRepository userTaskRepository;

    @Test
    void userTaskCompletionFailingTheMappingIsUnprocessable() throws Exception {
        UUID processInstanceId = start("user-task-output-mapping.bpmn");
        UUID taskId = openUserTask(processInstanceId);

        mockMvc.perform(post("/user-tasks/{id}/complete", taskId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"variables\":[{\"name\":\"comment\",\"type\":\"STRING\",\"value\":\"ok\"}]}"))
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.detail").value(containsString("Output 'approved' of 'review'")));

        assertThat(userTaskRepository.findById(taskId).orElseThrow().getCompletedAt()).isNull();
        mockMvc.perform(get("/variables").param("processInstanceId", processInstanceId.toString()))
            .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(post("/user-tasks/{id}/complete", taskId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"variables\":[{\"name\":\"decision\",\"type\":\"STRING\",\"value\":\"approve\"}]}"))
            .andExpect(status().isOk());
        assertThat(userTaskRepository.findById(taskId).orElseThrow().getCompletedAt()).isNotNull();
        mockMvc.perform(get("/variables").param("processInstanceId", processInstanceId.toString()))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.data[0].name").value("approved"))
            .andExpect(jsonPath("$.data[0].value").value("approve"));
    }

    @Test
    void serviceTaskCompletionFailingTheMappingIsAcceptedWithAnIncident() throws Exception {
        UUID processInstanceId = start("service-task-output-mapping.bpmn");
        UUID serviceTaskId = openServiceTask(processInstanceId);

        mockMvc.perform(post("/service-tasks/{id}/complete", serviceTaskId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"variables\":[{\"name\":\"debug\",\"type\":\"STRING\",\"value\":\"trace\"}]}"))
            .andExpect(status().isOk());

        assertThat(activityRepository.findById(serviceTaskId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.ERROR);
        String incidents = mockMvc.perform(get("/incidents").param("pageSize", "500"))
            .andReturn().getResponse().getContentAsString();
        java.util.List<String> ids = JsonPath.read(incidents, "$.data[?(@.activityId=='" + serviceTaskId + "')].id");
        assertThat(ids).hasSize(1);
        String incidentId = ids.get(0);
        mockMvc.perform(get("/incidents/{id}", incidentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activityId").value(serviceTaskId.toString()))
            .andExpect(jsonPath("$.errorCode").value("OUTPUT_MAPPING_FAILED"))
            .andExpect(jsonPath("$.message").value(containsString("Output 'paymentId' of 'charge'")))
            .andExpect(jsonPath("$.completedAt").doesNotExist());
        assertThat(openServiceTask(processInstanceId)).isEqualTo(serviceTaskId);
    }

    private UUID start(String file) throws Exception {
        ProcessDefinition definition = processDefinitionService.addProcessDefinition(Files.readString(Paths.get("src/test/files/" + file)));
        String body = mockMvc.perform(post("/process-instances").contentType(MediaType.APPLICATION_JSON)
                .content("{\"processDefinitionId\":\"" + definition.getId() + "\",\"variables\":[]}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID openServiceTask(UUID processInstanceId) throws Exception {
        String body = mockMvc.perform(get("/service-tasks").param("processInstanceId", processInstanceId.toString()).param("completed", "false"))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data[0].id"));
    }

    private UUID openUserTask(UUID processInstanceId) throws Exception {
        String body = mockMvc.perform(get("/user-tasks").param("processInstanceId", processInstanceId.toString()).param("completed", "false"))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data[0].id"));
    }
}
