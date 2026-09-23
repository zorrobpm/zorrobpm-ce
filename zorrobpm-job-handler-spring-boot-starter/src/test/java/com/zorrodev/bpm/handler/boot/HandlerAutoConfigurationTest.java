package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskResultStatus;
import com.zorrodev.bpm.handler.BpmnError;
import com.zorrodev.bpm.handler.JobFailedException;
import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
