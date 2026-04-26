package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.ProcessDefinitionContract;
import com.zorrodev.bpm.contract.dto.AddProcessDefinitionDTO;
import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ProcessDefinitionResource implements ProcessDefinitionContract {

    private final ProcessDefinitionService processDefinitionService;
    private final FileService fileService;

    @Override
    public ProcessDefinition addProcessDefinition(AddProcessDefinitionDTO dto) {
        return processDefinitionService.addProcessDefinition(dto.getBpmn());
    }

    @Override
    public PagedDataDTO<ProcessDefinition> getProcessDefinitions(@ParameterObject ProcessDefinitionsQueryParameters parameters) {
        return processDefinitionService.getProcessDefinitions(parameters);
    }

    @Override
    public ProcessDefinition getProcessDefinitionById(UUID id) {
        return processDefinitionService.getProcessDefinitionById(id).orElseThrow( () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process definition not found")) ;
    }

    @SneakyThrows
    @Override
    public String getProcessDefinitionXml(UUID id) {
        return fileService.getFileBytes(id);
    }

}
