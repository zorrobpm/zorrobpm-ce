package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import java.util.UUID;

public interface RuntimeContract {

    @PostExchange("/process-instances")
    IdDTO startProcessInstance(@RequestBody StartProcessInstanceDTO dto);

    @PostExchange("/service-tasks/{id}/complete")
    IdDTO completeServiceTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto);

    @PostExchange("/user-tasks/{id}/complete")
    IdDTO completeUserTask(@PathVariable UUID id, @RequestBody CompleteTaskDTO dto);

    @PostExchange("/incidents/{id}/resolve")
    IdDTO resolveIncident(@PathVariable UUID id, @RequestBody ResolveIncidentDTO dto);

}
