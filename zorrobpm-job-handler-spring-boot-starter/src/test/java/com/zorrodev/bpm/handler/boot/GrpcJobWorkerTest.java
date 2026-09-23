package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.grpc.CompleteJobRequest;
import com.zorrodev.bpm.exchange.grpc.FailJobRequest;
import com.zorrodev.bpm.exchange.grpc.Job;
import com.zorrodev.bpm.exchange.grpc.JobResultResponse;
import com.zorrodev.bpm.exchange.grpc.JobServiceGrpc;
import com.zorrodev.bpm.exchange.grpc.SubscribeJobsRequest;
import com.zorrodev.bpm.exchange.grpc.ThrowBpmnErrorRequest;
import com.zorrodev.bpm.exchange.grpc.Variable;
import com.zorrodev.bpm.handler.BpmnError;
import com.zorrodev.bpm.handler.JobFailedException;
import com.zorrodev.bpm.handler.JobHandler;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcJobWorkerTest {

    private final FakeEngine engine = new FakeEngine();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private Server server;
    private GrpcJobWorker worker;

    @AfterEach
    void stop() {
        if (worker != null) {
            worker.stop();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- outcomes

    @Test
    void successIsCompletedWithTheHandlerVariables() throws Exception {
        start(handler(model -> {
            assertThat(model.getVariables().get("amount").getValue()).isEqualTo("100");
            return List.of(variable("paid", "true", "BOOLEAN"));
        }), properties());

        String id = engine.push(job("charge", Variable.newBuilder().setName("amount").setValue("100").setType("LONG").build()));

        CompleteJobRequest completed = engine.completed.poll(5, TimeUnit.SECONDS);
        assertThat(completed).isNotNull();
        assertThat(completed.getServiceTaskId()).isEqualTo(id);
        assertThat(completed.getVariablesList()).extracting(Variable::getName, Variable::getValue, Variable::getType)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("paid", "true", "BOOLEAN"));
        assertThat(engine.subscriptions.peek().getJobsList()).containsExactly("charge");
        assertThat(engine.subscriptions.peek().getMaxActiveJobs()).isEqualTo(32);
    }

    @Test
    void bpmnErrorIsThrown() throws Exception {
        start(handler(model -> {
            throw new BpmnError("CARD_DECLINED", "declined");
        }), properties());

        engine.push(job("charge"));

        ThrowBpmnErrorRequest thrown = engine.thrown.poll(5, TimeUnit.SECONDS);
        assertThat(thrown).isNotNull();
        assertThat(thrown.getErrorCode()).isEqualTo("CARD_DECLINED");
        assertThat(thrown.getMessage()).isEqualTo("declined");
    }

    @Test
    void jobFailedExceptionFailsWithItsCodeAndRetryOverride() throws Exception {
        start(handler(model -> {
            throw new JobFailedException("GATEWAY", "gateway down", null, 1, Duration.ofSeconds(30));
        }), properties());

        engine.push(job("charge"));

        FailJobRequest failed = engine.failed.poll(5, TimeUnit.SECONDS);
        assertThat(failed).isNotNull();
        assertThat(failed.getErrorCode()).isEqualTo("GATEWAY");
        assertThat(failed.getMessage()).isEqualTo("gateway down");
        assertThat(failed.getDetails()).contains("JobFailedException");
        assertThat(failed.getRetries()).isEqualTo(1);
        assertThat(failed.getRetryTimeout()).isEqualTo("PT30S");
    }

    @Test
    void plainExceptionLeavesRetriesToTheEngine() throws Exception {
        start(handler(model -> {
            throw new IllegalStateException("boom");
        }), properties());

        engine.push(job("charge"));

        FailJobRequest failed = engine.failed.poll(5, TimeUnit.SECONDS);
        assertThat(failed.getErrorCode()).isEqualTo("java.lang.IllegalStateException");
        assertThat(failed.hasRetries()).isFalse();
        assertThat(failed.hasRetryTimeout()).isFalse();
    }

    // ---------------------------------------------------------------- reliability

    @Test
    void unavailableEngineGetsTheResultAgain() throws Exception {
        engine.completeFailures.set(2);
        start(handler(model -> List.of()), properties());

        engine.push(job("charge"));

        assertThat(engine.completed.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(engine.completeCalls.get()).isEqualTo(3);
    }

    @Test
    void resultIsGivenUpAfterTheLastAttempt() throws Exception {
        engine.completeFailures.set(100);
        start(handler(model -> List.of()), properties());

        engine.push(job("charge"));

        long deadline = System.currentTimeMillis() + 5000;
        while (engine.completeCalls.get() < 4 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(200);
        assertThat(engine.completeCalls.get()).isEqualTo(4);
    }

    @Test
    void notFoundIsNotRetried() throws Exception {
        engine.completeStatus = Status.NOT_FOUND;
        start(handler(model -> List.of()), properties());

        engine.push(job("charge"));

        long deadline = System.currentTimeMillis() + 5000;
        while (engine.completeCalls.get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(200);
        assertThat(engine.completeCalls.get()).isEqualTo(1);
    }

    @Test
    void closedStreamIsOpenedAgain() throws Exception {
        start(handler(model -> List.of()), properties());
        await(() -> engine.subscriptions.size() == 1);

        engine.closeStreams();

        await(() -> engine.subscriptions.size() == 2);
        engine.push(job("charge"));
        assertThat(engine.completed.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void tokenIsSentWithEveryCall() throws Exception {
        GrpcHandlerProperties properties = properties();
        properties.setToken("secret");
        properties.setLockTimeout(Duration.ofSeconds(90));
        start(handler(model -> List.of()), properties);

        engine.push(job("charge"));

        assertThat(engine.completed.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(authorizations).hasSize(2).allMatch("Bearer secret"::equals);
        assertThat(engine.subscriptions.peek().getLockTimeout().getSeconds()).isEqualTo(90);
    }

    // ---------------------------------------------------------------- auto-configuration

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class, HandlerAutoConfiguration.class, GrpcHandlerAutoConfiguration.class));

    @Test
    void grpcWithoutAnAddressStopsTheStart() {
        runner.withPropertyValues("zorrobpm.handler.transport=grpc").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("zorrobpm.handler.grpc.address");
        });
    }

    @Test
    void grpcCreatesNoRabbitMqTransport() {
        runner.withPropertyValues("zorrobpm.handler.transport=grpc", "zorrobpm.handler.grpc.address=localhost:1")
            .run(context -> {
                assertThat(context).hasSingleBean(GrpcJobWorker.class);
                assertThat(context).doesNotHaveBean(HandlerAutoConfiguration.class);
            });
    }

    // ---------------------------------------------------------------- helpers

    private void start(JobHandler handler, GrpcHandlerProperties properties) throws IOException {
        String name = InProcessServerBuilder.generateName();
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <Q, A> ServerCall.Listener<Q> interceptCall(ServerCall<Q, A> call, Metadata headers, ServerCallHandler<Q, A> next) {
                String value = headers.get(GrpcJobWorker.AUTHORIZATION);
                if (value != null) {
                    authorizations.add(value);
                }
                return next.startCall(call, headers);
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
            .addService(ServerInterceptors.intercept(engine, capture)).build().start();
        worker = new GrpcJobWorker(InProcessChannelBuilder.forName(name).directExecutor().build(), List.of(handler), properties, "test");
        worker.start();
    }

    private static GrpcHandlerProperties properties() {
        GrpcHandlerProperties properties = new GrpcHandlerProperties();
        properties.setAddress("in-process");
        properties.setResultRetryInterval(Duration.ofMillis(10));
        properties.setReconnectInterval(Duration.ofMillis(50));
        return properties;
    }

    private static JobHandler handler(Function<com.zorrodev.bpm.exchange.JobDetailModel, List<ProcessVariable>> body) {
        return new JobHandler() {
            @Override
            public String getJob() {
                return "charge";
            }

            @Override
            public List<ProcessVariable> handleJob(com.zorrodev.bpm.exchange.JobDetailModel model) {
                return body.apply(model);
            }
        };
    }

    private static Job job(String type, Variable... variables) {
        return Job.newBuilder()
            .setServiceTaskId(UUID.randomUUID().toString())
            .setProcessInstanceId(UUID.randomUUID().toString())
            .setProcessDefinitionId(UUID.randomUUID().toString())
            .setServiceTaskKey(type)
            .setJob(type)
            .addAllVariables(List.of(variables))
            .build();
    }

    private static ProcessVariable variable(String name, String value, String type) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(type);
        return v;
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    /** A JobService that records the calls of the worker and pushes jobs to its open stream. */
    static final class FakeEngine extends JobServiceGrpc.JobServiceImplBase {
        final Queue<SubscribeJobsRequest> subscriptions = new ConcurrentLinkedQueue<>();
        final List<StreamObserver<Job>> streams = new CopyOnWriteArrayList<>();
        final BlockingQueue<CompleteJobRequest> completed = new LinkedBlockingQueue<>();
        final BlockingQueue<FailJobRequest> failed = new LinkedBlockingQueue<>();
        final BlockingQueue<ThrowBpmnErrorRequest> thrown = new LinkedBlockingQueue<>();
        final AtomicInteger completeCalls = new AtomicInteger();
        final AtomicInteger completeFailures = new AtomicInteger();
        volatile Status completeStatus;

        String push(Job job) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (streams.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            streams.get(streams.size() - 1).onNext(job);
            return job.getServiceTaskId();
        }

        void closeStreams() {
            List<StreamObserver<Job>> open = List.copyOf(streams);
            streams.clear();
            open.forEach(StreamObserver::onCompleted);
        }

        @Override
        public void subscribeJobs(SubscribeJobsRequest request, StreamObserver<Job> responseObserver) {
            subscriptions.add(request);
            streams.add(responseObserver);
        }

        @Override
        public void completeJob(CompleteJobRequest request, StreamObserver<JobResultResponse> responseObserver) {
            completeCalls.incrementAndGet();
            if (completeStatus != null) {
                responseObserver.onError(completeStatus.asRuntimeException());
                return;
            }
            if (completeFailures.getAndDecrement() > 0) {
                responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
                return;
            }
            completed.add(request);
            ok(responseObserver);
        }

        @Override
        public void failJob(FailJobRequest request, StreamObserver<JobResultResponse> responseObserver) {
            failed.add(request);
            ok(responseObserver);
        }

        @Override
        public void throwBpmnError(ThrowBpmnErrorRequest request, StreamObserver<JobResultResponse> responseObserver) {
            thrown.add(request);
            ok(responseObserver);
        }

        private static void ok(StreamObserver<JobResultResponse> responseObserver) {
            responseObserver.onNext(JobResultResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }
}
