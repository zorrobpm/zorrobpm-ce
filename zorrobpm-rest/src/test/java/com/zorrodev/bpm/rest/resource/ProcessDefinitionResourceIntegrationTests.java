package com.zorrodev.bpm.rest.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.FileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ProcessDefinitionResourceIntegrationTests {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProcessDefinitionRepository processDefinitionRepository;

    @Autowired
    private FileService fileService;

    @Test
    void testSuccessfulAddProcessDefinition() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/process1.bpmn"), StandardCharsets.UTF_8);
        AddProcessDefinitionDTO requestDTO = new AddProcessDefinitionDTO();
        requestDTO.setBpmn(bpmn);
        String requestJson = mapper.writeValueAsString(requestDTO);

        MvcResult result = mockMvc.perform(post("/process-definitions")
                        .content(requestJson)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        ProcessDefinition processDefinition = mapper.readValue(responseJson, ProcessDefinition.class);

        assertThat(processDefinition).isNotNull();
        assertThat(processDefinition.getKey()).isEqualTo("process1");
        assertThat(processDefinition.getVersion()).isNotNull();
        assertThat(processDefinition.getName()).isEqualTo("Process 1");

        UUID id = processDefinition.getId();
        assertThat(processDefinitionRepository.existsById(id)).isTrue();

        ProcessDefinitionEntity processDefinitionEntity = processDefinitionRepository.findById(id).orElseThrow();
        assertThat(processDefinitionEntity).isNotNull();
        assertThat(processDefinitionEntity.getId()).isEqualTo(id);
        assertThat(processDefinitionEntity.getKey()).isEqualTo("process1");
        assertThat(processDefinitionEntity.getVersion()).isNotNull();
        assertThat(processDefinitionEntity.getName()).isEqualTo("Process 1");

        String savedBpmn = fileService.getFileBytes(id);

        assertThat(savedBpmn).isNotNull();
    }
}
