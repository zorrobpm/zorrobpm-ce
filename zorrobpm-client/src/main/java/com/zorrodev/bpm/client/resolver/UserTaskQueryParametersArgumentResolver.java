package com.zorrodev.bpm.client.resolver;

import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import org.springframework.core.MethodParameter;
import org.springframework.web.service.invoker.HttpRequestValues;
import org.springframework.web.service.invoker.HttpServiceArgumentResolver;

public class UserTaskQueryParametersArgumentResolver implements HttpServiceArgumentResolver {
    @Override
    public boolean resolve(Object argument, MethodParameter parameter, HttpRequestValues.Builder requestValues) {
        if (parameter.getParameterType().equals(UserTaskQuery.class)) {
            UserTaskQuery parameters = (UserTaskQuery) argument;
            requestValues.addRequestParameter("pageIndex", parameters.getPageIndex().toString());
            requestValues.addRequestParameter("pageSize", parameters.getPageSize().toString());
            if (parameters.getId() != null) {
                requestValues.addRequestParameter("id", parameters.getId().toString());
            }
            if (parameters.getAssignee() != null) {
                requestValues.addRequestParameter("assignee", parameters.getAssignee());
            }
            if (parameters.getAssigned() != null) {
                requestValues.addRequestParameter("assigned", parameters.getAssigned().toString());
            }
            if (parameters.getProcessInstanceId() != null) {
                requestValues.addRequestParameter("processInstanceId", parameters.getProcessInstanceId().toString());
            }
            return true;
        }
        return false;
    }
}
