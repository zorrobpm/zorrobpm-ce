package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.model.ProcessInstance;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ServiceTask;
import com.zorrodev.bpm.contract.model.UserTask;
import com.zorrodev.bpm.engine.dto.Incident;
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
import com.zorrodev.bpm.engine.query.IncidentQuery;
import com.zorrodev.bpm.engine.query.ProcessInstanceQuery;
import com.zorrodev.bpm.engine.query.ServiceTaskQuery;
import com.zorrodev.bpm.engine.query.UserTaskQuery;
import com.zorrodev.bpm.engine.query.VariableQuery;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.repository.VariableRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.QueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class QueryServiceImpl implements QueryService {

    private final DBService dbService;

    private final ServiceTaskMapper serviceTaskMapper;
    private final UserTaskMapper userTaskMapper;
    private final ProcessInstanceMapper processInstanceMapper;
    private final IncidentMapper incidentMapper;
    private final VariableMapper variableMapper;

    private final UserTaskRepository userTaskRepository;
    private final ServiceTaskRepository serviceTaskRepository;
    private final IncidentRepository incidentRepository;
    private final ProcessInstanceRepository processInstanceRepository;
    private final VariableRepository variableRepository;

    @Override
    public PagedDataDTO<ServiceTask> findServiceTasks(ServiceTaskQuery query) {
        List<Specification<ServiceTaskEntity>> specifications = new LinkedList<>();
        if (query.getProcessInstanceId() != null) {
            specifications.add(ServiceTaskRepository.byProcessInstanceId(query.getProcessInstanceId()));
        }
        if (query.getId() != null) {
            specifications.add(ServiceTaskRepository.byId(query.getId()));
        }
        Specification<ServiceTaskEntity> all = Specification.allOf(specifications);
        return toDTO(serviceTaskRepository.findAll(all, PageRequest.of(query.getPageIndex(), query.getPageSize())), serviceTaskMapper::toDTO);
    }

    @Override
    public ServiceTask getServiceTask(UUID id) {
        return serviceTaskMapper.toDTO(serviceTaskRepository.findById(id).orElseThrow());
    }

    @Override
    public PagedDataDTO<UserTask> findUserTasks(UserTaskQuery query) {
        List<Specification<UserTaskEntity>> specifications = new LinkedList<>();
        if (query.getProcessInstanceId() != null) {
            specifications.add(UserTaskRepository.byProcessInstanceId(query.getProcessInstanceId()));
        }
        if (query.getId() != null) {
            specifications.add(UserTaskRepository.byId(query.getId()));
        }
        Specification<UserTaskEntity> all = Specification.allOf(specifications);
        return toDTO(userTaskRepository.findAll(all, PageRequest.of(query.getPageIndex(), query.getPageSize())), userTaskMapper::toDTO);
    }

    @Override
    public UserTask getUserTask(UUID id) {
        return userTaskMapper.toDTO(userTaskRepository.findById(id).orElseThrow());
    }

    @Override
    public ProcessInstance getProcessInstance(UUID id) {
        return dbService.getProcessInstance(id);
    }

    @Override
    public PagedDataDTO<ProcessInstance> findProcessInstances(ProcessInstanceQuery query) {
        List<Specification<ProcessInstanceEntity>> specifications = new LinkedList<>();
        if (query.getId() != null) {
            specifications.add(ProcessInstanceRepository.byId(query.getId()));
        }
        if (query.getParentProcessInstanceId() != null) {
            specifications.add(ProcessInstanceRepository.byParentProcessInstanceId(query.getParentProcessInstanceId()));
        }
        Specification<ProcessInstanceEntity> all = Specification.allOf(specifications);
        return toDTO(processInstanceRepository.findAll(all, PageRequest.of(query.getPageIndex(), query.getPageSize())), processInstanceMapper::toDTO);
    }

    @Override
    public Incident getIncident(UUID id) {
        return dbService.getIncident(id);
    }

    @Override
    public PagedDataDTO<Incident> findIncidents(IncidentQuery query) {
        List<Specification<IncidentEntity>> specifications = new LinkedList<>();
        Specification<IncidentEntity> all = Specification.allOf(specifications);
        return toDTO(incidentRepository.findAll(all, PageRequest.of(query.getPageIndex(), query.getPageSize())), incidentMapper::toDTO);
    }

    @Override
    public PagedDataDTO<ProcessVariable> findVariables(VariableQuery query) {
        List<Specification<ProcessVariableEntity>> specifications = new LinkedList<>();
        if (query.getProcessInstanceId() != null) {
            specifications.add(VariableRepository.byProcessInstanceId(query.getProcessInstanceId()));
        }
        if (query.getName() != null) {
            specifications.add(VariableRepository.byName(query.getName()));
        }
        if (query.getType() != null) {
            specifications.add(VariableRepository.byType(query.getType()));
        }
        if (query.getValue() != null) {
            specifications.add(VariableRepository.byValue(query.getValue()));
        }
        Specification<ProcessVariableEntity> all = Specification.allOf(specifications);
        return toDTO(variableRepository.findAll(all, PageRequest.of(query.getPageIndex(), query.getPageSize())), variableMapper::toDTO);
    }

    private <T, S> PagedDataDTO<T> toDTO(Page<S> page, Function<S, T> converter) {
        PagedDataDTO<T> result = new PagedDataDTO<>();
        result.setTotalElements(page.getTotalElements());
        result.setPageIndex(page.getNumber());
        result.setPageSize(page.getSize());
        List<T> data = new ArrayList<>();
        for (S entity : page.getContent()) {
            data.add(converter.apply(entity));
        }
        result.setData(data);
        return result;
    }

}
