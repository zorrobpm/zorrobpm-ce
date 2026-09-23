package com.zorrodev.bpm.exchange;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The job the engine sends to workers. Workers on the starter read it with a default
 * {@link ObjectMapper}, so fields added by a newer engine must not break an older worker.
 */
class JobDetailModelJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void retriesAreRead() {
        JobDetailModel model = mapper.readValue("{\"job\":\"charge\",\"retries\":2}", JobDetailModel.class);

        assertThat(model.getJob()).isEqualTo("charge");
        assertThat(model.getRetries()).isEqualTo(2);
    }

    @Test
    void unknownFieldsDoNotBreakReading() {
        JobDetailModel model = mapper.readValue("{\"job\":\"charge\",\"attempt\":2}", JobDetailModel.class);

        assertThat(model.getJob()).isEqualTo("charge");
    }
}
