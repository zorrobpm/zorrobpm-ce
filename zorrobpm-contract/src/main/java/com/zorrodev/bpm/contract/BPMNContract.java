package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.model.bpmn.BPMNProcessDefinition;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

import java.util.UUID;

public interface BPMNContract {

    @GetExchange("/bpmn/process-definitions/{id}")
    BPMNProcessDefinition processDefinitionById(@PathVariable UUID id);
}
