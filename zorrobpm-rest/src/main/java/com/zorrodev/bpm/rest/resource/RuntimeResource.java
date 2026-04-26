package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.service.RuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class RuntimeResource {

    private final RuntimeService runtimeService;

    @Transactional
    @PostMapping("/process-instances")
    public IdDTO startProcessInstance(@RequestBody StartProcessInstanceDTO dto) {
        return runtimeService.startProcessInstance(dto);
    };

    @Transactional
    @PostMapping("/service-tasks/{id}/complete")
    public IdDTO completeServiceTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto) {
        return runtimeService.completeServiceTask(id, dto.getVariables());
    }

    @Transactional
    @PostMapping("/user-tasks/{id}/complete")
    public IdDTO completeUserTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto) {
        return runtimeService.completeUserTask(id, dto.getVariables());
    }

    @Transactional
    @PostMapping("/incidents/{id}/resolve")
    public IdDTO resolveIncident(@PathVariable UUID id, @RequestBody ResolveIncidentDTO dto) {
        return runtimeService.resolveIncident(id, dto.getVariables());
    }
}
