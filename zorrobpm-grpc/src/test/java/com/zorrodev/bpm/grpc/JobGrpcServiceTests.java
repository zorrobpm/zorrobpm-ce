package com.zorrodev.bpm.grpc;

import com.google.protobuf.Duration;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.IncidentEntity;
import com.zorrodev.bpm.engine.entity.ServiceTaskEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.IncidentRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.exchange.grpc.CompleteJobRequest;
import com.zorrodev.bpm.exchange.grpc.FailJobRequest;
import com.zorrodev.bpm.exchange.grpc.Job;
import com.zorrodev.bpm.exchange.grpc.JobServiceGrpc;
import com.zorrodev.bpm.exchange.grpc.SubscribeJobsRequest;
import com.zorrodev.bpm.exchange.grpc.ThrowBpmnErrorRequest;
import com.zorrodev.bpm.exchange.grpc.Variable;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.zorrodev.bpm.grpc.GrpcTestSupport.await;
import static com.zorrodev.bpm.grpc.GrpcTestSupport.variable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gRPC transport end to end on the engine: an in-process gRPC server, the real dispatcher,
 * timers and the database. Each test ends every open service task so that its jobs do not reach the
 * workers of the next test.
 */
@SpringBootTest(classes = GrpcTestApplication.class)
class JobGrpcServiceTests {

    private static final java.time.Duration WAIT = java.time.Duration.ofSeconds(5);
    private static final java.time.Duration SHORT = java.time.Duration.ofMillis(800);

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private DBService dbService;

    @Autowired
    private ServiceTaskRepository serviceTaskRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private GrpcJobDispatcher dispatcher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ManagedChannel channel;
    private JobServiceGrpc.JobServiceBlockingStub stub;
    private final List<GrpcTestSupport.Worker> workers = new ArrayList<>();

    @BeforeEach
    void connect() {
        channel = InProcessChannelBuilder.forName("zorrobpm-grpc-test").directExecutor().build();
        stub = JobServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void cleanUp() {
        workers.forEach(w -> {
            try {
                w.cancel();
            } catch (RuntimeException e) {
                // already closed
            }
        });
        await(WAIT, () -> dispatcher.subscriptionCount() == 0);
        channel.shutdownNow();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            serviceTaskRepository.findAll().stream()
                .filter(t -> t.getCompletedAt() == null)
                .forEach(t -> serviceTaskRepository.setCompletedAt(t.getId(), Instant.now())));
    }

    // ---------------------------------------------------------------- subscription

    @Test
    void subscribedWorkerGetsTheJobWithItsVariables() {
        GrpcTestSupport.Worker worker = subscribe(request("charge"));

        UUID instance = start("charge-no-retries.bpmn", variable("amount", "100", ProcessVariableType.LONG));

        Job job = worker.next(WAIT);
        assertThat(job).isNotNull();
        assertThat(job.getServiceTaskId()).isEqualTo(taskId(instance, "charge").toString());
        assertThat(job.getProcessInstanceId()).isEqualTo(instance.toString());
        assertThat(job.getJob()).isEqualTo("charge");
        assertThat(job.getServiceTaskKey()).isEqualTo("charge");
        assertThat(job.getVariablesList()).extracting(Variable::getName, Variable::getValue, Variable::getType)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("amount", "100", "LONG"));
    }

    @Test
    void emptyJobListIsRejected() {
        GrpcTestSupport.Worker worker = subscribe(SubscribeJobsRequest.newBuilder().build());

        await(WAIT, () -> worker.error != null);
        assertThat(Status.fromThrowable(worker.error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void invalidLockTimeoutAndMaxActiveJobsAreRejected() {
        GrpcTestSupport.Worker blank = subscribe(request(" "));
        GrpcTestSupport.Worker zeroLock = subscribe(request("charge").toBuilder().setLockTimeout(Duration.newBuilder().setSeconds(0)).build());
        GrpcTestSupport.Worker zeroMax = subscribe(request("charge").toBuilder().setMaxActiveJobs(0).build());

        for (GrpcTestSupport.Worker worker : List.of(blank, zeroLock, zeroMax)) {
            await(WAIT, () -> worker.error != null);
            assertThat(Status.fromThrowable(worker.error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        }
    }

    @Test
    void streamGetsNoMoreThanMaxActiveJobs() {
        GrpcTestSupport.Worker worker = subscribe(request("charge").toBuilder().setMaxActiveJobs(2).build());
        start("charge-no-retries.bpmn");
        start("charge-no-retries.bpmn");
        start("charge-no-retries.bpmn");

        Job first = worker.next(WAIT);
        assertThat(worker.next(WAIT)).isNotNull();
        assertThat(worker.next(SHORT)).isNull();

        complete(first.getServiceTaskId());

        assertThat(worker.next(WAIT)).isNotNull();
    }

    // ---------------------------------------------------------------- storage, locks and redelivery

    @Test
    void jobWaitsForASubscriber() {
        UUID instance = start("charge-no-retries.bpmn");

        GrpcTestSupport.Worker worker = subscribe(request("charge"));

        Job job = worker.next(WAIT);
        assertThat(job).isNotNull();
        assertThat(job.getServiceTaskId()).isEqualTo(taskId(instance, "charge").toString());
    }

    @Test
    void jobGoesToOneOfTwoSubscribers() {
        GrpcTestSupport.Worker a = subscribe(request("charge"));
        GrpcTestSupport.Worker b = subscribe(request("charge"));

        start("charge-no-retries.bpmn");

        await(WAIT, () -> a.jobs.size() + b.jobs.size() == 1);
        assertThat(a.next(SHORT) == null ^ b.next(SHORT) == null).isTrue();
        assertThat(a.jobs).isEmpty();
        assertThat(b.jobs).isEmpty();
    }

    @Test
    void expiredLockHandsTheJobToAnotherWorker() {
        GrpcTestSupport.Worker a = subscribe(request("charge").toBuilder().setLockTimeout(Duration.newBuilder().setSeconds(1)).build());
        UUID instance = start("charge-retry.bpmn");
        UUID charge = taskId(instance, "charge");
        assertThat(a.next(WAIT)).isNotNull();

        GrpcTestSupport.Worker b = subscribe(request("charge"));

        Job again = b.next(WAIT);
        assertThat(again).isNotNull();
        assertThat(again.getServiceTaskId()).isEqualTo(charge.toString());
        assertThat(serviceTask(charge).getRetries()).isEqualTo(1);
        assertThat(incidents(charge)).isEmpty();
    }

    @Test
    void closedStreamReleasesItsJobAtOnce() {
        GrpcTestSupport.Worker a = subscribe(request("charge").toBuilder().setLockTimeout(Duration.newBuilder().setSeconds(600)).build());
        UUID instance = start("charge-no-retries.bpmn");
        assertThat(a.next(WAIT)).isNotNull();
        GrpcTestSupport.Worker b = subscribe(request("charge"));
        assertThat(b.next(SHORT)).isNull();

        a.cancel();

        Job again = b.next(WAIT);
        assertThat(again).isNotNull();
        assertThat(again.getServiceTaskId()).isEqualTo(taskId(instance, "charge").toString());
    }

    @Test
    void failureWithRetryRedeliversTheJobWhenDue() {
        GrpcTestSupport.Worker worker = subscribe(request("charge"));
        UUID instance = start("charge-retry.bpmn");
        Job job = worker.next(WAIT);
        assertThat(job.getRetries()).isEqualTo(1);

        stub.failJob(FailJobRequest.newBuilder().setServiceTaskId(job.getServiceTaskId())
            .setErrorCode("IO").setMessage("reset").build());

        UUID charge = taskId(instance, "charge");
        assertThat(serviceTask(charge).getRetries()).isZero();
        assertThat(incidents(charge)).isEmpty();
        Instant failedAt = Instant.now();
        Job retry = worker.next(WAIT);
        assertThat(retry).isNotNull();
        assertThat(java.time.Duration.between(failedAt, Instant.now())).isGreaterThanOrEqualTo(java.time.Duration.ofMillis(700));
        assertThat(retry.getServiceTaskId()).isEqualTo(job.getServiceTaskId());
        assertThat(retry.getRetries()).isZero();
    }

    @Test
    void jobWithAnIncidentIsNotPushedUntilResolved() {
        GrpcTestSupport.Worker worker = subscribe(request("charge"));
        UUID instance = start("charge-no-retries.bpmn");
        Job job = worker.next(WAIT);

        stub.failJob(FailJobRequest.newBuilder().setServiceTaskId(job.getServiceTaskId())
            .setErrorCode("BOOM").setMessage("boom").build());

        UUID charge = taskId(instance, "charge");
        List<IncidentEntity> incidents = incidents(charge);
        assertThat(incidents).hasSize(1);
        assertThat(worker.next(SHORT)).isNull();

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            runtimeService.resolveIncident(incidents.get(0).getId(), List.of()));

        Job again = worker.next(WAIT);
        assertThat(again).isNotNull();
        assertThat(again.getServiceTaskId()).isEqualTo(charge.toString());
    }

    // ---------------------------------------------------------------- results

    @Test
    void completeJobCompletesTheTaskWithItsVariables() {
        GrpcTestSupport.Worker worker = subscribe(request("charge"));
        UUID instance = start("charge-no-retries.bpmn");
        Job job = worker.next(WAIT);

        stub.completeJob(CompleteJobRequest.newBuilder().setServiceTaskId(job.getServiceTaskId())
            .addVariables(Variable.newBuilder().setName("paid").setValue("true").setType("BOOLEAN")).build());

        assertThat(serviceTask(taskId(instance, "charge")).getCompletedAt()).isNotNull();
        assertThat(dbService.getVariables(instance)).extracting(ProcessVariable::getName, ProcessVariable::getValue)
            .contains(org.assertj.core.groups.Tuple.tuple("paid", "true"));
        assertThat(dbService.getProcessInstance(instance).getCompletedAt()).isNotNull();
    }

    @Test
    void repeatedCompletionIsIgnored() {
        GrpcTestSupport.Worker worker = subscribe(request("charge"));
        UUID instance = start("charge-no-retries.bpmn");
        Job job = worker.next(WAIT);

        complete(job.getServiceTaskId());
        complete(job.getServiceTaskId());

        assertThat(activities(instance, "done")).hasSize(1);
        assertThat(dbService.getProcessInstance(instance).getCompletedAt()).isNotNull();
    }

    @Test
    void failJobWithRetriesLeftSchedulesARetry() {
        GrpcTestSupport.Worker worker = subscribe(request("lookup"));
        UUID instance = start("lookup.bpmn");
        Job job = worker.next(WAIT);

        stub.failJob(FailJobRequest.newBuilder().setServiceTaskId(job.getServiceTaskId())
            .setErrorCode("IO").setMessage("reset").setRetryTimeout("PT1H").build());

        ServiceTaskEntity lookup = serviceTask(taskId(instance, "lookup"));
        assertThat(lookup.getRetries()).isEqualTo(2);
        assertThat(lookup.getNextRetryAt()).isNotNull();
        assertThat(incidents(lookup.getId())).isEmpty();
    }

    @Test
    void throwBpmnErrorFollowsTheErrorBoundaryEvent() {
        GrpcTestSupport.Worker worker = subscribe(request("lookup"));
        UUID instance = start("lookup.bpmn");
        Job job = worker.next(WAIT);

        stub.throwBpmnError(ThrowBpmnErrorRequest.newBuilder().setServiceTaskId(job.getServiceTaskId())
            .setErrorCode("CUSTOMER_NOT_FOUND").setMessage("no such customer").build());

        assertThat(activities(instance, "createCustomer")).hasSize(1);
        assertThat(serviceTask(taskId(instance, "lookup")).getCompletedAt()).isNotNull();
    }

    @Test
    void bpmnErrorWithoutCodeOpensAnIncident() {
        GrpcTestSupport.Worker worker = subscribe(request("lookup"));
        UUID instance = start("lookup.bpmn");
        Job job = worker.next(WAIT);

        stub.throwBpmnError(ThrowBpmnErrorRequest.newBuilder().setServiceTaskId(job.getServiceTaskId()).build());

        assertThat(incidents(taskId(instance, "lookup"))).extracting(IncidentEntity::getErrorCode)
            .containsExactly("INVALID_BPMN_ERROR");
    }

    @Test
    void resultForAnUnknownTaskIsNotFound() {
        CompleteJobRequest request = CompleteJobRequest.newBuilder().setServiceTaskId(UUID.randomUUID().toString()).build();

        assertThatThrownBy(() -> stub.completeJob(request))
            .isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void resultWithAnInvalidIdIsRejected() {
        assertThatThrownBy(() -> stub.completeJob(CompleteJobRequest.newBuilder().setServiceTaskId("not-a-uuid").build()))
            .isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        assertThatThrownBy(() -> stub.failJob(FailJobRequest.newBuilder().build()))
            .isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    // ---------------------------------------------------------------- helpers

    private GrpcTestSupport.Worker subscribe(SubscribeJobsRequest request) {
        GrpcTestSupport.Worker worker = GrpcTestSupport.Worker.subscribe(channel, request);
        workers.add(worker);
        return worker;
    }

    private static SubscribeJobsRequest request(String job) {
        return SubscribeJobsRequest.newBuilder().addJobs(job).setWorker("test").build();
    }

    private void complete(String serviceTaskId) {
        stub.completeJob(CompleteJobRequest.newBuilder().setServiceTaskId(serviceTaskId).build());
    }

    private UUID start(String file, ProcessVariable... variables) {
        return GrpcTestSupport.start(processDefinitionService, runtimeService, transactionManager, file, variables);
    }

    private UUID taskId(UUID instance, String bpmnElementId) {
        return activities(instance, bpmnElementId).get(0).getId();
    }

    private List<ActivityEntity> activities(UUID instance, String bpmnElementId) {
        return activityRepository.findAll().stream()
            .filter(a -> instance.equals(a.getProcessInstanceId()) && bpmnElementId.equals(a.getBpmnElementId()))
            .toList();
    }

    private ServiceTaskEntity serviceTask(UUID id) {
        return serviceTaskRepository.findById(id).orElseThrow();
    }

    private List<IncidentEntity> incidents(UUID activityId) {
        return incidentRepository.findAll().stream()
            .filter(i -> activityId.equals(i.getActivityId()) && i.getCompletedAt() == null)
            .toList();
    }
}
