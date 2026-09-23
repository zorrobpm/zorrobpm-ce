package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.exception.TaskNotActiveException;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Completing a task that is no longer active (completed, or canceled by a boundary timer) is a conflict.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class TaskCompletionResourceIntegrationTests {

    private static final String BODY = "{\"variables\":[]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueryService queryService;

    @MockitoBean
    private RuntimeService runtimeService;

    @Test
    void completingCanceledUserTaskIsConflict() throws Exception {
        UUID id = UUID.randomUUID();
        when(runtimeService.completeUserTask(eq(id), any())).thenThrow(new TaskNotActiveException("User task " + id + " is not active: TERMINATED"));

        mockMvc.perform(post("/user-tasks/{id}/complete", id).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isConflict());
    }

    @Test
    void completingInterruptedServiceTaskIsConflict() throws Exception {
        UUID id = UUID.randomUUID();
        when(runtimeService.completeServiceTask(eq(id), any())).thenThrow(new TaskNotActiveException("Service task " + id + " is not active: TERMINATED"));

        mockMvc.perform(post("/service-tasks/{id}/complete", id).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isConflict());
    }
}
