package com.zorrodev.bpm.contract;

import com.zorrodev.bpm.contract.model.BpmnElementStatistics;
import com.zorrodev.bpm.contract.model.ProcessStatisticsResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

import java.util.List;
import java.util.UUID;

public interface ProcessStatisticsContract {

    @GetExchange("/process-definitions/{processDefinitionId}/statistics")
    ProcessStatisticsResult processDefinitionStatistics(@PathVariable UUID processDefinitionId);

    @GetExchange("/process-instances/{processInstanceId}/statistics")
    List<BpmnElementStatistics> processInstanceStatistics(@PathVariable UUID processInstanceId);

    @GetExchange("/process-instances/{processInstanceId}/incident-statistics")
    List<BpmnElementStatistics> processDefinitionIncidents(UUID processDefinitionId);

    @GetExchange("/process-definitions/{processInstanceId}/incident-statistics")
    List<BpmnElementStatistics> processInstanceIncidents(UUID processDefinitionId);
}
