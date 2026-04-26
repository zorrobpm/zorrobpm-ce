package com.zorrodev.bpm.client.resolver;

import com.zorrodev.bpm.contract.dto.ProcessInstancesQueryParameters;
import org.springframework.core.MethodParameter;
import org.springframework.web.service.invoker.HttpRequestValues;
import org.springframework.web.service.invoker.HttpServiceArgumentResolver;

public class ProcessInstanceQueryParameterArgumentResolver implements HttpServiceArgumentResolver {

    @Override
    public boolean resolve(Object argument, MethodParameter parameter, HttpRequestValues.Builder requestValues) {
        if (parameter.getParameterType().equals(ProcessInstancesQueryParameters.class)) {
            ProcessInstancesQueryParameters search = (ProcessInstancesQueryParameters) argument;
            requestValues.addRequestParameter("pageNumber", search.getPageIndex().toString());
            requestValues.addRequestParameter("pageSize", search.getPageSize().toString());
            if (search.getProcessDefinitionId() != null) {
                requestValues.addRequestParameter("processDefinitionId", search.getProcessDefinitionId().toString());
            }
            if (search.getProcessDefinitionKey() != null) {
                requestValues.addRequestParameter("processDefinitionKey", search.getProcessDefinitionKey());
            }
            if (search.getProcessDefinitionVersion() != null) {
                requestValues.addRequestParameter("processDefinitionVersion", search.getProcessDefinitionVersion().toString());
            }
            return true;
        }
        return false;
    }
}
