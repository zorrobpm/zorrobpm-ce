package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ServiceTaskBpmnErrorThrown;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import com.zorrodev.bpm.exchange.ServiceTaskEnqueued;
import com.zorrodev.bpm.exchange.ServiceTaskFailed;
import com.zorrodev.bpm.exchange.ServiceTaskResultStatus;
import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceTaskListener {

    public static final String COMPLETE_SERVICE_TASK_QUEUE = "zorrobpm.complete-service-task";
    public static final String COMPLETE_SERVICE_TASK_DLQ = COMPLETE_SERVICE_TASK_QUEUE + ".dlq";

    static final String UNSUPPORTED_RESULT_STATUS = "UNSUPPORTED_RESULT_STATUS";
    static final String INVALID_BPMN_ERROR = "INVALID_BPMN_ERROR";

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
        if (data.getStatus() == ServiceTaskResultStatus.FAILURE) {
            log.info("Service task failure message received - {}: {} ({})", data.getServiceTaskId(), data.getMessage(), data.getErrorCode());
            ServiceTaskFailed failed = failed(data.getServiceTaskId(), data.getErrorCode(), data.getMessage(), data.getDetails());
            failed.setRetries(retries(data));
            failed.setRetryTimeout(retryTimeout(data));
            publisher.publishEvent(failed);
            return;
        }
        if (data.getStatus() == ServiceTaskResultStatus.BPMN_ERROR) {
            if (data.getErrorCode() == null || data.getErrorCode().isBlank()) {
                // Nothing to match a boundary event against; stop the process on the task instead of losing the message.
                log.warn("Service task BPMN error without a code received - {}", data.getServiceTaskId());
                ServiceTaskFailed failed = failed(data.getServiceTaskId(), INVALID_BPMN_ERROR,
                    "BPMN error without an error code", data.getMessage());
                failed.setRetries(0);
                publisher.publishEvent(failed);
                return;
            }
            log.info("Service task BPMN error message received - {}: {} ({})", data.getServiceTaskId(), data.getErrorCode(), data.getMessage());
            ServiceTaskBpmnErrorThrown thrown = new ServiceTaskBpmnErrorThrown();
            thrown.setServiceTaskId(data.getServiceTaskId());
            thrown.setErrorCode(data.getErrorCode());
            thrown.setMessage(data.getMessage());
            thrown.setVariables(data.getVariables());
            publisher.publishEvent(thrown);
            return;
        }
        if (data.getStatus() == ServiceTaskResultStatus.UNSUPPORTED) {
            // A status this engine does not know must not pass for a success: stop the process on the task.
            log.warn("Service task result with an unsupported status received - {}", data.getServiceTaskId());
            // A protocol error, not a handler failure: no retries.
            ServiceTaskFailed failed = failed(data.getServiceTaskId(), UNSUPPORTED_RESULT_STATUS,
                "Unsupported service task result status", null);
            failed.setRetries(0);
            publisher.publishEvent(failed);
            return;
        }
        log.info("Service task to complete message received - {}", data.getServiceTaskId());
        ServiceTaskCompleted serviceTaskCompleted = new ServiceTaskCompleted();
        serviceTaskCompleted.setServiceTaskId(data.getServiceTaskId());
        serviceTaskCompleted.setVariables(data.getVariables());
        publisher.publishEvent(serviceTaskCompleted);
    }

    /** A negative count from a worker means no retries rather than a lost message. */
    private static Integer retries(ServiceTaskCompleteData data) {
        Integer retries = data.getRetries();
        if (retries != null && retries < 0) {
            log.warn("Service task {}: negative retries {} treated as 0", data.getServiceTaskId(), retries);
            return 0;
        }
        return retries;
    }

    /** An invalid delay from a worker is dropped: the interval from BPMN applies. */
    private static String retryTimeout(ServiceTaskCompleteData data) {
        String value = data.getRetryTimeout();
        if (value == null) {
            return null;
        }
        try {
            if (!Duration.parse(value).isNegative()) {
                return value;
            }
        } catch (DateTimeParseException e) {
            // falls through to the warning
        }
        log.warn("Service task {}: invalid retryTimeout '{}' ignored, the interval from BPMN applies", data.getServiceTaskId(), value);
        return null;
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
