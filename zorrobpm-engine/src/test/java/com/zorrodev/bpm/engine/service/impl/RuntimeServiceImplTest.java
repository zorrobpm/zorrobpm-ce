package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.dto.IdDTO;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.DBService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeServiceImplTest {

    @Mock private DBService dbService;
    @Mock private ActivityService activityService;
    @Mock private BpmnService bpmnService;

    @InjectMocks
    private RuntimeServiceImpl runtimeService;

    @Test
    void startProcessInstance_byId_skipsDefinitionLookup() {
        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);
        List<ProcessVariable> vars = List.of();
        dto.setVariables(vars);

        when(activityService.startProcessInstance(null, processDefinitionId, vars)).thenReturn(processInstanceId);

        IdDTO result = runtimeService.startProcessInstance(dto);

        assertThat(result.getId()).isEqualTo(processInstanceId);
        verify(dbService, never()).getProcessDefinition(any(), any());
        verify(dbService, never()).getMaxProcessDefinitionVersionByKey(any());
    }

    @Test
    void startProcessInstance_byKeyAndVersion_resolvesViaDbService() {
        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("k");
        dto.setProcessDefinitionVersion(2);

        ProcessDefinition pd = new ProcessDefinition();
        pd.setId(processDefinitionId);
        when(dbService.getProcessDefinition("k", 2)).thenReturn(pd);
        when(activityService.startProcessInstance(eq(null), eq(processDefinitionId), any())).thenReturn(processInstanceId);

        IdDTO result = runtimeService.startProcessInstance(dto);

        assertThat(result.getId()).isEqualTo(processInstanceId);
        verify(dbService, never()).getMaxProcessDefinitionVersionByKey(any());
    }

    @Test
    void startProcessInstance_byKeyOnly_resolvesMaxVersion() {
        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionKey("k");

        ProcessDefinition pd = new ProcessDefinition();
        pd.setId(processDefinitionId);
        when(dbService.getMaxProcessDefinitionVersionByKey("k")).thenReturn(3);
        when(dbService.getProcessDefinition("k", 3)).thenReturn(pd);
        when(activityService.startProcessInstance(eq(null), eq(processDefinitionId), any())).thenReturn(processInstanceId);

        IdDTO result = runtimeService.startProcessInstance(dto);

        assertThat(result.getId()).isEqualTo(processInstanceId);
    }

    @Test
    void startProcessInstance_withParent_passesParentThrough() {
        UUID parentId = UUID.randomUUID();
        UUID processDefinitionId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(processDefinitionId);

        when(activityService.startProcessInstance(eq(parentId), eq(processDefinitionId), any())).thenReturn(processInstanceId);

        IdDTO result = runtimeService.startProcessInstance(parentId, dto);

        assertThat(result.getId()).isEqualTo(processInstanceId);
    }

    @Test
    void completeServiceTask_delegatesAndReturnsId() {
        UUID id = UUID.randomUUID();
        List<ProcessVariable> vars = List.of();

        IdDTO result = runtimeService.completeServiceTask(id, vars);

        assertThat(result.getId()).isEqualTo(id);
        verify(activityService).completeServiceTask(id, vars);
    }

    @Test
    void completeUserTask_delegatesAndReturnsId() {
        UUID id = UUID.randomUUID();
        List<ProcessVariable> vars = List.of();

        IdDTO result = runtimeService.completeUserTask(id, vars);

        assertThat(result.getId()).isEqualTo(id);
        verify(activityService).completeUserTask(id, vars);
    }

    @Test
    void resolveIncident_returnsNull() {
        IdDTO result = runtimeService.resolveIncident(UUID.randomUUID(), List.of());
        assertThat(result).isNull();
    }
}
