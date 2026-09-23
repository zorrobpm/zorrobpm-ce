package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.BpmnErrorOutcomeDTO;
import com.zorrodev.bpm.contract.dto.ClaimTaskDTO;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.FailServiceTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.ServiceTaskFailureDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.ThrowBpmnErrorDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.dto.BpmnErrorOutcome;
import com.zorrodev.bpm.engine.dto.FailureOutcome;
import com.zorrodev.bpm.engine.dto.RetryOverride;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeResourceTest {

    @Mock
    private RuntimeService runtimeService;

    @InjectMocks
    private RuntimeResource resource;

    @Test
    void startProcessInstance_delegatesToService() {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        IdDTO expected = new IdDTO(UUID.randomUUID());
        when(runtimeService.startProcessInstance(dto)).thenReturn(toEngineDTO(expected));

        IdDTO result = resource.startProcessInstance(dto);

        assertThat(result.getId()).isSameAs(expected.getId());
    }

    private com.zorrodev.bpm.engine.dto.IdDTO toEngineDTO(IdDTO expected) {
        return new com.zorrodev.bpm.engine.dto.IdDTO(expected.getId());
    }

    @Test
    void completeServiceTask_delegatesToService() {
        UUID id = UUID.randomUUID();
        List<ProcessVariable> vars = List.of();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(vars);
        IdDTO expected = new IdDTO(id);
        when(runtimeService.completeServiceTask(id, vars)).thenReturn(toEngineDTO(expected));

        IdDTO result = resource.completeServiceTask(id, dto);

        assertThat(result.getId()).isSameAs(expected.getId());
        verify(runtimeService).completeServiceTask(id, vars);
    }

    @Test
    void throwBpmnError_delegatesToServiceAndReturnsOutcome() {
        UUID id = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        List<ProcessVariable> vars = List.of(new ProcessVariable());
        ThrowBpmnErrorDTO dto = new ThrowBpmnErrorDTO();
        dto.setErrorCode("CUSTOMER_NOT_FOUND");
        dto.setMessage("no customer 42");
        dto.setVariables(vars);
        when(runtimeService.throwBpmnError(id, "CUSTOMER_NOT_FOUND", "no customer 42", vars))
            .thenReturn(new BpmnErrorOutcome(true, "notFound", processInstanceId, null));

        BpmnErrorOutcomeDTO result = resource.throwBpmnError(id, dto);

        assertThat(result.isCaught()).isTrue();
        assertThat(result.getBoundaryEventId()).isEqualTo("notFound");
        assertThat(result.getProcessInstanceId()).isEqualTo(processInstanceId);
        assertThat(result.getIncidentId()).isNull();
    }

    @Test
    void throwBpmnError_blankCode_returnsBadRequest() {
        for (String code : java.util.Arrays.asList(null, "", "  ")) {
            ThrowBpmnErrorDTO dto = new ThrowBpmnErrorDTO();
            dto.setErrorCode(code);

            assertThatThrownBy(() -> resource.throwBpmnError(UUID.randomUUID(), dto))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verifyNoInteractions(runtimeService);
    }

    @Test
    void failServiceTask_delegatesToServiceAndReturnsIncidentId() {
        UUID id = UUID.randomUUID();
        FailServiceTaskDTO dto = new FailServiceTaskDTO();
        dto.setMessage("card declined");
        dto.setErrorCode("CARD_DECLINED");
        dto.setDetails("gateway response 402");
        UUID incidentId = UUID.randomUUID();
        when(runtimeService.failServiceTask(id, "card declined", "CARD_DECLINED", "gateway response 402", RetryOverride.NONE))
            .thenReturn(new FailureOutcome(incidentId, 0, null));

        ServiceTaskFailureDTO result = resource.failServiceTask(id, dto);

        assertThat(result.getId()).isEqualTo(incidentId);
        assertThat(result.getRetries()).isZero();
        assertThat(result.getNextRetryAt()).isNull();
    }

    @Test
    void failServiceTask_passesRetryOverrideAndReturnsScheduledRetry() {
        UUID id = UUID.randomUUID();
        FailServiceTaskDTO dto = new FailServiceTaskDTO();
        dto.setMessage("gateway down");
        dto.setRetries(3);
        dto.setRetryTimeout("PT10M");
        Instant nextRetryAt = Instant.parse("2026-09-23T10:10:00Z");
        when(runtimeService.failServiceTask(id, "gateway down", null, null, new RetryOverride(3, Duration.ofMinutes(10))))
            .thenReturn(new FailureOutcome(null, 2, nextRetryAt));

        ServiceTaskFailureDTO result = resource.failServiceTask(id, dto);

        assertThat(result.getId()).isNull();
        assertThat(result.getRetries()).isEqualTo(2);
        assertThat(result.getNextRetryAt()).isEqualTo(nextRetryAt);
    }

    @Test
    void failServiceTask_invalidRetrySettings_returnBadRequest() {
        for (FailServiceTaskDTO dto : List.of(failure(-1, null), failure(null, "soon"), failure(null, "-PT1M"))) {
            assertThatThrownBy(() -> resource.failServiceTask(UUID.randomUUID(), dto))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verifyNoInteractions(runtimeService);
    }

    private static FailServiceTaskDTO failure(Integer retries, String retryTimeout) {
        FailServiceTaskDTO dto = new FailServiceTaskDTO();
        dto.setMessage("boom");
        dto.setRetries(retries);
        dto.setRetryTimeout(retryTimeout);
        return dto;
    }

    @Test
    void failServiceTask_blankMessage_returnsBadRequest() {
        FailServiceTaskDTO dto = new FailServiceTaskDTO();
        dto.setMessage(" ");

        assertThatThrownBy(() -> resource.failServiceTask(UUID.randomUUID(), dto))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void failServiceTask_nullMessage_returnsBadRequest() {
        FailServiceTaskDTO dto = new FailServiceTaskDTO();

        assertThatThrownBy(() -> resource.failServiceTask(UUID.randomUUID(), dto))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void completeUserTask_delegatesToService() {
        UUID id = UUID.randomUUID();
        List<ProcessVariable> vars = List.of();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(vars);
        IdDTO expected = new IdDTO(id);
        when(runtimeService.completeUserTask(id, vars)).thenReturn(toEngineDTO(expected));

        IdDTO result = resource.completeUserTask(id, dto);

        assertThat(result.getId()).isSameAs(expected.getId());
        verify(runtimeService).completeUserTask(id, vars);
    }

    @Test
    void claimUserTask_delegatesToService() {
        UUID id = UUID.randomUUID();
        ClaimTaskDTO dto = new ClaimTaskDTO();
        dto.setAssignee("alice");
        IdDTO expected = new IdDTO(id);
        when(runtimeService.claimUserTask(id, "alice")).thenReturn(toEngineDTO(expected));

        IdDTO result = resource.claimUserTask(id, dto);

        assertThat(result.getId()).isSameAs(expected.getId());
        verify(runtimeService).claimUserTask(id, "alice");
    }

    @Test
    void claimUserTask_blankAssignee_returnsBadRequest() {
        ClaimTaskDTO dto = new ClaimTaskDTO();
        dto.setAssignee(" ");

        assertThatThrownBy(() -> resource.claimUserTask(UUID.randomUUID(), dto))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void claimUserTask_nullAssignee_returnsBadRequest() {
        ClaimTaskDTO dto = new ClaimTaskDTO();

        assertThatThrownBy(() -> resource.claimUserTask(UUID.randomUUID(), dto))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void unclaimUserTask_delegatesToService() {
        UUID id = UUID.randomUUID();
        IdDTO expected = new IdDTO(id);
        when(runtimeService.unclaimUserTask(id)).thenReturn(toEngineDTO(expected));

        IdDTO result = resource.unclaimUserTask(id);

        assertThat(result.getId()).isSameAs(expected.getId());
        verify(runtimeService).unclaimUserTask(id);
    }

    @Test
    void resolveIncident_delegatesToService() {
        UUID id = UUID.randomUUID();
        List<ProcessVariable> vars = List.of();
        ResolveIncidentDTO dto = new ResolveIncidentDTO();
        dto.setVariables(vars);
        IdDTO expected = new IdDTO(id);
        when(runtimeService.resolveIncident(id, vars)).thenReturn(toEngineDTO(expected));

        IdDTO result = resource.resolveIncident(id, dto);

        assertThat(result.getId()).isSameAs(expected.getId());
        verify(runtimeService).resolveIncident(id, vars);
    }
}
