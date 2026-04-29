package com.zorrodev.bpm.client.resolver;

import org.springframework.core.MethodParameter;
import org.springframework.web.service.invoker.HttpRequestValues;
import org.springframework.web.service.invoker.HttpServiceArgumentResolver;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;

public class ProcessInstanceQueryParameterArgumentResolver implements HttpServiceArgumentResolver {

    @Override
    public boolean resolve(Object argument, MethodParameter parameter, HttpRequestValues.Builder requestValues) {
        if (parameter.getParameterType().equals(ProcessInstanceQuery.class)) {
            ProcessInstanceQuery search = (ProcessInstanceQuery) argument;
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
            if (search.getParentProcessInstanceId() != null) {
                requestValues.addRequestParameter("parentProcessInstanceId", search.getParentProcessInstanceId().toString());
            }
            return true;
        }
        return false;
    }
}
