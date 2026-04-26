package com.zorrodev.bpm.engine.service;

import com.zorrodev.bpm.event.ProcessEngineEvent;

public interface ProcessEngineEventsService {

    void sendRuntimeEvent(ProcessEngineEvent event);

    void sendDefinitionEvent(ProcessEngineEvent event);
}
