package com.zorrodev.bpm.grpc;

import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.exchange.grpc.CompleteJobRequest;
import com.zorrodev.bpm.exchange.grpc.Job;
import com.zorrodev.bpm.exchange.grpc.JobServiceGrpc;
import com.zorrodev.bpm.exchange.grpc.SubscribeJobsRequest;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zorrodev.bpm.grpc.GrpcTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two engine nodes on one database, each with a worker subscribed to the same job type: every job is
 * pushed and completed exactly once.
 */
class TwoEngineNodesTests {

    private static final int PROCESSES = 50;

    private final List<ConfigurableApplicationContext> nodes = new ArrayList<>();
    private final List<ManagedChannel> channels = new ArrayList<>();
    private final ExecutorService handlers = Executors.newFixedThreadPool(4);

    @BeforeEach
    void startNodes() {
        nodes.add(node("zorrobpm-node-1"));
        nodes.add(node("zorrobpm-node-2"));
    }

    @AfterEach
    void stopNodes() {
        handlers.shutdownNow();
        channels.forEach(ManagedChannel::shutdownNow);
        nodes.forEach(ConfigurableApplicationContext::close);
    }

    @Test
    void everyJobIsPushedAndCompletedOnce() {
        // The jobs wait in the database, so that both nodes compete for them from the start.
        ConfigurableApplicationContext first = nodes.get(0);
        List<UUID> instances = new ArrayList<>();
        for (int i = 0; i < PROCESSES; i++) {
            instances.add(GrpcTestSupport.start(first.getBean(ProcessDefinitionService.class), first.getBean(RuntimeService.class),
                first.getBean(PlatformTransactionManager.class), "multi.bpmn"));
        }

        Map<String, AtomicInteger> pushes = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> byNode = new ConcurrentHashMap<>();
        worker("zorrobpm-node-1", pushes, byNode);
        worker("zorrobpm-node-2", pushes, byNode);

        DBService db = first.getBean(DBService.class);
        await(Duration.ofSeconds(30), () -> instances.stream().allMatch(id -> db.getProcessInstance(id).getCompletedAt() != null));
        assertThat(pushes).hasSize(PROCESSES);
        assertThat(pushes.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
        assertThat(byNode).containsKeys("zorrobpm-node-1", "zorrobpm-node-2");
    }

    private void worker(String node, Map<String, AtomicInteger> pushes, Map<String, AtomicInteger> byNode) {
        ManagedChannel channel = InProcessChannelBuilder.forName(node).build();
        channels.add(channel);
        JobServiceGrpc.JobServiceBlockingStub results = JobServiceGrpc.newBlockingStub(channel);
        JobServiceGrpc.newStub(channel).subscribeJobs(
            SubscribeJobsRequest.newBuilder().addJobs("multi").setWorker(node).setMaxActiveJobs(5).build(),
            new StreamObserver<>() {
                @Override
                public void onNext(Job job) {
                    pushes.computeIfAbsent(job.getServiceTaskId(), id -> new AtomicInteger()).incrementAndGet();
                    byNode.computeIfAbsent(node, n -> new AtomicInteger()).incrementAndGet();
                    handlers.execute(() -> results.completeJob(
                        CompleteJobRequest.newBuilder().setServiceTaskId(job.getServiceTaskId()).build()));
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                }
            });
    }

    private static ConfigurableApplicationContext node(String name) {
        // Arguments, not default properties: they must win over application.properties of the tests.
        return new SpringApplicationBuilder(GrpcTestApplication.class).run(
            "--spring.datasource.url=jdbc:h2:mem:two-nodes;DB_CLOSE_DELAY=-1",
            "--spring.grpc.server.inprocess.name=" + name,
            "--zorrobpm.grpc.poll-interval=100ms");
    }
}
