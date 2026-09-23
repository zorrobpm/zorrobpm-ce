package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskBpmnErrorThrown;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskCompleted;
import com.zorrodev.bpm.exchange.ServiceTaskFailed;
import com.zorrodev.bpm.exchange.ServiceTaskResultStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ServiceTaskListenerTest {

    @Mock private AmqpAdmin amqpAdmin;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private ApplicationEventPublisher publisher;

    @InjectMocks
    private ServiceTaskListener listener;

    @Test
    void successPublishesCompleted() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.SUCCESS);

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
    void resultWithoutServiceTaskIdIsRejected() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.SUCCESS);
        data.setServiceTaskId(null);

        assertThatThrownBy(() -> listener.on(data)).isInstanceOf(InvalidServiceTaskResultException.class);

        verifyNoInteractions(publisher);
    }

    @Test
    void failurePublishesFailedWithCodeAndDetails() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.FAILURE);
        data.setMessage("boom");
        data.setErrorCode("java.lang.IllegalStateException");
        data.setDetails("java.lang.IllegalStateException: boom\n\tat Charge.run");

        listener.on(data);

        ServiceTaskFailed event = captured(ServiceTaskFailed.class);
        assertThat(event.getServiceTaskId()).isEqualTo(data.getServiceTaskId());
        assertThat(event.getMessage()).isEqualTo("boom");
        assertThat(event.getErrorCode()).isEqualTo("java.lang.IllegalStateException");
        assertThat(event.getDetails()).isEqualTo(data.getDetails());
    }

    @Test
    void failureFromOldWorkerHasNoCodeOrDetails() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.FAILURE);
        data.setMessage("java.lang.IllegalStateException: boom");

        listener.on(data);

        ServiceTaskFailed event = captured(ServiceTaskFailed.class);
        assertThat(event.getMessage()).isEqualTo("java.lang.IllegalStateException: boom");
        assertThat(event.getErrorCode()).isNull();
        assertThat(event.getDetails()).isNull();
    }

    @Test
    void unsupportedStatusPublishesFailedNotCompleted() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.UNSUPPORTED);

        listener.on(data);

        ServiceTaskFailed event = captured(ServiceTaskFailed.class);
        assertThat(event.getServiceTaskId()).isEqualTo(data.getServiceTaskId());
        assertThat(event.getErrorCode()).isEqualTo("UNSUPPORTED_RESULT_STATUS");
        assertThat(event.getMessage()).isNotBlank();
        assertThat(event.getRetries()).isZero();
    }

    @Test
    void failurePassesRetryOverride() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.FAILURE);
        data.setMessage("gateway down");
        data.setRetries(2);
        data.setRetryTimeout("PT10M");

        listener.on(data);

        ServiceTaskFailed event = captured(ServiceTaskFailed.class);
        assertThat(event.getRetries()).isEqualTo(2);
        assertThat(event.getRetryTimeout()).isEqualTo("PT10M");
    }

    @Test
    void failureWithoutOverrideLeavesItEmpty() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.FAILURE);
        data.setMessage("boom");

        listener.on(data);

        ServiceTaskFailed event = captured(ServiceTaskFailed.class);
        assertThat(event.getRetries()).isNull();
        assertThat(event.getRetryTimeout()).isNull();
    }

    @Test
    void negativeRetriesMeanNoRetry() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.FAILURE);
        data.setMessage("boom");
        data.setRetries(-3);

        listener.on(data);

        assertThat(captured(ServiceTaskFailed.class).getRetries()).isZero();
    }

    @Test
    void invalidRetryTimeoutFallsBackToBpmn() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.FAILURE);
        data.setMessage("boom");
        data.setRetryTimeout("soon");
        ServiceTaskCompleteData negative = data(ServiceTaskResultStatus.FAILURE);
        negative.setMessage("boom");
        negative.setRetryTimeout("-PT1M");

        listener.on(data);
        assertThat(captured(ServiceTaskFailed.class).getRetryTimeout()).isNull();

        org.mockito.Mockito.reset(publisher);
        listener.on(negative);
        assertThat(captured(ServiceTaskFailed.class).getRetryTimeout()).isNull();
    }

    @Test
    void bpmnErrorPublishesThrownWithVariables() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.BPMN_ERROR);
        data.setErrorCode("CUSTOMER_NOT_FOUND");
        data.setMessage("no customer 42");

        listener.on(data);

        ServiceTaskBpmnErrorThrown event = captured(ServiceTaskBpmnErrorThrown.class);
        assertThat(event.getServiceTaskId()).isEqualTo(data.getServiceTaskId());
        assertThat(event.getErrorCode()).isEqualTo("CUSTOMER_NOT_FOUND");
        assertThat(event.getMessage()).isEqualTo("no customer 42");
        assertThat(event.getVariables()).isSameAs(data.getVariables());
    }

    @Test
    void bpmnErrorWithoutCodeIsFailureWithoutRetries() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.BPMN_ERROR);
        data.setErrorCode(" ");
        data.setMessage("no customer 42");

        listener.on(data);

        ServiceTaskFailed event = captured(ServiceTaskFailed.class);
        assertThat(event.getServiceTaskId()).isEqualTo(data.getServiceTaskId());
        assertThat(event.getErrorCode()).isEqualTo("INVALID_BPMN_ERROR");
        assertThat(event.getMessage()).isNotBlank();
        assertThat(event.getDetails()).isEqualTo("no customer 42");
        assertThat(event.getRetries()).isZero();
    }

    private <T> T captured(Class<T> type) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(type);
        return type.cast(captor.getValue());
    }

    private static ServiceTaskCompleteData data(ServiceTaskResultStatus status) {
        ServiceTaskCompleteData data = new ServiceTaskCompleteData();
        data.setServiceTaskId(UUID.randomUUID());
        data.setStatus(status);
        data.setVariables(List.of(new ProcessVariable()));
        return data;
    }
}
