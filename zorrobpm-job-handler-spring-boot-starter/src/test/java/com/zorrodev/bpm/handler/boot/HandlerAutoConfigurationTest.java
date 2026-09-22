package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(data.getStatus()).isEqualTo("SUCCESS");
        assertThat(data.getMessage()).isNull();
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
        assertThat(data.getStatus()).isEqualTo("FAILURE");
        assertThat(data.getMessage()).isEqualTo("java.lang.IllegalArgumentException: card declined");
        assertThat(data.getVariables()).isEmpty();
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
