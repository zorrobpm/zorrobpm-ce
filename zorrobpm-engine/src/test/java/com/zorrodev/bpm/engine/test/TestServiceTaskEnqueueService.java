package com.zorrodev.bpm.engine.test;

import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
public class TestServiceTaskEnqueueService implements ServiceTaskEnqueueService {

    @Override
    public void enqueueAfterCommit(UUID serviceTaskId) {
        log.info("Service task enqueued => {}", serviceTaskId);
    }

}
