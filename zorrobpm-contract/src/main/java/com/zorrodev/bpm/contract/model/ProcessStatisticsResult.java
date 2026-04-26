package com.zorrodev.bpm.contract.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ProcessStatisticsResult {
    private List<BpmnElementStatistics> activeUserTaskStats;
    private List<BpmnElementStatistics> completedUserTaskStats;
    private List<BpmnElementStatistics> activeServiceTaskStats;
    private List<BpmnElementStatistics> completedServiceTaskStats;
}
