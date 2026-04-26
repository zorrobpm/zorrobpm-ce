package com.zorrodev.bpm.engine.service;

import java.util.UUID;

public interface ServiceTaskEnqueueService {

    void enqueueAfterCommit(UUID serviceTaskId);

}
