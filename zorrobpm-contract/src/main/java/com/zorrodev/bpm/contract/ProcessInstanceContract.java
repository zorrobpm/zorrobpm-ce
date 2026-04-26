package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.ProcessInstancesQueryParameters;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.UUID;

public interface ProcessInstanceContract {

    @PostExchange(value = "/process-instances", accept = MediaType.APPLICATION_JSON_VALUE, contentType = MediaType.APPLICATION_JSON_VALUE)
    ProcessInstance startProcessInstance(@RequestBody StartProcessInstanceDTO dto);

    @GetExchange(url = "/process-instances", accept = MediaType.APPLICATION_JSON_VALUE)
    PagedDataDTO<ProcessInstance> getProcessInstances(ProcessInstancesQueryParameters parameters);

    @GetExchange(value = "/process-instances/{processInstanceId}", accept = MediaType.APPLICATION_JSON_VALUE)
    ProcessInstance getProcessInstance(@PathVariable UUID processInstanceId);
}
