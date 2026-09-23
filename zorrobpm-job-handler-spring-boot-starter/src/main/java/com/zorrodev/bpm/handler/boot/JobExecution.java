package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.exchange.ErrorReport;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskResultStatus;
import com.zorrodev.bpm.handler.BpmnError;
import com.zorrodev.bpm.handler.JobFailedException;
import com.zorrodev.bpm.handler.JobHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** Runs a {@link JobHandler} and turns its outcome into the result for the engine, on every transport. */
@Slf4j
final class JobExecution {

    private JobExecution() {
    }

    /**
     * Runs the handler and builds the result for the engine: SUCCESS with the handler's variables,
     * BPMN_ERROR with the code, text and variables of a {@link BpmnError}, or FAILURE with the error
     * code, text and stack trace when the handler throws anything else, plus the retry override of a
     * {@link JobFailedException}.
     */
    static ServiceTaskCompleteData handle(JobHandler handler, JobDetailModel model) {
        ServiceTaskCompleteData completeData = new ServiceTaskCompleteData();
        completeData.setServiceTaskId(model.getServiceTaskId());
        try {
            completeData.setStatus(ServiceTaskResultStatus.SUCCESS);
            completeData.setVariables(toVariables(handler.handleJob(model)));
        } catch (BpmnError e) {
            // An expected business outcome, not a failure: no stack trace, no retries.
            log.info("Job {} threw BPMN error {} for service task {}: {}", handler.getJob(), e.getErrorCode(), model.getServiceTaskId(), e.getMessage());
            completeData.setStatus(ServiceTaskResultStatus.BPMN_ERROR);
            completeData.setErrorCode(e.getErrorCode());
            completeData.setMessage(e.getMessage());
            completeData.setVariables(toVariables(e.getVariables()));
        } catch (Exception e) {
            log.error("Job {} failed for service task {}", handler.getJob(), model.getServiceTaskId(), e);
            JobFailedException failed = e instanceof JobFailedException jobFailed ? jobFailed : null;
            ErrorReport report = ErrorReport.of(e, failed != null ? failed.getErrorCode() : null);
            completeData.setStatus(ServiceTaskResultStatus.FAILURE);
            completeData.setMessage(report.getMessage());
            completeData.setErrorCode(report.getErrorCode());
            completeData.setDetails(report.getDetails());
            if (failed != null) {
                completeData.setRetries(failed.getRetries());
                completeData.setRetryTimeout(failed.getRetryTimeout() != null ? failed.getRetryTimeout().toString() : null);
            }
            completeData.setVariables(List.of());
        }
        return completeData;
    }

    private static List<ProcessVariable> toVariables(List<ProcessVariable> variables) {
        return variables.stream().map(x -> {
            ProcessVariable v = new ProcessVariable();
            v.setName(x.getName());
            v.setValue(x.getValue());
            v.setType(x.getType().toString());
            return v;
        }).toList();
    }
}
