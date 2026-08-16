package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.RuntimeContract;
import com.zorrodev.bpm.contract.dto.ClaimTaskDTO;
import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.service.RuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
