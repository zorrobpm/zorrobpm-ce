package com.zorrodev.bpm.engine.listener;

import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.exchange.ServiceTaskFailed;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ServiceTaskFailedListener {

    private final ActivityService activityService;

    @Transactional
    @EventListener
    public void on(ServiceTaskFailed serviceTaskFailed) {
        activityService.failServiceTask(serviceTaskFailed.getServiceTaskId(), serviceTaskFailed.getMessage());
    }
}
