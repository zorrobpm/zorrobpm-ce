package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.PagedDataDTO;
import com.zorrodev.bpm.contract.dto.ProcessDefinitionsQueryParameters;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementExtensionModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementType;
import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.bpmn.model.ServiceTaskExtensionModel;
import com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskExtensionModel;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.FileService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.event.ProcessDefinitionCreatedEvent;
import com.zorrodev.bpm.event.data.ServiceTaskElement;
import com.zorrodev.bpm.event.data.UserTaskElement;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedList;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProcessDefinitionServiceImpl implements ProcessDefinitionService {

    private static final Object VERSION_LOCK = new Object();

    private final ProcessDefinitionRepository processDefinitionRepository;
    private final BpmnService bpmnService;
    private final BpmnParseService bpmnParseService;
    private final FileService fileService;

    private final ApplicationEventPublisher publisher;

    @Override
    public Optional<ProcessDefinition> getProcessDefinitionById(UUID id) {
        Optional<ProcessDefinitionEntity> processDefinitionEntityOptional = processDefinitionRepository.findById(id);

        return processDefinitionEntityOptional.map(this::fromEntity);
    }

    @SneakyThrows
    @Override
    public ProcessDefinition addProcessDefinition(String bpmn) {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String sha256 = Base64.getEncoder().encodeToString(digest.digest(bpmn.getBytes(StandardCharsets.UTF_8)));

        BpmnProcessDefinitionModel model = bpmnParseService.parse(bpmn);
        String key = model.getKey();
        String name = model.getName();

        Optional<ProcessDefinitionEntity> processDefinitionEntityOptional = processDefinitionRepository.findBySha256(sha256);

        ProcessDefinitionEntity processDefinitionEntity;

        if (processDefinitionEntityOptional.isEmpty()) {
            UUID id = UUID.randomUUID();

            synchronized (VERSION_LOCK) {
                Integer maxVersion = processDefinitionRepository.findMaxByKey(key).orElse(0);
                processDefinitionEntity = new ProcessDefinitionEntity();
                processDefinitionEntity.setId(id);
                processDefinitionEntity.setKey(key);
                processDefinitionEntity.setName(name);
                processDefinitionEntity.setVersion(maxVersion + 1);
                processDefinitionEntity.setSha256(sha256);
                processDefinitionEntity.setCreatedAt(Instant.now());
                processDefinitionEntity.setStartFormKey(model.getStartFormKey());
                processDefinitionEntity = processDefinitionRepository.save(processDefinitionEntity);
            }

            bpmnService.addProcessDefinition(id, model);
            fileService.saveFile(id, bpmn);

            publishProcessDefinitionCreatedEvent(processDefinitionEntity, model, bpmn);
        } else {
            processDefinitionEntity = processDefinitionEntityOptional.get();
        }

        return fromEntity(processDefinitionEntity);
    }

    private void publishProcessDefinitionCreatedEvent(ProcessDefinitionEntity processDefinitionEntity, BpmnProcessDefinitionModel model, String bpmn) {
        ProcessDefinitionCreatedEvent event = new ProcessDefinitionCreatedEvent();
        event.setId(processDefinitionEntity.getId());
        event.setType("ProcessDefinitionCreatedEvent");
        event.setProcessDefinitionId(processDefinitionEntity.getId());
        event.setCreatedAt(processDefinitionEntity.getCreatedAt());
        event.setProcessDefinitionName(processDefinitionEntity.getName());
        event.setProcessDefinitionKey(processDefinitionEntity.getKey());
        event.setProcessDefinitionVersion(processDefinitionEntity.getVersion());
        event.setStartFormKey(processDefinitionEntity.getStartFormKey());
        event.setBpmn(bpmn);

        event.setServiceTaskElements(new LinkedList<>());
        event.setUserTaskElements(new LinkedList<>());
        for (var x : model.getElements()) {
            if (x.getType() == BpmnElementType.USER_TASK) {
                UserTaskElement e = new UserTaskElement();
                e.setId(x.getId());
                e.setName(x.getName());
                String formKey = Optional.ofNullable(x)
                    .map(BpmnElementModel::getExtensions)
                    .map(BpmnElementExtensionModel::getUserTaskExtension)
                    .map(UserTaskExtensionModel::getFormKey)
                    .orElse(null);
                e.setFormKey(formKey);
                event.getUserTaskElements().add(e);
            } else if (x.getType() == BpmnElementType.SERVICE_TASK) {
                ServiceTaskElement e = new ServiceTaskElement();
                e.setId(x.getId());
                e.setName(x.getName());
                String jobType = Optional.ofNullable(x)
                    .map(BpmnElementModel::getExtensions)
                    .map(BpmnElementExtensionModel::getServiceTaskExtension)
                    .map(ServiceTaskExtensionModel::getJob)
                    .orElse(null);
                e.setJobType(jobType);
                event.getServiceTaskElements().add(e);
            }
        }

        publisher.publishEvent(event);
    }

    @Override
    public PagedDataDTO<ProcessDefinition> getProcessDefinitions(ProcessDefinitionsQueryParameters parameters) {
        PageRequest pageRequest = PageRequest.of(parameters.getPageIndex(), parameters.getPageSize(), Sort.by("name", "version").ascending());

        Page<ProcessDefinitionEntity> page;
        if (parameters.getLatestVersionOnly() == null || !parameters.getLatestVersionOnly()) {
            page = processDefinitionRepository.findAll(pageRequest);
        } else {
            page = processDefinitionRepository.findAllLatest(pageRequest);
        }

        PagedDataDTO<ProcessDefinition> data = new PagedDataDTO<>();
        data.setPageIndex(parameters.getPageIndex());
        data.setPageSize(parameters.getPageSize());
        data.setTotalElements(page.getTotalElements());
        data.setData(page.getContent().stream().map(this::fromEntity).toList());

        return data;
    }

    private ProcessDefinition fromEntity(ProcessDefinitionEntity processDefinitionEntity) {
        ProcessDefinition processDefinition = new ProcessDefinition();
        processDefinition.setId(processDefinitionEntity.getId());
        processDefinition.setKey(processDefinitionEntity.getKey());
        processDefinition.setVersion(processDefinitionEntity.getVersion());
        processDefinition.setName(processDefinitionEntity.getName());
        processDefinition.setSha256(processDefinitionEntity.getSha256());
        processDefinition.setCreatedAt(processDefinitionEntity.getCreatedAt());
        processDefinition.setStartFormKey(processDefinitionEntity.getStartFormKey());
        return processDefinition;
    }

}
