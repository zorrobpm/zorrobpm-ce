package com.zorrodev.bpm.handler;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;

import java.util.List;

public interface JobHandler {

    String getJob();

    /**
     * Does the work of one service task and returns the variables to set on the process.
     * <p>
     * Delivery is at least once: the job message is acknowledged only after the result has been
     * sent to the engine. The handler can therefore be called again for the same
     * {@link JobDetailModel#getServiceTaskId() service task id}, for example when sending the result
     * fails, when the worker stops before the acknowledgement, or when the engine retries the job.
     * Side effects outside the process (payments, emails, calls to other systems) must be idempotent
     * by the service task id. The engine ignores a repeated result for a service task that is already
     * completed or already has an incident.
     * <p>
     * If the application enables {@code spring.rabbitmq.listener.simple.retry.enabled}, the job
     * containers inherit it: a job whose result cannot be sent is then rejected after the last
     * attempt instead of staying in the queue.
     *
     * @throws BpmnError for an expected business outcome caught by an error boundary event
     * @throws JobFailedException for a failure with its own error code or retry settings
     */
    List<ProcessVariable> handleJob(JobDetailModel model);

}
