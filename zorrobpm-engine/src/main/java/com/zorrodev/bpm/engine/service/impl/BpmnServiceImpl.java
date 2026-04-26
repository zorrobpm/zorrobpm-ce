package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.bpmn.model.BpmnProcessDefinitionModel;
import com.zorrodev.bpm.engine.service.BpmnParseService;
import com.zorrodev.bpm.engine.service.BpmnService;
import com.zorrodev.bpm.engine.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BpmnServiceImpl implements BpmnService {

    private final Map<UUID, BpmnProcessDefinitionModel> models = new HashMap<>();

    private final FileService fileService;
    private final BpmnParseService bpmnParseService;

    @Override
    public void addProcessDefinition(UUID id, BpmnProcessDefinitionModel model) {
        models.put(id, model);
    }

    @SneakyThrows
    @Override
    public BpmnProcessDefinitionModel getProcessDefinitionModelById(UUID id) {
        if (!models.containsKey(id)) {
            String bpmn = fileService.getFileBytes(id);
            BpmnProcessDefinitionModel model = bpmnParseService.parse(bpmn);
            addProcessDefinition(id, model);
        }
        return models.get(id);
    }
}
