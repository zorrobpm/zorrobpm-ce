package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.exception.IncidentAlreadyResolvedException;
import com.zorrodev.bpm.contract.exception.IncidentNotFoundException;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks the HTTP side of incidents: reading one by id and the statuses of resolve.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class IncidentResourceIntegrationTests {

    private static final String RESOLVE_BODY = "{\"variables\":[{\"name\":\"retry\",\"type\":\"BOOLEAN\",\"value\":\"true\"}]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueryService queryService;

    @MockitoBean
    private RuntimeService runtimeService;

    @Test
    void getIncidentReturnsIt() throws Exception {
        UUID id = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        Incident incident = new Incident();
        incident.setId(id);
        incident.setActivityId(activityId);
        incident.setMessage("boom");
        incident.setCreatedAt(Instant.parse("2026-09-23T10:00:00Z"));
        when(queryService.getIncident(id)).thenReturn(incident);

        mockMvc.perform(get("/incidents/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.activityId").value(activityId.toString()))
            .andExpect(jsonPath("$.message").value("boom"))
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.completedAt").doesNotExist());
    }

    @Test
    void getUnknownIncidentIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(queryService.getIncident(id)).thenThrow(new IncidentNotFoundException("Incident " + id + " not found"));

        mockMvc.perform(get("/incidents/{id}", id))
            .andExpect(status().isNotFound());
    }

    @Test
    void resolveReturnsIncidentId() throws Exception {
        UUID id = UUID.randomUUID();
        when(runtimeService.resolveIncident(eq(id), any())).thenReturn(new IdDTO(id));

        mockMvc.perform(post("/incidents/{id}/resolve", id).contentType(MediaType.APPLICATION_JSON).content(RESOLVE_BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void resolveUnknownIncidentIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(runtimeService.resolveIncident(eq(id), any())).thenThrow(new IncidentNotFoundException("Incident " + id + " not found"));

        mockMvc.perform(post("/incidents/{id}/resolve", id).contentType(MediaType.APPLICATION_JSON).content(RESOLVE_BODY))
            .andExpect(status().isNotFound());
    }

    @Test
    void resolveClosedIncidentIsConflict() throws Exception {
        UUID id = UUID.randomUUID();
        when(runtimeService.resolveIncident(eq(id), any())).thenThrow(new IncidentAlreadyResolvedException("Incident " + id + " is already resolved"));

        mockMvc.perform(post("/incidents/{id}/resolve", id).contentType(MediaType.APPLICATION_JSON).content(RESOLVE_BODY))
            .andExpect(status().isConflict());
    }
}
