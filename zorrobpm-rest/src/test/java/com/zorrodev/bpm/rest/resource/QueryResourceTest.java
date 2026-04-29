package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.engine.service.QueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryResourceTest {

    @Mock
    private QueryService queryService;

    @InjectMocks
    private QueryResource resource;

    @Test
    void getVariables_delegates() {
        VariableQuery query = new VariableQuery();
        PagedDataDTO<ProcessVariable> expected = new PagedDataDTO<>();
        when(queryService.findVariables(query)).thenReturn(expected);

        assertThat(resource.getVariables(query)).isSameAs(expected);
    }

    @Test
    void getServiceTasks_delegates() {
        ServiceTaskQuery query = new ServiceTaskQuery();
        PagedDataDTO<ServiceTask> expected = new PagedDataDTO<>();
        when(queryService.findServiceTasks(query)).thenReturn(expected);

        assertThat(resource.getServiceTasks(query)).isSameAs(expected);
    }

    @Test
    void getUserTasks_delegates() {
        UserTaskQuery query = new UserTaskQuery();
        PagedDataDTO<UserTask> expected = new PagedDataDTO<>();
        when(queryService.findUserTasks(query)).thenReturn(expected);

        assertThat(resource.getUserTasks(query)).isSameAs(expected);
    }

    @Test
    void getProcessInstances_delegates() {
        ProcessInstanceQuery query = new ProcessInstanceQuery();
        PagedDataDTO<ProcessInstance> expected = new PagedDataDTO<>();
        when(queryService.findProcessInstances(query)).thenReturn(expected);

        assertThat(resource.getProcessInstances(query)).isSameAs(expected);
    }

    @Test
    void getIncidents_delegates() {
        IncidentQuery query = new IncidentQuery();
        PagedDataDTO<Incident> expected = new PagedDataDTO<>();
        when(queryService.findIncidents(query)).thenReturn(expected);

        assertThat(resource.getProcessInstances(query)).isSameAs(expected);
    }
}
