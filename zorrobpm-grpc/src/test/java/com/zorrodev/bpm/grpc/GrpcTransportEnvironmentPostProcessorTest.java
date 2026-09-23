package com.zorrodev.bpm.grpc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcTransportEnvironmentPostProcessorTest {

    private final GrpcTransportEnvironmentPostProcessor processor = new GrpcTransportEnvironmentPostProcessor();

    @Test
    void grpcTurnsTheServerOnAndExcludesRabbitMq() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("zorrobpm.transport", "grpc")
            .withProperty("zorrobpm.grpc.port", "9191")
            .withProperty("spring.autoconfigure.exclude", "com.example.Other");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.grpc.server.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("spring.grpc.server.port")).isEqualTo("9191");
        assertThat(environment.getProperty("spring.autoconfigure.exclude"))
            .contains("com.example.Other")
            .contains(GrpcTransportEnvironmentPostProcessor.RABBIT_AUTO_CONFIGURATIONS);
    }

    @Test
    void grpcPortDefaultsTo9090AndAnExplicitServerPortWins() {
        MockEnvironment defaults = new MockEnvironment().withProperty("zorrobpm.transport", "grpc");
        processor.postProcessEnvironment(defaults, new SpringApplication());
        assertThat(defaults.getProperty("spring.grpc.server.port")).isEqualTo("9090");

        MockEnvironment explicit = new MockEnvironment()
            .withProperty("zorrobpm.transport", "grpc")
            .withProperty("spring.grpc.server.port", "0");
        processor.postProcessEnvironment(explicit, new SpringApplication());
        assertThat(explicit.getProperty("spring.grpc.server.port")).isEqualTo("0");
    }

    @Test
    void rabbitmqByDefaultTurnsTheServerOffAndKeepsRabbitMq() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.grpc.server.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.autoconfigure.exclude")).isNull();
    }

    @Test
    void unknownTransportIsLeftToTheEngine() {
        MockEnvironment environment = new MockEnvironment().withProperty("zorrobpm.transport", "kafka");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.grpc.server.enabled")).isNull();
    }
}
