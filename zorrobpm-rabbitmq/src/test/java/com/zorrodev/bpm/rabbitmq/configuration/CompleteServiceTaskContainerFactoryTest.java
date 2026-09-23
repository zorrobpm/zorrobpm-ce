package com.zorrodev.bpm.rabbitmq.configuration;

import com.zorrodev.bpm.rabbitmq.InvalidServiceTaskResultException;
import com.zorrodev.bpm.rabbitmq.ServiceTaskListener;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The advice of the result queue container, run on a listener stand-in the way the container runs
 * it: the listener failure arrives wrapped in {@link ListenerExecutionFailedException}.
 */
class CompleteServiceTaskContainerFactoryTest {

    private final AmqpTemplate template = mock(AmqpTemplate.class);
    private final MethodInterceptor advice = RabbitConfiguration.completeServiceTaskRetry(template, 3, Duration.ofMillis(1), 2, Duration.ofMillis(5));
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void transientFailureIsRetried() throws Throwable {
        Message message = message("{}");

        advice.invoke(invocation(message, () -> {
            if (calls.incrementAndGet() == 1) {
                throw failed(message, new IllegalStateException("database is down"));
            }
        }));

        assertThat(calls).hasValue(2);
        verify(template, never()).send(anyString(), anyString(), any());
    }

    @Test
    void persistentFailureGoesToDeadLetterQueueAfterLastAttempt() throws Throwable {
        Message message = message("{\"serviceTaskId\":\"x\"}");

        advice.invoke(invocation(message, () -> {
            calls.incrementAndGet();
            throw failed(message, new IllegalStateException("database is down"));
        }));

        assertThat(calls).hasValue(3);
        Message dead = deadLettered();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"serviceTaskId\":\"x\"}");
        assertThat((String) dead.getMessageProperties().getHeader(RepublishMessageRecoverer.X_EXCEPTION_MESSAGE)).isEqualTo("database is down");
        assertThat((String) dead.getMessageProperties().getHeader(RepublishMessageRecoverer.X_ORIGINAL_ROUTING_KEY)).isEqualTo(ServiceTaskListener.COMPLETE_SERVICE_TASK_QUEUE);
    }

    @Test
    void unreadableResultGoesToDeadLetterQueueAtOnce() throws Throwable {
        Message message = message("not a json");

        advice.invoke(invocation(message, () -> {
            calls.incrementAndGet();
            throw failed(message, new MessageConversionException("Failed to convert message"));
        }));

        assertThat(calls).hasValue(1);
        assertThat(new String(deadLettered().getBody(), StandardCharsets.UTF_8)).isEqualTo("not a json");
    }

    @Test
    void resultWithoutServiceTaskIdGoesToDeadLetterQueueAtOnce() throws Throwable {
        Message message = message("{\"status\":\"SUCCESS\"}");

        advice.invoke(invocation(message, () -> {
            calls.incrementAndGet();
            throw failed(message, new InvalidServiceTaskResultException("Service task result without a service task id"));
        }));

        assertThat(calls).hasValue(1);
        assertThat((String) deadLettered().getMessageProperties().getHeader(RepublishMessageRecoverer.X_EXCEPTION_MESSAGE))
            .isEqualTo("Service task result without a service task id");
    }

    @Test
    void failedMoveToDeadLetterQueueReachesTheContainer() {
        Message message = message("not a json");
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
            .when(template).send(anyString(), anyString(), any(Message.class));

        assertThatThrownBy(() -> advice.invoke(invocation(message, () -> {
            throw failed(message, new MessageConversionException("Failed to convert message"));
        }))).isInstanceOf(AmqpConnectException.class);
    }

    private Message deadLettered() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(""), eq(ServiceTaskListener.COMPLETE_SERVICE_TASK_DLQ), captor.capture());
        return captor.getValue();
    }

    private static Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedExchange("");
        properties.setReceivedRoutingKey(ServiceTaskListener.COMPLETE_SERVICE_TASK_QUEUE);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    private static ListenerExecutionFailedException failed(Message message, Throwable cause) {
        return new ListenerExecutionFailedException("Listener threw exception", cause, message);
    }

    private static MethodInvocation invocation(Message message, Runnable listener) throws Throwable {
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{null, message});
        when(invocation.proceed()).thenAnswer(i -> {
            listener.run();
            return null;
        });
        return invocation;
    }
}
