package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import com.zorrodev.bpm.exchange.ServiceTaskResults;
import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "zorrobpm.transport", havingValue = "rabbitmq", matchIfMissing = true)
@RequiredArgsConstructor
public class ServiceTaskListener {

    public static final String COMPLETE_SERVICE_TASK_QUEUE = "zorrobpm.complete-service-task";
    public static final String COMPLETE_SERVICE_TASK_DLQ = COMPLETE_SERVICE_TASK_QUEUE + ".dlq";

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final ApplicationEventPublisher publisher;

    @EventListener
    public void on(ServiceTaskEnqueued event) {
        JobDetailModel detail = event.getDetail();

        String queueName = "zorrobpm.jobs." + detail.getJob();
        if (amqpAdmin.getQueueInfo(queueName) == null) {
            Queue queue = new Queue(queueName, true);
            amqpAdmin.declareQueue(queue);
            log.info("Queue {} created", queueName);
        }

        log.info("Data {}", detail);
        rabbitTemplate.convertAndSend(queueName, detail);
        log.info("Sent data for job {} to {}: {}", detail.getJob(), queueName, detail.getVariables());
    }

    @RabbitListener(queuesToDeclare = @org.springframework.amqp.rabbit.annotation.Queue(COMPLETE_SERVICE_TASK_QUEUE),
        containerFactory = RabbitConfiguration.COMPLETE_SERVICE_TASK_CONTAINER_FACTORY)
    public void on(ServiceTaskCompleteData data) {
        if (data.getServiceTaskId() == null) {
            throw new InvalidServiceTaskResultException("Service task result without a service task id");
        }
        publisher.publishEvent(ServiceTaskResults.toEvent(data));
    }
}
