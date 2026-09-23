package com.zorrodev.bpm.exchange;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceTaskResultsTest {

    @Test
    void successBecomesCompleted() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.SUCCESS);
        data.setVariables(List.of(new ProcessVariable()));

        ServiceTaskCompleted event = (ServiceTaskCompleted) ServiceTaskResults.toEvent(data);

        assertThat(event.getServiceTaskId()).isEqualTo(data.getServiceTaskId());
        assertThat(event.getVariables()).hasSize(1);
    }

    @Test
    void failureKeepsItsRetryOverride() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.FAILURE);
        data.setErrorCode("IO");
        data.setRetries(2);
        data.setRetryTimeout("PT30S");

        ServiceTaskFailed event = (ServiceTaskFailed) ServiceTaskResults.toEvent(data);

        assertThat(event.getErrorCode()).isEqualTo("IO");
        assertThat(event.getRetries()).isEqualTo(2);
        assertThat(event.getRetryTimeout()).isEqualTo("PT30S");
    }

    @Test
    void negativeRetriesMeanNone() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.FAILURE);
        data.setRetries(-1);

        assertThat(((ServiceTaskFailed) ServiceTaskResults.toEvent(data)).getRetries()).isZero();
    }

    @Test
    void invalidOrNegativeRetryTimeoutIsDropped() {
        ServiceTaskCompleteData invalid = data(ServiceTaskResultStatus.FAILURE);
        invalid.setRetryTimeout("30 seconds");
        ServiceTaskCompleteData negative = data(ServiceTaskResultStatus.FAILURE);
        negative.setRetryTimeout("-PT1S");

        assertThat(((ServiceTaskFailed) ServiceTaskResults.toEvent(invalid)).getRetryTimeout()).isNull();
        assertThat(((ServiceTaskFailed) ServiceTaskResults.toEvent(negative)).getRetryTimeout()).isNull();
    }

    @Test
    void bpmnErrorWithCodeIsThrown() {
        ServiceTaskCompleteData data = data(ServiceTaskResultStatus.BPMN_ERROR);
        data.setErrorCode("CARD_DECLINED");

        ServiceTaskBpmnErrorThrown event = (ServiceTaskBpmnErrorThrown) ServiceTaskResults.toEvent(data);

        assertThat(event.getErrorCode()).isEqualTo("CARD_DECLINED");
    }

    @Test
    void bpmnErrorWithoutCodeBecomesAFailureWithoutRetries() {
        ServiceTaskFailed event = (ServiceTaskFailed) ServiceTaskResults.toEvent(data(ServiceTaskResultStatus.BPMN_ERROR));

        assertThat(event.getErrorCode()).isEqualTo(ServiceTaskResults.INVALID_BPMN_ERROR);
        assertThat(event.getRetries()).isZero();
    }

    @Test
    void unsupportedStatusBecomesAFailureWithoutRetries() {
        ServiceTaskFailed event = (ServiceTaskFailed) ServiceTaskResults.toEvent(data(ServiceTaskResultStatus.UNSUPPORTED));

        assertThat(event.getErrorCode()).isEqualTo(ServiceTaskResults.UNSUPPORTED_RESULT_STATUS);
        assertThat(event.getRetries()).isZero();
    }

    private static ServiceTaskCompleteData data(ServiceTaskResultStatus status) {
        ServiceTaskCompleteData data = new ServiceTaskCompleteData();
        data.setServiceTaskId(UUID.randomUUID());
        data.setStatus(status);
        return data;
    }
}
