package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.engine.entity.UserTaskEventOutboxEntity;
import com.zorrodev.bpm.engine.entity.UserTaskEventRelayLockEntity;
import com.zorrodev.bpm.engine.repository.UserTaskEventOutboxRepository;
import com.zorrodev.bpm.engine.repository.UserTaskEventRelayLockRepository;
import com.zorrodev.bpm.exchange.UserTaskEventMessage;
import com.zorrodev.bpm.exchange.UserTaskEventPublisher;
import com.zorrodev.bpm.exchange.UserTaskEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes the user task event outbox every {@code zorrobpm.events.user-task.poll-interval}: the
 * oldest events first, a batch at a time, deleted only once the broker confirmed them. The lock row
 * lets one engine node publish at a time, so the events of a task leave in the order they were written.
 * A failed batch stays in the outbox and is sent again, whole, on the next tick.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = UserTaskEvents.ENABLED_PROPERTY, havingValue = "true")
public class UserTaskEventRelay {

    private final UserTaskEventOutboxRepository outboxRepository;
    private final UserTaskEventRelayLockRepository lockRepository;
    private final ObjectProvider<UserTaskEventPublisher> publisher;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final boolean relayEnabled;
    private boolean missingPublisherReported;

    public UserTaskEventRelay(UserTaskEventOutboxRepository outboxRepository,
                              UserTaskEventRelayLockRepository lockRepository,
                              ObjectProvider<UserTaskEventPublisher> publisher,
                              PlatformTransactionManager transactionManager,
                              @Value("${" + UserTaskEvents.PREFIX + ".batch-size:100}") int batchSize,
                              @Value("${" + UserTaskEvents.RELAY_ENABLED_PROPERTY + ":true}") boolean relayEnabled) {
        this.outboxRepository = outboxRepository;
        this.lockRepository = lockRepository;
        this.publisher = publisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = Math.max(1, batchSize);
        this.relayEnabled = relayEnabled;
    }

    @Scheduled(fixedDelayString = "${" + UserTaskEvents.PREFIX + ".poll-interval:1s}")
    public void poll() {
        if (!relayEnabled) {
            return;
        }
        int published;
        do {
            published = relay();
        } while (published == batchSize);
    }

    /** Publishes one batch; returns the number of events confirmed and removed from the outbox. */
    public int relay() {
        UserTaskEventPublisher target = publisher.getIfAvailable();
        if (target == null) {
            if (!missingPublisherReported) {
                log.warn("User task events are on, but no publisher is configured: the outbox is not published");
                missingPublisherReported = true;
            }
            return 0;
        }
        AtomicReference<UserTaskEventOutboxEntity> first = new AtomicReference<>();
        try {
            Integer count = transactionTemplate.execute(status -> {
                lockRepository.lock(UserTaskEventRelayLockEntity.ID)
                    .orElseThrow(() -> new IllegalStateException("The user task event relay lock row is missing"));
                List<UserTaskEventOutboxEntity> rows = outboxRepository.findAllByOrderBySeqAsc(Limit.of(batchSize));
                if (rows.isEmpty()) {
                    return 0;
                }
                first.set(rows.get(0));
                target.publish(rows.stream().map(UserTaskEventRelay::toMessage).toList());
                outboxRepository.deleteAllInBatch(rows);
                return rows.size();
            });
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            UserTaskEventOutboxEntity failed = first.get();
            if (failed == null) {
                log.warn("User task events not published: {}", e.toString());
                return 0;
            }
            log.warn("User task events not published, starting at event {} (attempt {}): {}",
                failed.getEventId(), failed.getAttempts() + 1, e.toString());
            try {
                transactionTemplate.executeWithoutResult(status -> outboxRepository.recordFailure(failed.getSeq(), e.toString()));
            } catch (RuntimeException recordError) {
                log.warn("Failure of user task event {} not recorded: {}", failed.getEventId(), recordError.toString());
            }
            return 0;
        }
    }

    static UserTaskEventMessage toMessage(UserTaskEventOutboxEntity row) {
        return new UserTaskEventMessage(row.getEventId(), row.getEventType(), row.getUserTaskId(), row.getCreatedAt(),
            row.getPayload());
    }
}
