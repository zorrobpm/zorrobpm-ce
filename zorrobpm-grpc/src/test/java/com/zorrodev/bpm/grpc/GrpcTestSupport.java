package com.zorrodev.bpm.grpc;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.exchange.grpc.Job;
import com.zorrodev.bpm.exchange.grpc.JobServiceGrpc;
import com.zorrodev.bpm.exchange.grpc.SubscribeJobsRequest;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Helpers of the gRPC transport tests: processes, a worker stream and waiting for a condition. */
final class GrpcTestSupport {

    private GrpcTestSupport() {
    }

    static UUID start(ProcessDefinitionService definitions, RuntimeService runtime, PlatformTransactionManager tm,
                      String file, ProcessVariable... variables) {
        return new TransactionTemplate(tm).execute(status -> {
            UUID definitionId = definitions.addProcessDefinition(read(file)).getId();
            StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
            dto.setProcessDefinitionId(definitionId);
            dto.setVariables(new ArrayList<>(List.of(variables)));
            return runtime.startProcessInstance(dto).getId();
        });
    }

    static ProcessVariable variable(String name, String value, ProcessVariableType type) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(type);
        return v;
    }

    private static String read(String file) {
        try (InputStream in = GrpcTestSupport.class.getResourceAsStream("/bpmn/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** A worker's SubscribeJobs stream that collects the pushed jobs. */
    static final class Worker implements ClientResponseObserver<SubscribeJobsRequest, Job> {
        final BlockingQueue<Job> jobs = new LinkedBlockingQueue<>();
        volatile Throwable error;
        volatile boolean completed;
        private volatile ClientCallStreamObserver<SubscribeJobsRequest> call;

        static Worker subscribe(ManagedChannel channel, SubscribeJobsRequest request) {
            Worker worker = new Worker();
            JobServiceGrpc.newStub(channel).subscribeJobs(request, worker);
            return worker;
        }

        @Override
        public void beforeStart(ClientCallStreamObserver<SubscribeJobsRequest> requestStream) {
            this.call = requestStream;
        }

        @Override
        public void onNext(Job job) {
            jobs.add(job);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }

        Job next(Duration timeout) {
            try {
                return jobs.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        void cancel() {
            call.cancel("worker stopped", null);
        }
    }
}
