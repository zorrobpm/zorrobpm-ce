package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.UserTaskEventMessage;
import com.zorrodev.bpm.exchange.UserTaskEventPublisher;
import com.zorrodev.bpm.exchange.UserTaskEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;

/**
 * Publishes user task events to the durable {@code zorrobpm.user-task-events} queue through the default
 * exchange, on a channel of its own with publisher confirms: a batch counts as published only when the
 * broker has confirmed every message of it. The queue is declared before each batch, so it exists after a
 * broker reset or a deletion: the default exchange would drop a message for a missing queue, confirmed.
 */
@Slf4j
public class RabbitUserTaskEventPublisher implements UserTaskEventPublisher {

    public static final String USER_TASK_ID_HEADER = "zorrobpm-user-task-id";
    private static final String DEFAULT_EXCHANGE = "";

    private final RabbitOperations rabbitOperations;
    private final Duration confirmTimeout;

    public RabbitUserTaskEventPublisher(RabbitOperations rabbitOperations, Duration confirmTimeout) {
        this.rabbitOperations = rabbitOperations;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publish(List<UserTaskEventMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        rabbitOperations.invoke(operations -> {
            declareQueue(operations);
            for (UserTaskEventMessage message : messages) {
                operations.send(DEFAULT_EXCHANGE, UserTaskEvents.QUEUE, toAmqp(message));
            }
            operations.waitForConfirmsOrDie(confirmTimeout.toMillis());
            return null;
        });
    }

    /** Declares the queue at once, when the broker is there; a missing broker is reported, not thrown. */
    public void declareQueue() {
        try {
            declareQueue(rabbitOperations);
        } catch (RuntimeException e) {
            log.warn("Queue {} not declared now, it will be on the first publication: {}", UserTaskEvents.QUEUE, e.toString());
        }
    }

    /** Durable, neither exclusive nor auto-deleted, without arguments: as the queues of service task jobs. */
    private static void declareQueue(RabbitOperations operations) {
        operations.execute(channel -> channel.queueDeclare(UserTaskEvents.QUEUE, true, false, false, null));
    }

    static Message toAmqp(UserTaskEventMessage message) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(message.eventId().toString());
        properties.setType(message.type());
        if (message.occurredAt() != null) {
            properties.setTimestamp(Date.from(message.occurredAt()));
        }
        properties.setHeader(USER_TASK_ID_HEADER, message.userTaskId().toString());
        return new Message(message.payload().getBytes(StandardCharsets.UTF_8), properties);
    }
}
