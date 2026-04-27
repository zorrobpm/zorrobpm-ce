package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.event.ProcessDefinitionCreatedEvent;
import com.zorrodev.bpm.event.ProcessEngineEvent;
import com.zorrodev.bpm.event.ProcessInstanceCompletedEvent;
import com.zorrodev.bpm.event.ProcessInstanceCreatedEvent;
import com.zorrodev.bpm.event.ServiceTaskInstanceCompletedEvent;
import com.zorrodev.bpm.event.ServiceTaskInstanceCreatedEvent;
import com.zorrodev.bpm.event.UserTaskInstanceCompletedEvent;
import com.zorrodev.bpm.event.UserTaskInstanceCreatedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;

class ProcessEngineEventsServiceImplTest {

    private final ProcessEngineEventsServiceImpl service = new ProcessEngineEventsServiceImpl();

    @Test
    void sendRuntimeEvent_doesNotThrow() {
        assertThatNoException().isThrownBy(() -> service.sendRuntimeEvent(mock(ProcessEngineEvent.class)));
    }

    @Test
    void sendDefinitionEvent_doesNotThrow() {
        assertThatNoException().isThrownBy(() -> service.sendDefinitionEvent(mock(ProcessEngineEvent.class)));
    }

    @Test
    void onProcessDefinitionCreated_doesNotThrow() {
        ProcessDefinitionCreatedEvent event = new ProcessDefinitionCreatedEvent();
        event.setId(UUID.randomUUID());
        assertThatNoException().isThrownBy(() -> service.on(event));
    }

    @Test
    void onProcessInstanceCreated_doesNotThrow() {
        ProcessInstanceCreatedEvent event = new ProcessInstanceCreatedEvent();
        event.setId(UUID.randomUUID());
        assertThatNoException().isThrownBy(() -> service.on(event));
    }

    @Test
    void onProcessInstanceCompleted_doesNotThrow() {
        ProcessInstanceCompletedEvent event = new ProcessInstanceCompletedEvent();
        event.setId(UUID.randomUUID());
        assertThatNoException().isThrownBy(() -> service.on(event));
    }

    @Test
    void onServiceTaskInstanceCreated_doesNotThrow() {
        ServiceTaskInstanceCreatedEvent event = new ServiceTaskInstanceCreatedEvent();
        event.setId(UUID.randomUUID());
        assertThatNoException().isThrownBy(() -> service.on(event));
    }

    @Test
    void onServiceTaskInstanceCompleted_doesNotThrow() {
        ServiceTaskInstanceCompletedEvent event = new ServiceTaskInstanceCompletedEvent();
        event.setId(UUID.randomUUID());
        assertThatNoException().isThrownBy(() -> service.on(event));
    }

    @Test
    void onUserTaskInstanceCreated_doesNotThrow() {
        UserTaskInstanceCreatedEvent event = new UserTaskInstanceCreatedEvent();
        event.setId(UUID.randomUUID());
        assertThatNoException().isThrownBy(() -> service.on(event));
    }

    @Test
    void onUserTaskInstanceCompleted_doesNotThrow() {
        UserTaskInstanceCompletedEvent event = new UserTaskInstanceCompletedEvent();
        event.setId(UUID.randomUUID());
        assertThatNoException().isThrownBy(() -> service.on(event));
    }
}
