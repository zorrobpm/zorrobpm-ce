package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.UserTask;
import org.springframework.web.service.annotation.GetExchange;

public interface QueryContract {

    @GetExchange("/variables")
    PagedDataDTO<ProcessVariable> getVariables(VariableQuery query);

    @GetExchange("/service-tasks")
    PagedDataDTO<ServiceTask> getServiceTasks(ServiceTaskQuery query);

    @GetExchange("/user-tasks")
    PagedDataDTO<UserTask> getUserTasks(UserTaskQuery query);

    @GetExchange("/process-instances")
    PagedDataDTO<ProcessInstance> getProcessInstances(ProcessInstanceQuery query);

    @GetExchange("/incidents")
    PagedDataDTO<Incident> getProcessInstances(IncidentQuery query);

}
