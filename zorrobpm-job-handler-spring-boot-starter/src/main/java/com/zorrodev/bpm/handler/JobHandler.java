package com.zorrodev.bpm.handler;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;

import java.util.List;

public interface JobHandler {

    String getJob();

    List<ProcessVariable> handleJob(JobDetailModel model);

}
