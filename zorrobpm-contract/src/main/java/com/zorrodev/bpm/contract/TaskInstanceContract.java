package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ServiceTaskQueryParameters;
import com.zorrodev.bpm.contract.dto.UserTaskQueryParameters;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.UserTask;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.UUID;

public interface TaskInstanceContract {

    @PostExchange(url = "/complete-service-task-instances", accept = MediaType.APPLICATION_JSON_VALUE, contentType = MediaType.APPLICATION_JSON_VALUE)
    void completeServiceTaskInstance(@RequestBody CompleteTaskDTO dto);

    @PostExchange(url = "/complete-user-task-instances", accept = MediaType.APPLICATION_JSON_VALUE, contentType = MediaType.APPLICATION_JSON_VALUE)
    void completeUserTaskInstance(@RequestBody CompleteTaskDTO dto);

    @GetExchange(url = "/service-task-instances", accept = MediaType.APPLICATION_JSON_VALUE)
    PagedDataDTO<ServiceTask> serviceTaskInstances(ServiceTaskQueryParameters parameters);

    @GetExchange(url = "/user-task-instances", accept = MediaType.APPLICATION_JSON_VALUE)
    PagedDataDTO<UserTask> userTaskInstances(UserTaskQueryParameters parameters);

    @GetExchange(url = "/user-task-instances/{userTaskInstanceId}", accept = MediaType.APPLICATION_JSON_VALUE)
    UserTask userTaskInstanceById(@PathVariable UUID userTaskInstanceId);

}
