package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.service.JobDetailFactory;
import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Profile("!test")
@Service
@RequiredArgsConstructor
public class ServiceTaskEnqueueServiceImpl implements ServiceTaskEnqueueService {

    private final JobDetailFactory jobDetailFactory;
    private final ApplicationEventPublisher publisher;

    @Override
    public void enqueueAfterCommit(UUID serviceTaskId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisher.publishEvent(new ServiceTaskEnqueued(jobDetailFactory.create(serviceTaskId)));
            }
        });
    }
}
