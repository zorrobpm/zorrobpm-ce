package com.zorrodev.bpm.rabbitmq;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/**
 * A service task result the engine cannot act on whatever the number of attempts, such as one
 * without a service task id: it goes to the dead-letter queue at once.
 */
public class InvalidServiceTaskResultException extends AmqpRejectAndDontRequeueException {

    public InvalidServiceTaskResultException(String message) {
        super(message);
    }
}
