package com.zorrodev.bpm.grpc;

import com.zorrodev.bpm.contract.exception.ServiceTaskNotFoundException;
import com.zorrodev.bpm.contract.exception.TaskNotActiveException;
import com.zorrodev.bpm.engine.service.ServiceTaskResultService;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskResults;
import com.zorrodev.bpm.exchange.grpc.CompleteJobRequest;
import com.zorrodev.bpm.exchange.grpc.FailJobRequest;
import com.zorrodev.bpm.exchange.grpc.Job;
import com.zorrodev.bpm.exchange.grpc.JobResultResponse;
import com.zorrodev.bpm.exchange.grpc.JobServiceGrpc;
import com.zorrodev.bpm.exchange.grpc.SubscribeJobsRequest;
import com.zorrodev.bpm.exchange.grpc.ThrowBpmnErrorRequest;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The gRPC service of service task jobs: workers subscribe to jobs with a server stream and report
 * results with unary calls. A result is applied in a transaction of its own before the call returns,
 * by the same rules as a result from the RabbitMQ queue.
 * <p>
 * Result statuses: unknown service task - {@code NOT_FOUND}; missing or invalid id or variable -
 * {@code INVALID_ARGUMENT}; late result for a completed, interrupted or retrying task - {@code OK}
 * without effect; database failure - {@code UNAVAILABLE}; anything else - {@code INTERNAL}.
 */
@Slf4j
public class JobGrpcService extends JobServiceGrpc.JobServiceImplBase {

    private final GrpcJobDispatcher dispatcher;
    private final ServiceTaskResultService resultService;
    private final TransactionTemplate transaction;

    public JobGrpcService(GrpcJobDispatcher dispatcher, ServiceTaskResultService resultService,
                          PlatformTransactionManager transactionManager) {
        this.dispatcher = dispatcher;
        this.resultService = resultService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void subscribeJobs(SubscribeJobsRequest request, StreamObserver<Job> responseObserver) {
        Set<String> jobs = new LinkedHashSet<>();
        for (String job : request.getJobsList()) {
            if (job.isBlank()) {
                responseObserver.onError(invalid("Job types must not be blank"));
                return;
            }
            jobs.add(job.trim());
        }
        if (jobs.isEmpty()) {
            responseObserver.onError(invalid("At least one job type is required"));
            return;
        }
        Duration lockTimeout = null;
        if (request.hasLockTimeout()) {
            lockTimeout = Duration.ofSeconds(request.getLockTimeout().getSeconds(), request.getLockTimeout().getNanos());
            if (lockTimeout.isZero() || lockTimeout.isNegative()) {
                responseObserver.onError(invalid("Lock timeout must be positive"));
                return;
            }
        }
        Integer maxActiveJobs = null;
        if (request.hasMaxActiveJobs()) {
            maxActiveJobs = request.getMaxActiveJobs();
            if (maxActiveJobs <= 0) {
                responseObserver.onError(invalid("Max active jobs must be positive"));
                return;
            }
        }
        String worker = request.getWorker().isBlank() ? "unnamed" : request.getWorker();
        dispatcher.subscribe(worker, jobs, lockTimeout, maxActiveJobs, (ServerCallStreamObserver<Job>) responseObserver);
    }

    @Override
    public void completeJob(CompleteJobRequest request, StreamObserver<JobResultResponse> responseObserver) {
        applyResult(() -> JobMessages.completed(request), responseObserver);
    }

    @Override
    public void failJob(FailJobRequest request, StreamObserver<JobResultResponse> responseObserver) {
        applyResult(() -> JobMessages.failed(request), responseObserver);
    }

    @Override
    public void throwBpmnError(ThrowBpmnErrorRequest request, StreamObserver<JobResultResponse> responseObserver) {
        applyResult(() -> JobMessages.bpmnError(request), responseObserver);
    }

    private void applyResult(Supplier<ServiceTaskCompleteData> result, StreamObserver<JobResultResponse> responseObserver) {
        ServiceTaskCompleteData data;
        try {
            data = result.get();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(invalid(e.getMessage()));
            return;
        }
        UUID serviceTaskId = data.getServiceTaskId();
        try {
            Object event = ServiceTaskResults.toEvent(data);
            transaction.executeWithoutResult(status -> resultService.apply(event));
        } catch (TaskNotActiveException e) {
            // A late result: the task is already completed, interrupted or waiting for a retry.
            log.info("Ignoring {} result of service task {}: {}", data.getStatus(), serviceTaskId, e.getMessage());
        } catch (ServiceTaskNotFoundException e) {
            log.warn("{} result of unknown service task {}", data.getStatus(), serviceTaskId);
            responseObserver.onError(Status.NOT_FOUND.withDescription("Service task " + serviceTaskId + " not found").asRuntimeException());
            return;
        } catch (IllegalArgumentException e) {
            // For example, a variable of an unknown type.
            responseObserver.onError(invalid(e.getMessage()));
            return;
        } catch (DataAccessException | TransactionException e) {
            log.error("Failed to apply {} result of service task {}", data.getStatus(), serviceTaskId, e);
            responseObserver.onError(Status.UNAVAILABLE.withDescription(e.getMessage()).withCause(e).asRuntimeException());
            return;
        } catch (RuntimeException e) {
            log.error("Failed to apply {} result of service task {}", data.getStatus(), serviceTaskId, e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).withCause(e).asRuntimeException());
            return;
        }
        dispatcher.onResult(serviceTaskId);
        responseObserver.onNext(JobResultResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    private static RuntimeException invalid(String description) {
        return Status.INVALID_ARGUMENT.withDescription(description).asRuntimeException();
    }
}
