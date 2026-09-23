package com.zorrodev.bpm.engine.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZorroTransportTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TransportConfiguration.class);

    @Test
    void rabbitmqWithoutTheProperty() {
        assertThat(ZorroTransport.from(null)).isEqualTo(ZorroTransport.RABBITMQ);
        assertThat(ZorroTransport.from(" ")).isEqualTo(ZorroTransport.RABBITMQ);
        runner.run(context -> assertThat(context.getBean(ZorroTransport.class)).isEqualTo(ZorroTransport.RABBITMQ));
    }

    @Test
    void knownValues() {
        assertThat(ZorroTransport.from("rabbitmq")).isEqualTo(ZorroTransport.RABBITMQ);
        assertThat(ZorroTransport.from("GRPC")).isEqualTo(ZorroTransport.GRPC);
        runner.withPropertyValues("zorrobpm.transport=grpc")
            .run(context -> assertThat(context.getBean(ZorroTransport.class)).isEqualTo(ZorroTransport.GRPC));
    }

    @Test
    void unknownValueStopsTheStartWithThePropertyAndTheAllowedValues() {
        assertThatThrownBy(() -> ZorroTransport.from("kafka"))
            .hasMessageContaining("zorrobpm.transport")
            .hasMessageContaining("kafka")
            .hasMessageContaining("rabbitmq")
            .hasMessageContaining("grpc");
        runner.withPropertyValues("zorrobpm.transport=kafka").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("zorrobpm.transport");
        });
    }
}
