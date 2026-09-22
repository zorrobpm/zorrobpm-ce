package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import com.zorrodev.bpm.exchange.ServiceTaskFailed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ServiceTaskListenerTest {

    @Mock private AmqpAdmin amqpAdmin;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private ApplicationEventPublisher publisher;

    @InjectMocks
    private ServiceTaskListener listener;

    @Test
    void successPublishesCompleted() {
        ServiceTaskCompleteData data = data("SUCCESS");

        listener.on(data);

        ServiceTaskCompleted event = captured(ServiceTaskCompleted.class);
        assertThat(event.getServiceTaskId()).isEqualTo(data.getServiceTaskId());
        assertThat(event.getVariables()).isSameAs(data.getVariables());
    }

    @Test
    void missingStatusPublishesCompleted() {
        ServiceTaskCompleteData data = data(null);

        listener.on(data);

        assertThat(captured(ServiceTaskCompleted.class).getServiceTaskId()).isEqualTo(data.getServiceTaskId());
    }

    @Test
    void failurePublishesFailed() {
        ServiceTaskCompleteData data = data("FAILURE");
        data.setMessage("java.lang.IllegalStateException: boom");

        listener.on(data);

        ServiceTaskFailed event = captured(ServiceTaskFailed.class);
        assertThat(event.getServiceTaskId()).isEqualTo(data.getServiceTaskId());
        assertThat(event.getMessage()).isEqualTo("java.lang.IllegalStateException: boom");
    }

    private <T> T captured(Class<T> type) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(type);
        return type.cast(captor.getValue());
    }

    private static ServiceTaskCompleteData data(String status) {
        ServiceTaskCompleteData data = new ServiceTaskCompleteData();
        data.setServiceTaskId(UUID.randomUUID());
        data.setStatus(status);
        data.setVariables(List.of(new ProcessVariable()));
        return data;
    }
}
