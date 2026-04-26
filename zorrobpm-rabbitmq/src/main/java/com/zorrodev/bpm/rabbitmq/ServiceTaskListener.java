package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceTaskListener {

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

    @RabbitListener(queuesToDeclare = @org.springframework.amqp.rabbit.annotation.Queue("zorrobpm.complete-service-task"))
    public void on(ServiceTaskCompleteData data) {
        log.info("Service task to complete message received - {}", data.getServiceTaskId());
        ServiceTaskCompleted serviceTaskCompleted = new ServiceTaskCompleted();
        serviceTaskCompleted.setServiceTaskId(data.getServiceTaskId());
        serviceTaskCompleted.setVariables(data.getVariables());
        publisher.publishEvent(serviceTaskCompleted);
    }
}
