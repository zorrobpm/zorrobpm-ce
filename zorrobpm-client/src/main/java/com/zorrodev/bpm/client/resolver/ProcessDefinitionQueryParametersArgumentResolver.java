package com.zorrodev.bpm.client.resolver;

import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import org.springframework.core.MethodParameter;
import org.springframework.web.service.invoker.HttpRequestValues;
import org.springframework.web.service.invoker.HttpServiceArgumentResolver;

public class ProcessDefinitionQueryParametersArgumentResolver implements HttpServiceArgumentResolver {

    @Override
    public boolean resolve(Object argument, MethodParameter parameter, HttpRequestValues.Builder requestValues) {
        if (parameter.getParameterType().equals(ProcessDefinitionsQueryParameters.class)) {
            ProcessDefinitionsQueryParameters search = (ProcessDefinitionsQueryParameters) argument;
            requestValues.addRequestParameter("pageIndex", search.getPageIndex().toString());
            requestValues.addRequestParameter("pageSize", search.getPageSize().toString());
            return true;
        }
        return false;
    }
}
