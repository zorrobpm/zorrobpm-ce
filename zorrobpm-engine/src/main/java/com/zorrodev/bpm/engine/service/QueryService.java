package com.zorrodev.bpm.engine.service;

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

import java.util.UUID;

public interface QueryService {

    PagedDataDTO<ServiceTask> findServiceTasks(ServiceTaskQuery query);

    ServiceTask getServiceTask(UUID id);

    PagedDataDTO<UserTask> findUserTasks(UserTaskQuery query);

    UserTask getUserTask(UUID id);

    ProcessInstance getProcessInstance(UUID id);

    PagedDataDTO<ProcessInstance> findProcessInstances(ProcessInstanceQuery query);

    Incident getIncident(UUID id);

    PagedDataDTO<Incident> findIncidents(IncidentQuery query);

    PagedDataDTO<ProcessVariable> findVariables(VariableQuery query);
}
