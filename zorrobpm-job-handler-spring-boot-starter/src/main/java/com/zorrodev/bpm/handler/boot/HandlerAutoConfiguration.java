package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.exchange.ErrorReport;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskResultStatus;
import com.zorrodev.bpm.handler.BpmnError;
import com.zorrodev.bpm.handler.JobFailedException;
import com.zorrodev.bpm.handler.JobHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Subscribes every {@link JobHandler} to the queue of its job and sends the outcome to the engine.
 * <p>
 * A job message is acknowledged only after its result is sent: a failed send leaves the job in the
 * queue for another delivery, so a handler runs at least once and may run again (see
 * {@link JobHandler#handleJob}). The containers come from the application's listener container
 * factory, so its settings apply; if the application enables
 * {@code spring.rabbitmq.listener.simple.retry.enabled} without a recoverer, a job whose result
 * cannot be sent is rejected after the last attempt instead of staying in the queue.
 * <p>
 * The shared factory and {@link RabbitTemplate} of the application are used as they are: the
 * starter parses and builds its messages with its own mapper and converter.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class HandlerAutoConfiguration {

    static final String COMPLETE_SERVICE_TASK_QUEUE = "zorrobpm.complete-service-task";
    static final String INVALID_JOB_MESSAGE = "INVALID_JOB_MESSAGE";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final MessageConverter RESULT_CONVERTER = new JacksonJsonMessageConverter();

    private final ApplicationContext applicationContext;
    private final SimpleRabbitListenerContainerFactory connectionFactory;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;

    @PostConstruct
    public void init() {
        Map<String, JobHandler> handlersMap = applicationContext.getBeansOfType(JobHandler.class);
        log.info("Found {} handlers", handlersMap.size());

        for (Map.Entry<String, JobHandler> entry : handlersMap.entrySet()) {
            JobHandler handler = entry.getValue();
            SimpleMessageListenerContainer container = connectionFactory.createListenerContainer();
            String queueName = "zorrobpm.jobs." + handler.getJob();
            declareIfMissing(queueName);
            declareIfMissing(deadLetterQueue(queueName));
            container.setQueueNames(queueName);
            log.info("Subscribing to {}", queueName);
            container.setMessageListener(message -> onMessage(handler, queueName, message));
            container.start();
        }

    }

    /**
     * Runs the handler on a readable job and sends its result. An unreadable job does not reach the
     * handler: the engine gets a FAILURE when the service task id can be read, otherwise the message
     * goes to the dead-letter queue of the job. A failed send leaves the message unacknowledged.
     */
    void onMessage(JobHandler handler, String queueName, Message message) {
        JobMessage job = parse(message.getBody());
        if (job.model() != null) {
            send(handle(handler, job.model()));
        } else if (job.failure() != null) {
            log.error("Unreadable job message in {} for service task {}: {}", queueName, job.failure().getServiceTaskId(), job.error().getMessage());
            send(job.failure());
        } else {
            log.error("Unreadable job message in {} without a service task id, moving it to {}: {}", queueName, deadLetterQueue(queueName), job.error().getMessage());
            new RepublishMessageRecoverer(rabbitTemplate, "", deadLetterQueue(queueName)).recover(message, job.error());
        }
    }

    /** A job message: the model, or the reason it cannot be read and the FAILURE for the engine when there is a service task id. */
    record JobMessage(JobDetailModel model, ServiceTaskCompleteData failure, Exception error) {
    }

    static JobMessage parse(byte[] body) {
        try {
            JobDetailModel model = MAPPER.readValue(body, JobDetailModel.class);
            if (model.getServiceTaskId() == null) {
                return new JobMessage(null, null, new IllegalArgumentException("Job message without a service task id"));
            }
            return new JobMessage(model, null, null);
        } catch (Exception e) {
            UUID serviceTaskId = serviceTaskId(body);
            if (serviceTaskId == null) {
                return new JobMessage(null, null, e);
            }
            // The engine will send the same body again: retries would only delay the incident.
            ErrorReport report = ErrorReport.of(e, INVALID_JOB_MESSAGE);
            ServiceTaskCompleteData failure = new ServiceTaskCompleteData();
            failure.setServiceTaskId(serviceTaskId);
            failure.setStatus(ServiceTaskResultStatus.FAILURE);
            failure.setErrorCode(report.getErrorCode());
            failure.setMessage(report.getMessage());
            failure.setDetails(report.getDetails());
            failure.setRetries(0);
            failure.setVariables(List.of());
            return new JobMessage(null, failure, e);
        }
    }

    /** The top-level service task id of an unreadable job, if it is there and a valid UUID. */
    private static UUID serviceTaskId(byte[] body) {
        try {
            JsonNode id = MAPPER.readTree(body).get("serviceTaskId");
            return id != null && id.isString() ? UUID.fromString(id.asString()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void send(ServiceTaskCompleteData completeData) {
        rabbitTemplate.send("", COMPLETE_SERVICE_TASK_QUEUE, RESULT_CONVERTER.toMessage(completeData, new MessageProperties()));
    }

    private void declareIfMissing(String queueName) {
        if (amqpAdmin.getQueueInfo(queueName) == null) {
            amqpAdmin.declareQueue(new Queue(queueName, true));
            log.info("Queue {} created", queueName);
        }
    }

    private static String deadLetterQueue(String queueName) {
        return queueName + ".dlq";
    }

    /**
     * Runs the handler and builds the message for the engine: SUCCESS with the handler's
     * variables, BPMN_ERROR with the code, text and variables of a {@link BpmnError}, or FAILURE with
     * the error code, text and stack trace when the handler throws anything else, plus the retry
     * override of a {@link JobFailedException}.
     */
    static ServiceTaskCompleteData handle(JobHandler handler, JobDetailModel model) {
        ServiceTaskCompleteData completeData = new ServiceTaskCompleteData();
        completeData.setServiceTaskId(model.getServiceTaskId());
        try {
            completeData.setStatus(ServiceTaskResultStatus.SUCCESS);
            completeData.setVariables(toVariables(handler.handleJob(model)));
        } catch (BpmnError e) {
            // An expected business outcome, not a failure: no stack trace, no retries.
            log.info("Job {} threw BPMN error {} for service task {}: {}", handler.getJob(), e.getErrorCode(), model.getServiceTaskId(), e.getMessage());
            completeData.setStatus(ServiceTaskResultStatus.BPMN_ERROR);
            completeData.setErrorCode(e.getErrorCode());
            completeData.setMessage(e.getMessage());
            completeData.setVariables(toVariables(e.getVariables()));
        } catch (Exception e) {
            log.error("Job {} failed for service task {}", handler.getJob(), model.getServiceTaskId(), e);
            JobFailedException failed = e instanceof JobFailedException jobFailed ? jobFailed : null;
            ErrorReport report = ErrorReport.of(e, failed != null ? failed.getErrorCode() : null);
            completeData.setStatus(ServiceTaskResultStatus.FAILURE);
            completeData.setMessage(report.getMessage());
            completeData.setErrorCode(report.getErrorCode());
            completeData.setDetails(report.getDetails());
            if (failed != null) {
                completeData.setRetries(failed.getRetries());
                completeData.setRetryTimeout(failed.getRetryTimeout() != null ? failed.getRetryTimeout().toString() : null);
            }
            completeData.setVariables(List.of());
        }
        return completeData;
    }

    private static List<ProcessVariable> toVariables(List<ProcessVariable> variables) {
        return variables.stream().map(x -> {
            ProcessVariable v = new ProcessVariable();
            v.setName(x.getName());
            v.setValue(x.getValue());
            v.setType(x.getType().toString());
            return v;
        }).toList();
    }
}
