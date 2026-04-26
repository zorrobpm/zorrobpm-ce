package com.zorrodev.bpm.handler;

import com.zorrodev.bpm.contract.job.JobDetailModel;
import com.zorrodev.bpm.contract.model.ProcessVariable;

import java.util.List;

public interface JobHandler {

    String getJob();

    List<ProcessVariable> handleJob(JobDetailModel model);

}
