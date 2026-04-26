package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.dto.CreateServiceTaskIncidentDTO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import java.util.UUID;

public interface IncidentContract {

    @PostExchange("/service-task-incidents")
    void createServiceTaskIncident(@RequestBody CreateServiceTaskIncidentDTO dto);

}
