package com.zorrodev.bpm.rest.resource;

import com.jayway.jsonpath.JsonPath;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An external worker reports a service task failure over HTTP, against the real engine and database.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ServiceTaskFailureResourceIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;

    @Test
    void failureCreatesIncidentAndStopsTheProcess() throws Exception {
        UUID processInstanceId = start("service-task.bpmn");
        UUID serviceTaskId = openServiceTask(processInstanceId);

        UUID incidentId = fail(serviceTaskId, "card declined").andExpect(status().isOk()).andReturnId();

        mockMvc.perform(get("/incidents/{id}", incidentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activityId").value(serviceTaskId.toString()))
            .andExpect(jsonPath("$.message").value("card declined"))
            .andExpect(jsonPath("$.completedAt").doesNotExist());
        assertThat(activityRepository.findById(serviceTaskId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.ERROR);
        assertThat(openServiceTask(processInstanceId)).isEqualTo(serviceTaskId);
    }

    @Test
    void failureWithCodeAndDetailsKeepsThem() throws Exception {
        UUID serviceTaskId = openServiceTask(start("service-task.bpmn"));
        String body = "{\"message\":\"card declined\",\"errorCode\":\"CARD_DECLINED\",\"details\":\"gateway response 402\"}";

        UUID incidentId = new FailResult(mockMvc.perform(post("/service-tasks/{id}/fail", serviceTaskId)
                .contentType(MediaType.APPLICATION_JSON).content(body)))
            .andExpect(status().isOk()).andReturnId();

        mockMvc.perform(get("/incidents/{id}", incidentId))
            .andExpect(jsonPath("$.message").value("card declined"))
            .andExpect(jsonPath("$.errorCode").value("CARD_DECLINED"))
            .andExpect(jsonPath("$.details").value("gateway response 402"));
    }

    @Test
    void failureWithoutCodeAndDetailsLeavesThemEmpty() throws Exception {
        UUID serviceTaskId = openServiceTask(start("service-task.bpmn"));

        UUID incidentId = fail(serviceTaskId, "card declined").andExpect(status().isOk()).andReturnId();

        mockMvc.perform(get("/incidents/{id}", incidentId))
            .andExpect(jsonPath("$.errorCode").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void repeatedFailureReturnsTheOpenIncident() throws Exception {
        UUID serviceTaskId = openServiceTask(start("service-task.bpmn"));
        UUID first = fail(serviceTaskId, "card declined").andExpect(status().isOk()).andReturnId();

        UUID second = fail(serviceTaskId, "card declined again").andExpect(status().isOk()).andReturnId();

        assertThat(second).isEqualTo(first);
        assertThat(incidentsOf(serviceTaskId)).hasSize(1);
        mockMvc.perform(get("/incidents/{id}", first))
            .andExpect(jsonPath("$.message").value("card declined"));
    }

    @Test
    void failureOfCompletedServiceTaskIsConflict() throws Exception {
        UUID serviceTaskId = openServiceTask(start("service-task.bpmn"));
        mockMvc.perform(post("/service-tasks/{id}/complete", serviceTaskId).contentType(MediaType.APPLICATION_JSON).content("{\"variables\":[]}"))
            .andExpect(status().isOk());

        fail(serviceTaskId, "too late").andExpect(status().isConflict());

        assertThat(incidentsOf(serviceTaskId)).isEmpty();
    }

    @Test
    void failureOfUnknownIdIsNotFound() throws Exception {
        fail(UUID.randomUUID(), "boom").andExpect(status().isNotFound());
    }

    @Test
    void failureOfUserTaskIsNotFound() throws Exception {
        UUID processInstanceId = start("user-task.bpmn");
        String body = mockMvc.perform(get("/user-tasks").param("processInstanceId", processInstanceId.toString()))
            .andReturn().getResponse().getContentAsString();
        UUID userTaskId = UUID.fromString(JsonPath.read(body, "$.data[0].id"));

        fail(userTaskId, "boom").andExpect(status().isNotFound());

        assertThat(activityRepository.findById(userTaskId).orElseThrow().getStatus()).isNotEqualTo(ActivityStatus.ERROR);
    }

    @Test
    void blankOrMissingMessageIsBadRequest() throws Exception {
        UUID serviceTaskId = openServiceTask(start("service-task.bpmn"));

        fail(serviceTaskId, "   ").andExpect(status().isBadRequest());
        mockMvc.perform(post("/service-tasks/{id}/fail", serviceTaskId).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());

        assertThat(activityRepository.findById(serviceTaskId).orElseThrow().getStatus()).isNotEqualTo(ActivityStatus.ERROR);
    }

    @Test
    void failureAfterResolveCreatesNewIncident() throws Exception {
        UUID serviceTaskId = openServiceTask(start("service-task.bpmn"));
        UUID first = fail(serviceTaskId, "card declined").andExpect(status().isOk()).andReturnId();
        mockMvc.perform(post("/incidents/{id}/resolve", first).contentType(MediaType.APPLICATION_JSON).content("{\"variables\":[]}"))
            .andExpect(status().isOk());

        UUID second = fail(serviceTaskId, "card declined").andExpect(status().isOk()).andReturnId();

        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(get("/incidents/{id}", first))
            .andExpect(jsonPath("$.completedAt").exists());
        mockMvc.perform(get("/incidents/{id}", second))
            .andExpect(jsonPath("$.completedAt").doesNotExist());
    }

    private List<IncidentEntity> incidentsOf(UUID activityId) {
        return incidentRepository.findAll().stream().filter(i -> activityId.equals(i.getActivityId())).toList();
    }

    private FailResult fail(UUID serviceTaskId, String message) throws Exception {
        String body = "{\"message\":\"" + message + "\"}";
        return new FailResult(mockMvc.perform(post("/service-tasks/{id}/fail", serviceTaskId).contentType(MediaType.APPLICATION_JSON).content(body)));
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

    private record FailResult(ResultActions actions) {

        FailResult andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        UUID andReturnId() throws Exception {
            return UUID.fromString(JsonPath.read(actions.andReturn().getResponse().getContentAsString(), "$.id"));
        }
    }
}
