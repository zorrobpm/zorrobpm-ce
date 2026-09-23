package com.zorrodev.bpm.handler.boot;

import com.google.protobuf.Duration;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.grpc.CompleteJobRequest;
import com.zorrodev.bpm.exchange.grpc.FailJobRequest;
import com.zorrodev.bpm.exchange.grpc.Job;
import com.zorrodev.bpm.exchange.grpc.JobServiceGrpc;
import com.zorrodev.bpm.exchange.grpc.SubscribeJobsRequest;
import com.zorrodev.bpm.exchange.grpc.ThrowBpmnErrorRequest;
import com.zorrodev.bpm.exchange.grpc.Variable;
import com.zorrodev.bpm.handler.JobHandler;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.MetadataUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gets jobs from the engine over gRPC and runs the {@link JobHandler} of each: one
 * {@code SubscribeJobs} stream for the job types of all handlers, at most {@code max-active-jobs}
 * handlers at a time, the outcome sent with {@code CompleteJob}, {@code ThrowBpmnError} or
 * {@code FailJob} by the same rules as over RabbitMQ.
 * <p>
 * A broken stream or an unavailable engine never stops the application: the worker reconnects with a
 * pause growing from {@code reconnect-interval} to {@code reconnect-max-interval}. A result the engine
 * cannot take for the moment is sent again a few times; if it still cannot be sent, the engine pushes
 * the job again once its lock expires, so a handler runs at least once and may run again.
 */
@Slf4j
public class GrpcJobWorker implements SmartLifecycle {

    static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Set<Status.Code> TRANSIENT = EnumSet.of(Status.Code.UNAVAILABLE, Status.Code.INTERNAL,
        Status.Code.DEADLINE_EXCEEDED, Status.Code.RESOURCE_EXHAUSTED);

    private final ManagedChannel channel;
    private final Map<String, JobHandler> handlers;
    private final GrpcHandlerProperties properties;
    private final String worker;
    private final JobServiceGrpc.JobServiceStub jobs;
    private final JobServiceGrpc.JobServiceBlockingStub results;
    private final ExecutorService pool;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger reconnects = new AtomicInteger();

    private volatile boolean running;
    private volatile ClientCallStreamObserver<SubscribeJobsRequest> call;
    private final CountDownLatch stopped = new CountDownLatch(1);

    public GrpcJobWorker(ManagedChannel channel, List<JobHandler> handlers, GrpcHandlerProperties properties, String worker) {
        this.channel = channel;
        this.handlers = new LinkedHashMap<>();
        handlers.forEach(h -> this.handlers.put(h.getJob(), h));
        this.properties = properties;
        this.worker = worker;
        JobServiceGrpc.JobServiceStub stub = JobServiceGrpc.newStub(channel);
        JobServiceGrpc.JobServiceBlockingStub blocking = JobServiceGrpc.newBlockingStub(channel);
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            Metadata headers = new Metadata();
            headers.put(AUTHORIZATION, "Bearer " + properties.getToken());
            stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
            blocking = blocking.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        }
        this.jobs = stub;
        this.results = blocking;
        AtomicInteger threads = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(Math.max(1, properties.getMaxActiveJobs()), r -> {
            Thread thread = new Thread(r, "zorrobpm-job-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "zorrobpm-job-subscription");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start() {
        running = true;
        keepAlive();
        if (handlers.isEmpty()) {
            log.info("No job handlers, not subscribing to the engine");
            return;
        }
        subscribe();
    }

    /**
     * The threads of gRPC and of this worker are daemon threads: without a web server nothing else
     * would keep the JVM of a worker application alive (the listener containers do it on RabbitMQ).
     */
    private void keepAlive() {
        Thread thread = new Thread(() -> {
            try {
                stopped.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "zorrobpm-job-worker-keep-alive");
        thread.setDaemon(false);
        thread.start();
    }

    private void subscribe() {
        if (!running) {
            return;
        }
        SubscribeJobsRequest.Builder request = SubscribeJobsRequest.newBuilder()
            .addAllJobs(handlers.keySet())
            .setWorker(worker)
            .setMaxActiveJobs(Math.max(1, properties.getMaxActiveJobs()));
        if (properties.getLockTimeout() != null) {
            request.setLockTimeout(Duration.newBuilder()
                .setSeconds(properties.getLockTimeout().getSeconds())
                .setNanos(properties.getLockTimeout().getNano()));
        }
        log.info("Subscribing to jobs {} at {}", handlers.keySet(), properties.getAddress());
        jobs.subscribeJobs(request.build(), new ClientResponseObserver<SubscribeJobsRequest, Job>() {
            @Override
            public void beforeStart(ClientCallStreamObserver<SubscribeJobsRequest> requestStream) {
                call = requestStream;
            }

            @Override
            public void onNext(Job job) {
                reconnects.set(0);
                pool.execute(() -> process(job));
            }

            @Override
            public void onError(Throwable t) {
                if (running) {
                    log.warn("Stream of jobs from {} broke: {}", properties.getAddress(), Status.fromThrowable(t));
                    reconnect();
                }
            }

            @Override
            public void onCompleted() {
                if (running) {
                    log.info("Engine at {} closed the stream of jobs", properties.getAddress());
                    reconnect();
                }
            }
        });
    }

    private void reconnect() {
        int attempt = reconnects.getAndIncrement();
        long first = properties.getReconnectInterval().toMillis();
        long delay = Math.min(properties.getReconnectMaxInterval().toMillis(), first << Math.min(attempt, 20));
        log.info("Reconnecting to {} in {} ms", properties.getAddress(), delay);
        try {
            scheduler.schedule(this::subscribe, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // Stopped meanwhile.
        }
    }

    /** Runs the handler of the job and sends its outcome. */
    void process(Job job) {
        JobHandler handler = handlers.get(job.getJob());
        if (handler == null) {
            // Not subscribed to: leave it to the lock timeout of the engine.
            log.error("No handler for job {} of service task {}", job.getJob(), job.getServiceTaskId());
            return;
        }
        send(JobExecution.handle(handler, toModel(job)));
    }

    /**
     * Sends a result; a transient failure is retried with a growing pause. If every attempt fails, the
     * job is pushed again when its lock expires.
     */
    void send(ServiceTaskCompleteData data) {
        long pause = properties.getResultRetryInterval().toMillis();
        int attempts = Math.max(1, properties.getResultAttempts());
        for (int attempt = 1; ; attempt++) {
            try {
                call(data);
                return;
            } catch (StatusRuntimeException e) {
                Status.Code code = e.getStatus().getCode();
                if (code == Status.Code.NOT_FOUND) {
                    log.warn("Engine does not know service task {}: its {} result is dropped", data.getServiceTaskId(), data.getStatus());
                    return;
                }
                if (!TRANSIENT.contains(code) || attempt >= attempts || !running) {
                    log.error("Failed to send the {} result of service task {} ({} attempts); the engine pushes the job again after its lock expires",
                        data.getStatus(), data.getServiceTaskId(), attempt, e);
                    return;
                }
                log.warn("Failed to send the {} result of service task {}, attempt {} of {}: {}", data.getStatus(), data.getServiceTaskId(), attempt, attempts, e.getStatus());
            }
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while sending the {} result of service task {}", data.getStatus(), data.getServiceTaskId());
                return;
            }
            pause *= 2;
        }
    }

    private void call(ServiceTaskCompleteData data) {
        String id = data.getServiceTaskId().toString();
        List<Variable> variables = toVariables(data.getVariables());
        switch (data.getStatus()) {
            case BPMN_ERROR -> results.throwBpmnError(ThrowBpmnErrorRequest.newBuilder()
                .setServiceTaskId(id)
                .setErrorCode(Objects.toString(data.getErrorCode(), ""))
                .setMessage(Objects.toString(data.getMessage(), ""))
                .addAllVariables(variables)
                .build());
            case FAILURE -> {
                FailJobRequest.Builder request = FailJobRequest.newBuilder()
                    .setServiceTaskId(id)
                    .setErrorCode(Objects.toString(data.getErrorCode(), ""))
                    .setMessage(Objects.toString(data.getMessage(), ""))
                    .setDetails(Objects.toString(data.getDetails(), ""))
                    .addAllVariables(variables);
                if (data.getRetries() != null) {
                    request.setRetries(data.getRetries());
                }
                if (data.getRetryTimeout() != null) {
                    request.setRetryTimeout(data.getRetryTimeout());
                }
                results.failJob(request.build());
            }
            default -> results.completeJob(CompleteJobRequest.newBuilder()
                .setServiceTaskId(id)
                .addAllVariables(variables)
                .build());
        }
    }

    static JobDetailModel toModel(Job job) {
        JobDetailModel model = new JobDetailModel();
        model.setServiceTaskId(UUID.fromString(job.getServiceTaskId()));
        model.setProcessInstanceId(job.getProcessInstanceId().isEmpty() ? null : UUID.fromString(job.getProcessInstanceId()));
        model.setProcessDefinitionId(job.getProcessDefinitionId().isEmpty() ? null : UUID.fromString(job.getProcessDefinitionId()));
        model.setServiceTaskKey(job.getServiceTaskKey());
        model.setJob(job.getJob());
        model.setRetries(job.getRetries());
        Map<String, ProcessVariable> variables = new LinkedHashMap<>();
        for (Variable v : job.getVariablesList()) {
            ProcessVariable pv = new ProcessVariable();
            pv.setName(v.getName());
            pv.setValue(v.getValue());
            pv.setType(v.getType());
            variables.put(v.getName(), pv);
        }
        model.setVariables(variables);
        return model;
    }

    private static List<Variable> toVariables(List<ProcessVariable> variables) {
        return (variables == null ? List.<ProcessVariable>of() : variables).stream()
            .map(v -> Variable.newBuilder()
                .setName(Objects.toString(v.getName(), ""))
                .setValue(Objects.toString(v.getValue(), ""))
                .setType(Objects.toString(v.getType(), ""))
                .build())
            .toList();
    }

    /** Closes the stream, lets the running handlers finish, then closes the channel. */
    @Override
    public void stop() {
        running = false;
        ClientCallStreamObserver<SubscribeJobsRequest> current = call;
        if (current != null) {
            current.cancel("worker stopped", null);
        }
        scheduler.shutdownNow();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        channel.shutdown();
        stopped.countDown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Starts after, and stops before, the rest of the application. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
