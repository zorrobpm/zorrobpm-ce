package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.entity.BpmnEntity;
import com.zorrodev.bpm.engine.repository.BpmnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    @Mock
    private BpmnRepository bpmnRepository;

    @InjectMocks
    private FileServiceImpl fileService;

    @BeforeEach
    void setFilesDir() {
        ReflectionTestUtils.setField(fileService, "filesDir", "target/no-such-dir-for-tests");
    }

    @Test
    void saveFile_persistsEntity() {
        UUID id = UUID.randomUUID();
        String bpmn = "<bpmn/>";

        fileService.saveFile(id, bpmn);

        ArgumentCaptor<BpmnEntity> captor = ArgumentCaptor.forClass(BpmnEntity.class);
        verify(bpmnRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(id);
        assertThat(captor.getValue().getBpmn()).isEqualTo(bpmn);
    }

    @Test
    void getFileBytes_returnsBpmnFromRepository() throws IOException {
        UUID id = UUID.randomUUID();
        BpmnEntity entity = new BpmnEntity();
        entity.setId(id);
        entity.setBpmn("<bpmn/>");
        when(bpmnRepository.findById(id)).thenReturn(Optional.of(entity));

        String result = fileService.getFileBytes(id);

        assertThat(result).isEqualTo("<bpmn/>");
    }

    @Test
    void getFileBytes_returnsNullWhenMissing() throws IOException {
        UUID id = UUID.randomUUID();
        when(bpmnRepository.findById(id)).thenReturn(Optional.empty());

        String result = fileService.getFileBytes(id);

        assertThat(result).isNull();
    }
}
