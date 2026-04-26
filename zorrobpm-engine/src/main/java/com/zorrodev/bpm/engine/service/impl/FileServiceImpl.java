package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.entity.BpmnEntity;
import com.zorrodev.bpm.engine.repository.BpmnRepository;
import com.zorrodev.bpm.engine.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    @Value("${app.filesDir}")
    private String filesDir;

    private final BpmnRepository bpmnRepository;

    @Override
    public void saveFile(UUID id, String bpmn) {
        BpmnEntity entity = new BpmnEntity();
        entity.setId(id);
        entity.setBpmn(bpmn);
        bpmnRepository.save(entity);
    }

    @Override
    public String getFileBytes(UUID id) throws IOException {
        Optional<BpmnEntity> bpmnOptional = bpmnRepository.findById(id);

        if (bpmnOptional.isEmpty()) {
            String idStr = id.toString();
            String part1 = idStr.substring(0, 2);
            String part2 = idStr.substring(2, 4);
            String part3 = idStr.substring(4, 6);
            String part4 = idStr.substring(6, 8);

            Path dir = Paths.get(filesDir, part1, part2, part3, part4);
            Path file = dir.resolve(idStr);

            if (Files.exists(file)) {
                String bpmn = Files.readString(file);
                saveFile(id, bpmn);
                return bpmn;
            } else {
                return null;
            }
        } else {
            BpmnEntity entity = bpmnOptional.get();
            return entity.getBpmn();
        }
    }

}
