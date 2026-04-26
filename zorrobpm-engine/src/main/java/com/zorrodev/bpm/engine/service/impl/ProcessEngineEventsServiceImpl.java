package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.service.ProcessEngineEventsService;
import com.zorrodev.bpm.event.ProcessDefinitionCreatedEvent;
import com.zorrodev.bpm.event.ProcessEngineEvent;
import com.zorrodev.bpm.event.ProcessInstanceCompletedEvent;
import com.zorrodev.bpm.event.ProcessInstanceCreatedEvent;
import com.zorrodev.bpm.event.ServiceTaskInstanceCompletedEvent;
import com.zorrodev.bpm.event.ServiceTaskInstanceCreatedEvent;
import com.zorrodev.bpm.event.UserTaskInstanceCompletedEvent;
import com.zorrodev.bpm.event.UserTaskInstanceCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Profile("!test")
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessEngineEventsServiceImpl implements ProcessEngineEventsService {

    @Override
    public void sendRuntimeEvent(ProcessEngineEvent event) {
        String queueName = "zorrobpm.engine.runtime-events";
//        rabbitTemplate.convertAndSend(queueName, event);
    }

    @Override
    public void sendDefinitionEvent(ProcessEngineEvent event) {
        String queueName = "zorrobpm.engine.definition-events";
//        rabbitTemplate.convertAndSend(queueName, event);
    }

    @EventListener
    public void on(ProcessDefinitionCreatedEvent event) {
        sendRuntimeEvent(event);
        sendDefinitionEvent(event);
        log.info("Sent ProcessDefinitionCreatedEvent => {}", event.getId());
    }

    @EventListener
    public void on(ProcessInstanceCreatedEvent event) {
        sendRuntimeEvent(event);
        log.info("Sent engine ProcessInstanceCreatedEvent => {}", event.getId());
    }

    @EventListener
    public void on(ProcessInstanceCompletedEvent event) {
        sendRuntimeEvent(event);
        log.info("Sent engine ProcessInstanceCompletedEvent => {}", event.getId());
    }

    @EventListener
    public void on(ServiceTaskInstanceCreatedEvent event) {
        sendRuntimeEvent(event);
        log.info("Sent engine ServiceTaskInstanceCreatedEvent => {}", event.getId());
    }

    @EventListener
    public void on(ServiceTaskInstanceCompletedEvent event) {
        sendRuntimeEvent(event);
        log.info("Sent engine ServiceTaskInstanceCompletedEvent => {}", event.getId());
    }

    @EventListener
    public void on(UserTaskInstanceCreatedEvent event) {
        sendRuntimeEvent(event);
        log.info("Sent engine UserTaskInstanceCreatedEvent => {}", event.getId());
    }

    @EventListener
    public void on(UserTaskInstanceCompletedEvent event) {
        sendRuntimeEvent(event);
        log.info("Sent engine UserTaskInstanceCompletedEvent => {}", event.getId());
    }

}
