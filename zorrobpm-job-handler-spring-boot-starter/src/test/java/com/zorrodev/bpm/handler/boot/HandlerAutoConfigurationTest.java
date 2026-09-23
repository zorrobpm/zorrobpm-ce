package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskResultStatus;
import com.zorrodev.bpm.handler.BpmnError;
import com.zorrodev.bpm.handler.JobFailedException;
import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HandlerAutoConfigurationTest {

    @Test
    void handle_successReturnsVariables() {
        JobDetailModel model = model();
        ProcessVariable variable = new ProcessVariable();
        variable.setName("approved");
        variable.setValue("true");
        variable.setType("BOOLEAN");

        ServiceTaskCompleteData data = HandlerAutoConfiguration.handle(handler(m -> List.of(variable)), model);

        assertThat(data.getServiceTaskId()).isEqualTo(model.getServiceTaskId());
        assertThat(data.getStatus()).isEqualTo(ServiceTaskResultStatus.SUCCESS);
        assertThat(data.getMessage()).isNull();
        assertThat(data.getErrorCode()).isNull();
        assertThat(data.getVariables()).singleElement().satisfies(v -> {
            assertThat(v.getName()).isEqualTo("approved");
            assertThat(v.getValue()).isEqualTo("true");
            assertThat(v.getType()).isEqualTo("BOOLEAN");
        });
    }

    @Test
    void handle_exceptionReturnsFailureWithMessage() {
        JobDetailModel model = model();

        ServiceTaskCompleteData data = HandlerAutoConfiguration.handle(handler(m -> {
            throw new IllegalArgumentException("card declined");
        }), model);

        assertThat(data.getServiceTaskId()).isEqualTo(model.getServiceTaskId());
        assertThat(data.getStatus()).isEqualTo(ServiceTaskResultStatus.FAILURE);
        assertThat(data.getMessage()).isEqualTo("card declined");
        assertThat(data.getErrorCode()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(data.getDetails()).startsWith("java.lang.IllegalArgumentException: card declined").contains("\tat ");
        assertThat(data.getVariables()).isEmpty();
    }

    @Test
    void handle_exceptionWithoutTextUsesClassName() {
        ServiceTaskCompleteData data = HandlerAutoConfiguration.handle(handler(m -> {
            throw new NullPointerException();
        }), model());

        assertThat(data.getMessage()).isEqualTo("java.lang.NullPointerException");
        assertThat(data.getErrorCode()).isEqualTo("java.lang.NullPointerException");
    }

    @Test
    void handle_jobFailedExceptionCarriesItsCode() {
        ServiceTaskCompleteData data = HandlerAutoConfiguration.handle(handler(m -> {
            throw new JobFailedException("CARD_DECLINED", "card declined", new IllegalStateException("gateway said 402"));
        }), model());

        assertThat(data.getStatus()).isEqualTo(ServiceTaskResultStatus.FAILURE);
        assertThat(data.getErrorCode()).isEqualTo("CARD_DECLINED");
        assertThat(data.getMessage()).isEqualTo("card declined");
        assertThat(data.getDetails()).contains("Caused by: java.lang.IllegalStateException: gateway said 402");
    }

    @Test
    void handle_jobFailedExceptionCarriesRetryOverride() {
        ServiceTaskCompleteData data = HandlerAutoConfiguration.handle(handler(m -> {
            throw new JobFailedException("GATEWAY_DOWN", "gateway down").withRetries(0).withRetryTimeout(Duration.ofMinutes(10));
        }), model());

        assertThat(data.getErrorCode()).isEqualTo("GATEWAY_DOWN");
        assertThat(data.getRetries()).isZero();
        assertThat(data.getRetryTimeout()).isEqualTo("PT10M");
    }

    @Test
    void handle_plainExceptionLeavesRetriesToEngine() {
        ServiceTaskCompleteData data = HandlerAutoConfiguration.handle(handler(m -> {
            throw new IllegalStateException("timeout");
        }), model());

        assertThat(data.getRetries()).isNull();
        assertThat(data.getRetryTimeout()).isNull();
    }

    @Test
    void handle_bpmnErrorReturnsBpmnErrorWithVariables() {
        ProcessVariable variable = new ProcessVariable();
        variable.setName("customerId");
        variable.setValue("42");
        variable.setType("LONG");

        ServiceTaskCompleteData data = HandlerAutoConfiguration.handle(handler(m -> {
            throw new BpmnError("CUSTOMER_NOT_FOUND", "no customer 42", List.of(variable));
        }), model());

        assertThat(data.getStatus()).isEqualTo(ServiceTaskResultStatus.BPMN_ERROR);
        assertThat(data.getErrorCode()).isEqualTo("CUSTOMER_NOT_FOUND");
        assertThat(data.getMessage()).isEqualTo("no customer 42");
        assertThat(data.getDetails()).isNull();
        assertThat(data.getRetries()).isNull();
        assertThat(data.getVariables()).singleElement().satisfies(v -> {
            assertThat(v.getName()).isEqualTo("customerId");
            assertThat(v.getValue()).isEqualTo("42");
            assertThat(v.getType()).isEqualTo("LONG");
        });
    }

    @Test
    void handle_bpmnErrorWithoutVariables() {
        ServiceTaskCompleteData data = HandlerAutoConfiguration.handle(handler(m -> {
            throw new BpmnError("CUSTOMER_NOT_FOUND");
        }), model());

        assertThat(data.getStatus()).isEqualTo(ServiceTaskResultStatus.BPMN_ERROR);
        assertThat(data.getMessage()).isNull();
        assertThat(data.getVariables()).isEmpty();
    }

    @Test
    void bpmnErrorRequiresCode() {
        assertThatThrownBy(() -> new BpmnError(" ", "no code")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BpmnError(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- job messages

    @Test
    void parse_readableJobGivesModel() {
        UUID id = UUID.randomUUID();

        HandlerAutoConfiguration.JobMessage job = HandlerAutoConfiguration.parse(bytes("{\"serviceTaskId\":\"" + id + "\",\"job\":\"charge\",\"variables\":{}}"));

        assertThat(job.model().getServiceTaskId()).isEqualTo(id);
        assertThat(job.failure()).isNull();
    }

    @Test
    void parse_unreadableJobWithServiceTaskIdGivesFailureWithoutRetries() {
        UUID id = UUID.randomUUID();

        HandlerAutoConfiguration.JobMessage job = HandlerAutoConfiguration.parse(bytes("{\"serviceTaskId\":\"" + id + "\",\"variables\":5}"));

        assertThat(job.model()).isNull();
        ServiceTaskCompleteData failure = job.failure();
        assertThat(failure.getServiceTaskId()).isEqualTo(id);
        assertThat(failure.getStatus()).isEqualTo(ServiceTaskResultStatus.FAILURE);
        assertThat(failure.getErrorCode()).isEqualTo("INVALID_JOB_MESSAGE");
        assertThat(failure.getRetries()).isZero();
        assertThat(failure.getMessage()).isNotBlank();
        assertThat(failure.getDetails()).isNotBlank();
        assertThat(failure.getVariables()).isEmpty();
    }

    @Test
    void parse_unreadableJobWithoutServiceTaskIdGoesToDeadLetterQueue() {
        for (String body : List.of("not a json", "{\"serviceTaskId\":\"x\"}", "{\"job\":\"charge\"}", "5")) {
            HandlerAutoConfiguration.JobMessage job = HandlerAutoConfiguration.parse(bytes(body));

            assertThat(job.model()).as(body).isNull();
            assertThat(job.failure()).as(body).isNull();
            assertThat(job.error()).as(body).isNotNull();
        }
    }

    @Test
    void onMessage_sendsResultWithoutTouchingTheApplicationTemplate() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        UUID id = UUID.randomUUID();

        configuration(template).onMessage(handler(m -> List.of()), JOBS, message("{\"serviceTaskId\":\"" + id + "\"}"));

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(""), eq("zorrobpm.complete-service-task"), sent.capture());
        assertThat(new String(sent.getValue().getBody(), StandardCharsets.UTF_8)).contains(id.toString()).contains("SUCCESS");
        verify(template, never()).setMessageConverter(any());
    }

    @Test
    void onMessage_failedSendLeavesTheJobUnacknowledged() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused"))).when(template).send(anyString(), anyString(), any(Message.class));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> configuration(template).onMessage(handler(m -> {
            calls.incrementAndGet();
            return List.of();
        }), JOBS, message("{\"serviceTaskId\":\"" + UUID.randomUUID() + "\"}"))).isInstanceOf(AmqpConnectException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void onMessage_unreadableJobWithServiceTaskIdSendsFailure() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        AtomicInteger calls = new AtomicInteger();

        configuration(template).onMessage(handler(m -> {
            calls.incrementAndGet();
            return List.of();
        }), JOBS, message("{\"serviceTaskId\":\"" + UUID.randomUUID() + "\",\"variables\":5}"));

        assertThat(calls).hasValue(0);
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(""), eq("zorrobpm.complete-service-task"), sent.capture());
        assertThat(new String(sent.getValue().getBody(), StandardCharsets.UTF_8)).contains("INVALID_JOB_MESSAGE");
    }

    @Test
    void onMessage_unreadableJobWithoutServiceTaskIdGoesToDeadLetterQueue() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        AtomicInteger calls = new AtomicInteger();

        configuration(template).onMessage(handler(m -> {
            calls.incrementAndGet();
            return List.of();
        }), JOBS, message("not a json"));

        assertThat(calls).hasValue(0);
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(""), eq(JOBS + ".dlq"), sent.capture());
        assertThat(new String(sent.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo("not a json");
        assertThat((String) sent.getValue().getMessageProperties().getHeader(RepublishMessageRecoverer.X_EXCEPTION_MESSAGE)).isNotBlank();
        assertThat((String) sent.getValue().getMessageProperties().getHeader(RepublishMessageRecoverer.X_ORIGINAL_ROUTING_KEY)).isEqualTo(JOBS);
        verify(template, never()).send(anyString(), eq("zorrobpm.complete-service-task"), any(Message.class));
    }

    private static final String JOBS = "zorrobpm.jobs.charge";

    private static HandlerAutoConfiguration configuration(RabbitTemplate template) {
        return new HandlerAutoConfiguration(mock(ApplicationContext.class), mock(SimpleRabbitListenerContainerFactory.class), template, mock(AmqpAdmin.class));
    }

    private static Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedExchange("");
        properties.setReceivedRoutingKey(JOBS);
        return new Message(bytes(body), properties);
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static JobDetailModel model() {
        JobDetailModel model = new JobDetailModel();
        model.setServiceTaskId(UUID.randomUUID());
        model.setJob("charge");
        return model;
    }

    private static JobHandler handler(Function<JobDetailModel, List<ProcessVariable>> body) {
        return new JobHandler() {
            @Override
            public String getJob() {
                return "charge";
            }

            @Override
            public List<ProcessVariable> handleJob(JobDetailModel model) {
                return body.apply(model);
            }
        };
    }
}
