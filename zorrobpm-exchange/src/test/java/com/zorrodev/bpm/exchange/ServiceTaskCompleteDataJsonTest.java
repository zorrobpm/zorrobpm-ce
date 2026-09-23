package com.zorrodev.bpm.exchange;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire format of the worker's result: the status is an enum, yet the JSON stays the one older
 * workers and engines already speak.
 */
class ServiceTaskCompleteDataJsonTest {

    private static final String ID = "\"serviceTaskId\":\"6f1c2f64-4d0b-4b43-9a3c-6b1f0c1d2e3f\"";

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void missingStatusIsNull() {
        assertThat(read("{" + ID + "}").getStatus()).isNull();
    }

    @Test
    void knownStatuses() {
        assertThat(read("{" + ID + ",\"status\":\"SUCCESS\"}").getStatus()).isEqualTo(ServiceTaskResultStatus.SUCCESS);
        assertThat(read("{" + ID + ",\"status\":\"FAILURE\"}").getStatus()).isEqualTo(ServiceTaskResultStatus.FAILURE);
        assertThat(read("{" + ID + ",\"status\":\"BPMN_ERROR\"}").getStatus()).isEqualTo(ServiceTaskResultStatus.BPMN_ERROR);
    }

    @Test
    void unknownStatusIsUnsupported() {
        assertThat(read("{" + ID + ",\"status\":\"RETRY_LATER\"}").getStatus()).isEqualTo(ServiceTaskResultStatus.UNSUPPORTED);
    }

    @Test
    void errorFieldsAreRead() {
        ServiceTaskCompleteData data = read("{" + ID + ",\"status\":\"FAILURE\",\"message\":\"card declined\","
            + "\"errorCode\":\"CARD_DECLINED\",\"details\":\"stack\"}");

        assertThat(data.getMessage()).isEqualTo("card declined");
        assertThat(data.getErrorCode()).isEqualTo("CARD_DECLINED");
        assertThat(data.getDetails()).isEqualTo("stack");
    }

    @Test
    void retryFieldsAreRead() {
        ServiceTaskCompleteData data = read("{" + ID + ",\"status\":\"FAILURE\",\"retries\":0,\"retryTimeout\":\"PT10M\"}");

        assertThat(data.getRetries()).isZero();
        assertThat(data.getRetryTimeout()).isEqualTo("PT10M");
    }

    @Test
    void missingRetryFieldsAreNull() {
        ServiceTaskCompleteData data = read("{" + ID + ",\"status\":\"FAILURE\"}");

        assertThat(data.getRetries()).isNull();
        assertThat(data.getRetryTimeout()).isNull();
    }

    @Test
    void unknownFieldsDoNotBreakReading() {
        ServiceTaskCompleteData data = read("{" + ID + ",\"status\":\"SUCCESS\",\"attempt\":3}");

        assertThat(data.getStatus()).isEqualTo(ServiceTaskResultStatus.SUCCESS);
    }

    @Test
    void bpmnErrorFieldsAreRead() {
        ServiceTaskCompleteData data = read("{" + ID + ",\"status\":\"BPMN_ERROR\",\"errorCode\":\"CUSTOMER_NOT_FOUND\","
            + "\"message\":\"no customer 42\",\"variables\":[{\"name\":\"customerId\",\"value\":\"42\",\"type\":\"LONG\"}]}");

        assertThat(data.getStatus()).isEqualTo(ServiceTaskResultStatus.BPMN_ERROR);
        assertThat(data.getErrorCode()).isEqualTo("CUSTOMER_NOT_FOUND");
        assertThat(data.getMessage()).isEqualTo("no customer 42");
        assertThat(data.getVariables()).singleElement().extracting(ProcessVariable::getName).isEqualTo("customerId");
    }

    @Test
    void failureIsWrittenAsString() {
        ServiceTaskCompleteData data = new ServiceTaskCompleteData();
        data.setStatus(ServiceTaskResultStatus.FAILURE);

        assertThat(mapper.writeValueAsString(data)).contains("\"status\":\"FAILURE\"");
    }

    private ServiceTaskCompleteData read(String json) {
        return mapper.readValue(json, ServiceTaskCompleteData.class);
    }
}
