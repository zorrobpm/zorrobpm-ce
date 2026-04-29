package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.CompleteTaskDTO;
import com.zorrodev.bpm.contract.dto.IdDTO;
import com.zorrodev.bpm.contract.dto.ResolveIncidentDTO;
import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.service.RuntimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeResourceTest {

    @Mock
    private RuntimeService runtimeService;

    @InjectMocks
    private RuntimeResource resource;

    @Test
    void startProcessInstance_delegatesToService() {
        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        IdDTO expected = new IdDTO(UUID.randomUUID());
        when(runtimeService.startProcessInstance(dto)).thenReturn(toEngineDTO(expected));

        IdDTO result = resource.startProcessInstance(dto);

        assertThat(result.getId()).isSameAs(expected.getId());
    }

    private com.zorrodev.bpm.engine.dto.IdDTO toEngineDTO(IdDTO expected) {
        return new com.zorrodev.bpm.engine.dto.IdDTO(expected.getId());
    }

    @Test
    void completeServiceTask_delegatesToService() {
        UUID id = UUID.randomUUID();
        List<ProcessVariable> vars = List.of();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(vars);
        IdDTO expected = new IdDTO(id);
        when(runtimeService.completeServiceTask(id, vars)).thenReturn(toEngineDTO(expected));

        IdDTO result = resource.completeServiceTask(id, dto);

        assertThat(result.getId()).isSameAs(expected.getId());
        verify(runtimeService).completeServiceTask(id, vars);
    }

    @Test
    void completeUserTask_delegatesToService() {
        UUID id = UUID.randomUUID();
        List<ProcessVariable> vars = List.of();
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setVariables(vars);
        IdDTO expected = new IdDTO(id);
        when(runtimeService.completeUserTask(id, vars)).thenReturn(toEngineDTO(expected));

        IdDTO result = resource.completeUserTask(id, dto);

        assertThat(result.getId()).isSameAs(expected.getId());
        verify(runtimeService).completeUserTask(id, vars);
    }

    @Test
    void resolveIncident_delegatesToService() {
        UUID id = UUID.randomUUID();
        List<ProcessVariable> vars = List.of();
        ResolveIncidentDTO dto = new ResolveIncidentDTO();
        dto.setVariables(vars);
        IdDTO expected = new IdDTO(id);
        when(runtimeService.resolveIncident(id, vars)).thenReturn(toEngineDTO(expected));

        IdDTO result = resource.resolveIncident(id, dto);

        assertThat(result.getId()).isSameAs(expected.getId());
        verify(runtimeService).resolveIncident(id, vars);
    }
}
