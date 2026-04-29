package com.zorrodev.bpm.client.resolver;

import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import org.springframework.core.MethodParameter;
import org.springframework.web.service.invoker.HttpRequestValues;
import org.springframework.web.service.invoker.HttpServiceArgumentResolver;

public class ServiceTaskQueryParametersArgumentResolver implements HttpServiceArgumentResolver {
    @Override
    public boolean resolve(Object argument, MethodParameter parameter, HttpRequestValues.Builder requestValues) {
        if (parameter.getParameterType().equals(ServiceTaskQuery.class)) {
            ServiceTaskQuery parameters = (ServiceTaskQuery) argument;
            requestValues.addRequestParameter("pageIndex", parameters.getPageIndex().toString());
            requestValues.addRequestParameter("pageSize", parameters.getPageSize().toString());
            if (parameters.getId() != null) {
                requestValues.addRequestParameter("id", parameters.getId().toString());
            }
            if (parameters.getCompleted() != null) {
                requestValues.addRequestParameter("completed", parameters.getCompleted().toString());
            }
            if (parameters.getJobType() != null) {
                requestValues.addRequestParameter("jobType", parameters.getJobType());
            }
            if (parameters.getProcessInstanceId() != null) {
                requestValues.addRequestParameter("processInstanceId", parameters.getProcessInstanceId().toString());
            }
            return true;
        }
        return false;
    }
}

