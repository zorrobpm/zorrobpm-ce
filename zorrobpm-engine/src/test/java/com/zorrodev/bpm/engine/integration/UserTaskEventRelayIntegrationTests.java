package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UserTaskEventOutboxEntity;
import com.zorrodev.bpm.engine.repository.UserTaskEventOutboxRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.impl.UserTaskEventRelay;
import com.zorrodev.bpm.exchange.UserTaskEventMessage;
import com.zorrodev.bpm.exchange.UserTaskEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * The outbox relay against a stand-in publisher: confirmed batches leave the outbox in order, a failed
 * batch stays and is sent again whole. The scheduled poll is off; the test drives the relay itself.
 */
@SpringBootTest(classes = TestMain.class, properties = {
    "zorrobpm.events.user-task.enabled=true",
    "zorrobpm.events.user-task.relay-enabled=false",
    "zorrobpm.events.user-task.batch-size=2",
})
@ActiveProfiles("test")
public class UserTaskEventRelayIntegrationTests {

    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private UserTaskRepository userTaskRepository;
    @Autowired private UserTaskEventOutboxRepository outboxRepository;
    @Autowired private UserTaskEventRelay relay;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private UserTaskEventPublisher publisher;

    private final List<UserTaskEventMessage> published = new ArrayList<>();

    @BeforeEach
    void drainAndRecord() {
        // Events of other test classes share the database: publish them away first.
        reset(publisher);
        while (relay.relay() > 0) {
            // drain
        }
        doAnswer(invocation -> published.addAll(invocation.getArgument(0))).when(publisher).publish(anyList());
    }

    @Test
    void confirmedBatchesLeaveTheOutboxInOrder() {
        UUID taskId = createClaimAndComplete();

        assertThat(relay.relay()).isEqualTo(2);
        assertThat(relay.relay()).isEqualTo(1);
        assertThat(relay.relay()).isZero();

        assertThat(published).extracting(UserTaskEventMessage::type)
            .containsExactly("CREATED", "ASSIGNED", "COMPLETED");
        assertThat(published).allMatch(m -> taskId.equals(m.userTaskId()));
        assertThat(published.get(0).payload()).contains("\"type\":\"CREATED\"");
        assertThat(outboxRepository.findAll()).isEmpty();
    }

    @Test
    void failedBatchStaysAndIsSentAgainWhole() {
        UUID taskId = createClaimAndComplete();
        List<UserTaskEventOutboxEntity> before = outboxRepository.findAll();
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(anyList());

        assertThat(relay.relay()).isZero();

        List<UserTaskEventOutboxEntity> after = outboxRepository.findAll();
        assertThat(after).hasSameSizeAs(before);
        UserTaskEventOutboxEntity first = after.stream().min((a, b) -> Long.compare(a.getSeq(), b.getSeq())).orElseThrow();
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).contains("broker down");

        reset(publisher);
        doAnswer(invocation -> published.addAll(invocation.getArgument(0))).when(publisher).publish(anyList());
        while (relay.relay() > 0) {
            // drain
        }
        assertThat(published).extracting(UserTaskEventMessage::type).containsExactly("CREATED", "ASSIGNED", "COMPLETED");
        assertThat(published).extracting(UserTaskEventMessage::userTaskId).containsOnly(taskId);
        assertThat(outboxRepository.findAll()).isEmpty();
    }

    private UUID createClaimAndComplete() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID instance = tx.execute(status -> {
            try {
                UUID definitionId = processDefinitionService.addProcessDefinition(
                    Files.readString(Paths.get("src/test/files/user-task-events/approve.bpmn"))).getId();
                StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
                dto.setProcessDefinitionId(definitionId);
                return runtimeService.startProcessInstance(dto).getId();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        UUID taskId = userTaskRepository.findByProcessInstanceId(instance).get(0).getId();
        tx.executeWithoutResult(status -> runtimeService.claimUserTask(taskId, "111"));
        tx.executeWithoutResult(status -> runtimeService.completeUserTask(taskId, List.of()));
        return taskId;
    }
}
