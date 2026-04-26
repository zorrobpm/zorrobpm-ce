package com.zorrodev.bpm.engine.mapper;

import com.zorrodev.bpm.engine.dto.Incident;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import org.springframework.stereotype.Component;

@Component
public class IncidentMapper {

    public Incident toDTO(IncidentEntity entity) {
        Incident pi = new Incident();
        pi.setId(entity.getId());
        pi.setActivityId(entity.getActivityId());
        pi.setMessage(entity.getMessage());
        pi.setCompletedAt(entity.getCompletedAt());
        pi.setCompletedAt(entity.getCreatedAt());
        return pi;
    }

}
