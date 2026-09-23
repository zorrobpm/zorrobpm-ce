package com.zorrodev.bpm.rest.resource;

import com.jayway.jsonpath.JsonPath;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.service.DBService;
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
 * An external worker throws a BPMN error over HTTP, against the real engine and database.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ServiceTaskBpmnErrorResourceIntegrationTests {

    private static final String CATCHING = "service-task-bpmn-error.bpmn";

    @Autowired private MockMvc mockMvc;
    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private DBService dbService;

    @Test
    void caughtErrorContinuesOnBoundaryEvent() throws Exception {
        UUID processInstanceId = start(CATCHING);
        UUID serviceTaskId = openServiceTask(processInstanceId);
        String body = "{\"errorCode\":\"CUSTOMER_NOT_FOUND\",\"message\":\"no customer 42\","
            + "\"variables\":[{\"name\":\"customerId\",\"value\":\"42\",\"type\":\"STRING\"}]}";

        throwError(serviceTaskId, body).andExpect(status().isOk())
            .andExpect(jsonPath("$.caught").value(true))
            .andExpect(jsonPath("$.boundaryEventId").value("notFound"))
            .andExpect(jsonPath("$.processInstanceId").value(processInstanceId.toString()))
            .andExpect(jsonPath("$.incidentId").doesNotExist());

        assertThat(activityRepository.findById(serviceTaskId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.TERMINATED);
        assertThat(incidentsOf(serviceTaskId)).isEmpty();
        assertThat(activitiesOf(processInstanceId, "handled")).isOne();
        assertThat(dbService.getVariables(processInstanceId))
            .anySatisfy(v -> {
                assertThat(v.getName()).isEqualTo("customerId");
                assertThat(v.getValue()).isEqualTo("42");
            });
    }

    @Test
    void uncaughtErrorOpensIncident() throws Exception {
        UUID serviceTaskId = openServiceTask(start("service-task.bpmn"));

        String response = throwError(serviceTaskId, "{\"errorCode\":\"CARD_DECLINED\",\"message\":\"declined\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caught").value(false))
            .andExpect(jsonPath("$.boundaryEventId").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        UUID incidentId = UUID.fromString(JsonPath.read(response, "$.incidentId"));

        mockMvc.perform(get("/incidents/{id}", incidentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activityId").value(serviceTaskId.toString()))
            .andExpect(jsonPath("$.errorCode").value("CARD_DECLINED"))
            .andExpect(jsonPath("$.message").value("declined"))
            .andExpect(jsonPath("$.completedAt").doesNotExist());
        assertThat(activityRepository.findById(serviceTaskId).orElseThrow().getStatus()).isEqualTo(ActivityStatus.ERROR);
    }

    @Test
    void errorWithOpenIncidentReturnsIt() throws Exception {
        UUID serviceTaskId = openServiceTask(start("service-task.bpmn"));
        String first = throwError(serviceTaskId, "{\"errorCode\":\"CARD_DECLINED\"}").andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String second = throwError(serviceTaskId, "{\"errorCode\":\"OTHER\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.caught").value(false))
            .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(second, "$.incidentId")).isEqualTo(JsonPath.<String>read(first, "$.incidentId"));
        assertThat(incidentsOf(serviceTaskId)).hasSize(1);
    }

    @Test
    void repeatedErrorAfterCatchIsConflict() throws Exception {
        UUID processInstanceId = start(CATCHING);
        UUID serviceTaskId = openServiceTask(processInstanceId);
        throwError(serviceTaskId, "{\"errorCode\":\"CUSTOMER_NOT_FOUND\"}").andExpect(status().isOk());

        throwError(serviceTaskId, "{\"errorCode\":\"CUSTOMER_NOT_FOUND\"}").andExpect(status().isConflict());

        assertThat(activitiesOf(processInstanceId, "notFound")).isOne();
    }

    @Test
    void errorWhileRetryIsPendingIsConflict() throws Exception {
        UUID serviceTaskId = openServiceTask(start("service-task-retries.bpmn"));
        mockMvc.perform(post("/service-tasks/{id}/fail", serviceTaskId).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"timeout\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nextRetryAt").exists());

        throwError(serviceTaskId, "{\"errorCode\":\"CUSTOMER_NOT_FOUND\"}").andExpect(status().isConflict());

        assertThat(incidentsOf(serviceTaskId)).isEmpty();
        mockMvc.perform(get("/service-tasks").param("processInstanceId", processInstanceOf(serviceTaskId).toString()))
            .andExpect(jsonPath("$.data[0].retries").value(1))
            .andExpect(jsonPath("$.data[0].nextRetryAt").exists());
    }

    @Test
    void blankOrMissingCodeIsBadRequest() throws Exception {
        UUID serviceTaskId = openServiceTask(start(CATCHING));

        throwError(serviceTaskId, "{\"errorCode\":\"  \"}").andExpect(status().isBadRequest());
        throwError(serviceTaskId, "{\"message\":\"no code\"}").andExpect(status().isBadRequest());

        assertThat(activityRepository.findById(serviceTaskId).orElseThrow().getCompletedAt()).isNull();
        assertThat(incidentsOf(serviceTaskId)).isEmpty();
    }

    @Test
    void errorOnUserTaskIsNotFound() throws Exception {
        UUID processInstanceId = start("user-task.bpmn");
        String body = mockMvc.perform(get("/user-tasks").param("processInstanceId", processInstanceId.toString()))
            .andReturn().getResponse().getContentAsString();
        UUID userTaskId = UUID.fromString(JsonPath.read(body, "$.data[0].id"));

        throwError(userTaskId, "{\"errorCode\":\"CUSTOMER_NOT_FOUND\"}").andExpect(status().isNotFound());
        throwError(UUID.randomUUID(), "{\"errorCode\":\"CUSTOMER_NOT_FOUND\"}").andExpect(status().isNotFound());
    }

    private ResultActions throwError(UUID serviceTaskId, String body) throws Exception {
        return mockMvc.perform(post("/service-tasks/{id}/bpmn-error", serviceTaskId).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID processInstanceOf(UUID activityId) {
        return activityRepository.findById(activityId).orElseThrow().getProcessInstanceId();
    }

    private long activitiesOf(UUID processInstanceId, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> processInstanceId.equals(a.getProcessInstanceId()) && bpmnElementId.equals(a.getBpmnElementId()))
            .count();
    }

    private List<IncidentEntity> incidentsOf(UUID activityId) {
        return incidentRepository.findAll().stream().filter(i -> activityId.equals(i.getActivityId())).toList();
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
}
