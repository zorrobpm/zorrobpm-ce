package com.zorrodev.bpm.test;

import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class TestServiceTaskEnqueueService implements ServiceTaskEnqueueService {

    @Override
    public void enqueueAfterCommit(UUID serviceTaskId) {
        log.info("Service task enqueued => {}", serviceTaskId);
    }

}
