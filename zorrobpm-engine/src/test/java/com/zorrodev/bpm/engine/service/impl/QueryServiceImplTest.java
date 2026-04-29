package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.contract.dto.Incident;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.entity.ProcessVariableEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEntity;
import com.zorrodev.bpm.engine.mapper.IncidentMapper;
import com.zorrodev.bpm.engine.mapper.ProcessInstanceMapper;
import com.zorrodev.bpm.engine.mapper.ServiceTaskMapper;
import com.zorrodev.bpm.engine.mapper.UserTaskMapper;
import com.zorrodev.bpm.engine.mapper.VariableMapper;
import com.zorrodev.bpm.contract.dto.query.IncidentQuery;
import com.zorrodev.bpm.contract.dto.query.ProcessInstanceQuery;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.dto.query.UserTaskQuery;
import com.zorrodev.bpm.contract.dto.query.VariableQuery;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryServiceImplTest {

    @Mock private DBService dbService;
    @Mock private ServiceTaskMapper serviceTaskMapper;
    @Mock private UserTaskMapper userTaskMapper;
    @Mock private ProcessInstanceMapper processInstanceMapper;
    @Mock private IncidentMapper incidentMapper;
    @Mock private VariableMapper variableMapper;
    @Mock private UserTaskRepository userTaskRepository;
    @Mock private ServiceTaskRepository serviceTaskRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private ProcessInstanceRepository processInstanceRepository;
    @Mock private VariableRepository variableRepository;

    @InjectMocks
    private QueryServiceImpl queryService;

    @Test
    void findServiceTasks_withFilters_returnsPagedDTO() {
        ServiceTaskQuery query = new ServiceTaskQuery();
        query.setProcessInstanceId(UUID.randomUUID());
        query.setId(UUID.randomUUID());

        ServiceTaskEntity entity = new ServiceTaskEntity();
        ServiceTask dto = new ServiceTask();
        when(serviceTaskRepository.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(entity)));
        when(serviceTaskMapper.toDTO(entity)).thenReturn(dto);

        PagedDataDTO<ServiceTask> result = queryService.findServiceTasks(query);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getData()).containsExactly(dto);
    }

    @Test
    void findServiceTasks_noFilters_stillWorks() {
        ServiceTaskQuery query = new ServiceTaskQuery();
        when(serviceTaskRepository.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of()));

        PagedDataDTO<ServiceTask> result = queryService.findServiceTasks(query);

        assertThat(result.getData()).isEmpty();
    }

    @Test
    void getServiceTask_returnsMapped() {
        UUID id = UUID.randomUUID();
        ServiceTaskEntity entity = new ServiceTaskEntity();
        ServiceTask dto = new ServiceTask();
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.of(entity));
        when(serviceTaskMapper.toDTO(entity)).thenReturn(dto);

        assertThat(queryService.getServiceTask(id)).isSameAs(dto);
    }

    @Test
    void getServiceTask_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(serviceTaskRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> queryService.getServiceTask(id)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findUserTasks_withFilters_returnsPagedDTO() {
        UserTaskQuery query = new UserTaskQuery();
        query.setProcessInstanceId(UUID.randomUUID());
        query.setId(UUID.randomUUID());

        UserTaskEntity entity = new UserTaskEntity();
        UserTask dto = new UserTask();
        when(userTaskRepository.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(entity)));
        when(userTaskMapper.toDTO(entity)).thenReturn(dto);

        PagedDataDTO<UserTask> result = queryService.findUserTasks(query);

        assertThat(result.getData()).containsExactly(dto);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void getUserTask_returnsMapped() {
        UUID id = UUID.randomUUID();
        UserTaskEntity entity = new UserTaskEntity();
        UserTask dto = new UserTask();
        when(userTaskRepository.findById(id)).thenReturn(Optional.of(entity));
        when(userTaskMapper.toDTO(entity)).thenReturn(dto);

        assertThat(queryService.getUserTask(id)).isSameAs(dto);
    }

    @Test
    void getUserTask_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(userTaskRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> queryService.getUserTask(id)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getProcessInstance_delegatesToDbService() {
        UUID id = UUID.randomUUID();
        ProcessInstance pi = new ProcessInstance();
        when(dbService.getProcessInstance(id)).thenReturn(pi);

        assertThat(queryService.getProcessInstance(id)).isSameAs(pi);
    }

    @Test
    void findProcessInstances_withFilters_returnsPagedDTO() {
        ProcessInstanceQuery query = new ProcessInstanceQuery();
        query.setId(UUID.randomUUID());
        query.setParentProcessInstanceId(UUID.randomUUID());

        ProcessInstanceEntity entity = new ProcessInstanceEntity();
        ProcessInstance dto = new ProcessInstance();
        when(processInstanceRepository.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(entity)));
        when(processInstanceMapper.toDTO(entity)).thenReturn(dto);

        PagedDataDTO<ProcessInstance> result = queryService.findProcessInstances(query);

        assertThat(result.getData()).containsExactly(dto);
    }

    @Test
    void getIncident_delegatesToDbService() {
        UUID id = UUID.randomUUID();
        Incident inc = new Incident();
        when(dbService.getIncident(id)).thenReturn(inc);

        assertThat(queryService.getIncident(id)).isSameAs(inc);
    }

    @Test
    void findIncidents_returnsPagedDTO() {
        IncidentQuery query = new IncidentQuery();
        IncidentEntity entity = new IncidentEntity();
        Incident dto = new Incident();

        when(incidentRepository.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(entity)));
        when(incidentMapper.toDTO(entity)).thenReturn(dto);

        PagedDataDTO<Incident> result = queryService.findIncidents(query);

        assertThat(result.getData()).containsExactly(dto);
    }

    @Test
    void findVariables_withAllFilters_returnsPagedDTO() {
        VariableQuery query = new VariableQuery();
        query.setProcessInstanceId(UUID.randomUUID());
        query.setName("var1");
        query.setType(ProcessVariableType.LONG);
        query.setValue("1");

        ProcessVariableEntity entity = new ProcessVariableEntity();
        ProcessVariable dto = new ProcessVariable();
        when(variableRepository.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(entity)));
        when(variableMapper.toDTO(entity)).thenReturn(dto);

        PagedDataDTO<ProcessVariable> result = queryService.findVariables(query);

        assertThat(result.getData()).containsExactly(dto);
    }
}
