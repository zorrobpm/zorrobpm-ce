package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import com.zorrodev.bpm.exchange.ServiceTaskFailed;
import com.zorrodev.bpm.exchange.ServiceTaskResultStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceTaskListener {

    static final String UNSUPPORTED_RESULT_STATUS = "UNSUPPORTED_RESULT_STATUS";

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
        if (data.getStatus() == ServiceTaskResultStatus.FAILURE) {
            log.info("Service task failure message received - {}: {} ({})", data.getServiceTaskId(), data.getMessage(), data.getErrorCode());
            publisher.publishEvent(failed(data.getServiceTaskId(), data.getErrorCode(), data.getMessage(), data.getDetails()));
            return;
        }
        if (data.getStatus() == ServiceTaskResultStatus.UNSUPPORTED) {
            // A status this engine does not know must not pass for a success: stop the process on the task.
            log.warn("Service task result with an unsupported status received - {}", data.getServiceTaskId());
            publisher.publishEvent(failed(data.getServiceTaskId(), UNSUPPORTED_RESULT_STATUS,
                "Unsupported service task result status", null));
            return;
        }
        log.info("Service task to complete message received - {}", data.getServiceTaskId());
        ServiceTaskCompleted serviceTaskCompleted = new ServiceTaskCompleted();
        serviceTaskCompleted.setServiceTaskId(data.getServiceTaskId());
        serviceTaskCompleted.setVariables(data.getVariables());
        publisher.publishEvent(serviceTaskCompleted);
    }

    private static ServiceTaskFailed failed(UUID serviceTaskId, String errorCode, String message, String details) {
        ServiceTaskFailed serviceTaskFailed = new ServiceTaskFailed();
        serviceTaskFailed.setServiceTaskId(serviceTaskId);
        serviceTaskFailed.setErrorCode(errorCode);
        serviceTaskFailed.setMessage(message);
        serviceTaskFailed.setDetails(details);
        return serviceTaskFailed;
    }
}
