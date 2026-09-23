package com.zorrodev.bpm.grpc;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskResultStatus;
import com.zorrodev.bpm.exchange.grpc.CompleteJobRequest;
import com.zorrodev.bpm.exchange.grpc.FailJobRequest;
import com.zorrodev.bpm.exchange.grpc.Job;
import com.zorrodev.bpm.exchange.grpc.ThrowBpmnErrorRequest;
import com.zorrodev.bpm.exchange.grpc.Variable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Converts between the gRPC messages and the exchange models, so that a gRPC result goes through the
 * same rules as a result from the RabbitMQ queue.
 */
final class JobMessages {

    private JobMessages() {
    }

    static Job toJob(JobDetailModel detail) {
        Job.Builder job = Job.newBuilder()
            .setServiceTaskId(detail.getServiceTaskId().toString())
            .setProcessInstanceId(detail.getProcessInstanceId().toString())
            .setProcessDefinitionId(detail.getProcessDefinitionId().toString())
            .setServiceTaskKey(Objects.toString(detail.getServiceTaskKey(), ""))
            .setJob(Objects.toString(detail.getJob(), ""))
            .setRetries(Optional.ofNullable(detail.getRetries()).orElse(0));
        Optional.ofNullable(detail.getVariables()).map(Map::values).orElse(List.of())
            .forEach(v -> job.addVariables(toVariable(v)));
        return job.build();
    }

    static ServiceTaskCompleteData completed(CompleteJobRequest request) {
        ServiceTaskCompleteData data = result(request.getServiceTaskId(), ServiceTaskResultStatus.SUCCESS, request.getVariablesList());
        return data;
    }

    static ServiceTaskCompleteData failed(FailJobRequest request) {
        ServiceTaskCompleteData data = result(request.getServiceTaskId(), ServiceTaskResultStatus.FAILURE, request.getVariablesList());
        data.setErrorCode(emptyToNull(request.getErrorCode()));
        data.setMessage(emptyToNull(request.getMessage()));
        data.setDetails(emptyToNull(request.getDetails()));
        data.setRetries(request.hasRetries() ? request.getRetries() : null);
        data.setRetryTimeout(request.hasRetryTimeout() ? request.getRetryTimeout() : null);
        return data;
    }

    static ServiceTaskCompleteData bpmnError(ThrowBpmnErrorRequest request) {
        ServiceTaskCompleteData data = result(request.getServiceTaskId(), ServiceTaskResultStatus.BPMN_ERROR, request.getVariablesList());
        data.setErrorCode(emptyToNull(request.getErrorCode()));
        data.setMessage(emptyToNull(request.getMessage()));
        return data;
    }

    /**
     * @throws IllegalArgumentException when the service task id is missing or not a UUID
     */
    static UUID serviceTaskId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Service task id is required");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Service task id '" + value + "' is not a UUID");
        }
    }

    private static ServiceTaskCompleteData result(String serviceTaskId, ServiceTaskResultStatus status, List<Variable> variables) {
        ServiceTaskCompleteData data = new ServiceTaskCompleteData();
        data.setServiceTaskId(serviceTaskId(serviceTaskId));
        data.setStatus(status);
        data.setVariables(variables.stream().map(JobMessages::toProcessVariable).toList());
        return data;
    }

    private static Variable toVariable(ProcessVariable v) {
        return Variable.newBuilder()
            .setName(Objects.toString(v.getName(), ""))
            .setValue(Objects.toString(v.getValue(), ""))
            .setType(Objects.toString(v.getType(), ""))
            .build();
    }

    private static ProcessVariable toProcessVariable(Variable v) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(v.getName());
        pv.setValue(v.getValue());
        pv.setType(v.getType());
        return pv;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
