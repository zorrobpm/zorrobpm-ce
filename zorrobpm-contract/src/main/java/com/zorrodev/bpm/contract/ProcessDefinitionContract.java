package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.UUID;

public interface ProcessDefinitionContract {

    @PostExchange(value = "/process-definitions", accept = MediaType.APPLICATION_JSON_VALUE, contentType = MediaType.APPLICATION_JSON_VALUE)
    ProcessDefinition addProcessDefinition(@RequestBody AddProcessDefinitionDTO dto);

    @GetExchange(url = "/process-definitions", accept = MediaType.APPLICATION_JSON_VALUE)
    PagedDataDTO<ProcessDefinition> getProcessDefinitions(ProcessDefinitionsQueryParameters parameters);

    @GetExchange(url = "/process-definitions/{id}", accept = MediaType.APPLICATION_JSON_VALUE)
    ProcessDefinition getProcessDefinitionById(@PathVariable UUID id);

    @GetExchange(url = "/process-definitions/{id}/xml", accept = MediaType.APPLICATION_JSON_VALUE)
    String getProcessDefinitionXml(@PathVariable("id") UUID id);
}
