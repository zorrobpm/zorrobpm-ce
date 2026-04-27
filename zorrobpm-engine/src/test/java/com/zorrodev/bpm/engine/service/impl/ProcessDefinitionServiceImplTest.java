package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.event.ProcessDefinitionCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessDefinitionServiceImplTest {

    @Mock
    private ProcessDefinitionRepository processDefinitionRepository;

    @Mock
    private BpmnService bpmnService;

    @Mock
    private FileService fileService;

    @Mock
    private ApplicationEventPublisher publisher;

    private final BpmnParseServiceImpl bpmnParseService = new BpmnParseServiceImpl();

    private ProcessDefinitionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProcessDefinitionServiceImpl(
            processDefinitionRepository,
            bpmnService,
            bpmnParseService,
            fileService,
            publisher
        );
    }

    @Test
    void getProcessDefinitionById_returnsMappedDTOWhenFound() {
        UUID id = UUID.randomUUID();
        ProcessDefinitionEntity entity = entity(id, "key1", 1);
        when(processDefinitionRepository.findById(id)).thenReturn(Optional.of(entity));

        Optional<ProcessDefinition> result = service.getProcessDefinitionById(id);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(id);
        assertThat(result.get().getKey()).isEqualTo("key1");
        assertThat(result.get().getVersion()).isEqualTo(1);
    }

    @Test
    void getProcessDefinitionById_returnsEmptyWhenMissing() {
        UUID id = UUID.randomUUID();
        when(processDefinitionRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.getProcessDefinitionById(id)).isEmpty();
    }

    @Test
    void addProcessDefinition_newSha_savesAndPublishes() throws IOException {
        String bpmn = Files.readString(Path.of("src/test/files/test1.bpmn"));

        when(processDefinitionRepository.findBySha256(anyString())).thenReturn(Optional.empty());
        when(processDefinitionRepository.findMaxByKey("test1")).thenReturn(Optional.of(2));
        when(processDefinitionRepository.save(any(ProcessDefinitionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        ProcessDefinition result = service.addProcessDefinition(bpmn);

        ArgumentCaptor<ProcessDefinitionEntity> savedEntity = ArgumentCaptor.forClass(ProcessDefinitionEntity.class);
        verify(processDefinitionRepository).save(savedEntity.capture());
        assertThat(savedEntity.getValue().getKey()).isEqualTo("test1");
        assertThat(savedEntity.getValue().getVersion()).isEqualTo(3);

        verify(bpmnService).addProcessDefinition(eq(savedEntity.getValue().getId()), any());
        verify(fileService).saveFile(eq(savedEntity.getValue().getId()), eq(bpmn));
        verify(publisher).publishEvent(any(ProcessDefinitionCreatedEvent.class));

        assertThat(result.getKey()).isEqualTo("test1");
        assertThat(result.getVersion()).isEqualTo(3);
    }

    @Test
    void addProcessDefinition_existingSha_doesNotResaveAndDoesNotPublish() throws IOException {
        String bpmn = Files.readString(Path.of("src/test/files/test1.bpmn"));
        UUID existingId = UUID.randomUUID();
        ProcessDefinitionEntity existing = entity(existingId, "test1", 5);
        existing.setCreatedAt(Instant.now());

        when(processDefinitionRepository.findBySha256(anyString())).thenReturn(Optional.of(existing));

        ProcessDefinition result = service.addProcessDefinition(bpmn);

        verify(processDefinitionRepository, never()).save(any());
        verify(bpmnService, never()).addProcessDefinition(any(), any());
        verify(fileService, never()).saveFile(any(), any());
        verifyNoInteractions(publisher);

        assertThat(result.getId()).isEqualTo(existingId);
        assertThat(result.getVersion()).isEqualTo(5);
    }

    @Test
    void getProcessDefinitions_defaultUsesFindAll() {
        ProcessDefinitionsQueryParameters params = new ProcessDefinitionsQueryParameters();
        params.setPageIndex(0);
        params.setPageSize(10);

        Page<ProcessDefinitionEntity> page = new PageImpl<>(List.of(entity(UUID.randomUUID(), "k", 1)));
        when(processDefinitionRepository.findAll(any(Pageable.class))).thenReturn(page);

        PagedDataDTO<ProcessDefinition> result = service.getProcessDefinitions(params);

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(processDefinitionRepository, never()).findAllLatest(any());
    }

    @Test
    void getProcessDefinitions_latestOnlyUsesFindAllLatest() {
        ProcessDefinitionsQueryParameters params = new ProcessDefinitionsQueryParameters();
        params.setPageIndex(0);
        params.setPageSize(10);
        params.setLatestVersionOnly(true);

        Page<ProcessDefinitionEntity> page = new PageImpl<>(List.of(entity(UUID.randomUUID(), "k", 2)));
        when(processDefinitionRepository.findAllLatest(any(Pageable.class))).thenReturn(page);

        PagedDataDTO<ProcessDefinition> result = service.getProcessDefinitions(params);

        assertThat(result.getData()).hasSize(1);
        verify(processDefinitionRepository, never()).findAll(any(Pageable.class));
    }

    private static ProcessDefinitionEntity entity(UUID id, String key, int version) {
        ProcessDefinitionEntity e = new ProcessDefinitionEntity();
        e.setId(id);
        e.setKey(key);
        e.setName(key);
        e.setVersion(version);
        e.setSha256("sha-" + key);
        e.setCreatedAt(Instant.now());
        return e;
    }
}
