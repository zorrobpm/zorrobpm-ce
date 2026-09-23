package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** The RabbitMQ channel exists on the rabbitmq transport only. No broker is needed: nothing connects. */
class RabbitTransportConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
        .withUserConfiguration(RabbitConfiguration.class, ServiceTaskListener.class)
        // Converts the Duration settings of the result container, as in the application.
        .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
        .withPropertyValues("spring.rabbitmq.listener.simple.auto-startup=false");

    @Test
    void activeWithoutTheProperty() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ServiceTaskListener.class);
            assertThat(context).hasBean(RabbitConfiguration.COMPLETE_SERVICE_TASK_CONTAINER_FACTORY);
            assertThat(context).hasBean("completeServiceTaskDeadLetterQueue");
        });
    }

    @Test
    void activeOnRabbitmq() {
        runner.withPropertyValues("zorrobpm.transport=rabbitmq")
            .run(context -> assertThat(context).hasSingleBean(ServiceTaskListener.class));
    }

    @Test
    void absentOnGrpc() {
        runner.withPropertyValues("zorrobpm.transport=grpc").run(context -> {
            assertThat(context).doesNotHaveBean(ServiceTaskListener.class);
            assertThat(context).doesNotHaveBean(RabbitConfiguration.COMPLETE_SERVICE_TASK_CONTAINER_FACTORY);
            assertThat(context).doesNotHaveBean("completeServiceTaskDeadLetterQueue");
        });
    }
}
