package com.zorrodev.bpm.client.resolver;

import com.zorrodev.bpm.contract.dto.ServiceTaskQueryParameters;
import org.springframework.core.MethodParameter;
import org.springframework.web.service.invoker.HttpRequestValues;
import org.springframework.web.service.invoker.HttpServiceArgumentResolver;

public class ServiceTaskQueryParametersArgumentResolver implements HttpServiceArgumentResolver {
    @Override
    public boolean resolve(Object argument, MethodParameter parameter, HttpRequestValues.Builder requestValues) {
        if (parameter.getParameterType().equals(ServiceTaskQueryParameters.class)) {
            ServiceTaskQueryParameters parameters = (ServiceTaskQueryParameters) argument;
            requestValues.addRequestParameter("pageIndex", parameters.getPageIndex().toString());
            requestValues.addRequestParameter("pageSize", parameters.getPageSize().toString());
            if (parameters.getProcessDefinitionId() != null) {
                requestValues.addRequestParameter("processDefinitionKey", parameters.getProcessDefinitionKey());
            }
            if (parameters.getProcessDefinitionVersion() != null) {
                requestValues.addRequestParameter("processDefinitionVersion", parameters.getProcessDefinitionVersion());
            }
            if (parameters.getProcessDefinitionId() != null) {
                requestValues.addRequestParameter("processDefinitionId", parameters.getProcessDefinitionId().toString());
            }
            if (parameters.getProcessInstanceId() != null) {
                requestValues.addRequestParameter("processInstanceId", parameters.getProcessInstanceId().toString());
            }
            return true;
        }
        return false;
    }
}

