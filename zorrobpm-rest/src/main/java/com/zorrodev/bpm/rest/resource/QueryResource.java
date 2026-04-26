package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.dto.Incident;
import com.zorrodev.bpm.engine.query.IncidentQuery;
import com.zorrodev.bpm.engine.query.ProcessInstanceQuery;
import com.zorrodev.bpm.engine.query.ServiceTaskQuery;
import com.zorrodev.bpm.engine.query.UserTaskQuery;
import com.zorrodev.bpm.engine.query.VariableQuery;
import com.zorrodev.bpm.engine.service.QueryService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class QueryResource {

    private final QueryService queryService;

    @GetMapping("/variables")
    public PagedDataDTO<ProcessVariable> getVariables(@ParameterObject VariableQuery query) {
        return queryService.findVariables(query);
    }

    @GetMapping("/service-tasks")
    public PagedDataDTO<ServiceTask> getServiceTasks(@ParameterObject ServiceTaskQuery query) {
        return queryService.findServiceTasks(query);
    }

    @GetMapping("/user-tasks")
    public PagedDataDTO<UserTask> getUserTasks(@ParameterObject UserTaskQuery query) {
        return queryService.findUserTasks(query);
    }

    @GetMapping("/process-instances")
    public PagedDataDTO<ProcessInstance> getProcessInstances(@ParameterObject ProcessInstanceQuery query) {
        return queryService.findProcessInstances(query);
    }

    @GetMapping("/incidents")
    public PagedDataDTO<Incident> getProcessInstances(@ParameterObject IncidentQuery query) {
        return queryService.findIncidents(query);
    }
}
