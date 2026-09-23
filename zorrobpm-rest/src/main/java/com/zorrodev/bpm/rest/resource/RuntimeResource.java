package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.RuntimeContract;
import com.zorrodev.bpm.contract.dto.ClaimTaskDTO;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.FailServiceTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.ServiceTaskFailureDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.dto.FailureOutcome;
import com.zorrodev.bpm.engine.dto.RetryOverride;
import com.zorrodev.bpm.engine.service.RuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class RuntimeResource implements RuntimeContract {

    private final RuntimeService runtimeService;

    @Transactional
    @Override
    public IdDTO startProcessInstance(@RequestBody StartProcessInstanceDTO dto) {
        return Optional.ofNullable(runtimeService.startProcessInstance(dto)).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO completeServiceTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto) {
        return Optional.ofNullable(runtimeService.completeServiceTask(id, dto.getVariables())).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public ServiceTaskFailureDTO failServiceTask(@PathVariable UUID id, @RequestBody FailServiceTaskDTO dto) {
        if (dto.getMessage() == null || dto.getMessage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        if (dto.getRetries() != null && dto.getRetries() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "retries must not be negative");
        }
        RetryOverride override = new RetryOverride(dto.getRetries(), retryTimeout(dto.getRetryTimeout()));
        FailureOutcome outcome = runtimeService.failServiceTask(id, dto.getMessage(), dto.getErrorCode(), dto.getDetails(), override);
        return new ServiceTaskFailureDTO(outcome.incidentId(), outcome.retries(), outcome.nextRetryAt());
    }

    private static Duration retryTimeout(String value) {
        if (value == null) {
            return null;
        }
        Duration timeout;
        try {
            timeout = Duration.parse(value);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "retryTimeout must be an ISO-8601 duration, got '" + value + "'");
        }
        if (timeout.isNegative()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "retryTimeout must not be negative");
        }
        return timeout;
    }

    @Transactional
    @Override
    public IdDTO completeUserTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto) {
        return Optional.ofNullable(runtimeService.completeUserTask(id, dto.getVariables())).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO claimUserTask(@PathVariable UUID id, @RequestBody ClaimTaskDTO dto) {
        if (dto.getAssignee() == null || dto.getAssignee().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assignee is required");
        }
        return Optional.ofNullable(runtimeService.claimUserTask(id, dto.getAssignee())).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO unclaimUserTask(@PathVariable UUID id) {
        return Optional.ofNullable(runtimeService.unclaimUserTask(id)).map(this::toDTO).orElseThrow();
    }

    @Transactional
    @Override
    public IdDTO resolveIncident(@PathVariable UUID id, @RequestBody ResolveIncidentDTO dto) {
        return Optional.ofNullable(runtimeService.resolveIncident(id, dto.getVariables())).map(this::toDTO).orElseThrow();
    }

    private IdDTO toDTO(com.zorrodev.bpm.engine.dto.IdDTO idDTO) {
        IdDTO result = new IdDTO();
        result.setId(idDTO.getId());
        return result;
    }
}
