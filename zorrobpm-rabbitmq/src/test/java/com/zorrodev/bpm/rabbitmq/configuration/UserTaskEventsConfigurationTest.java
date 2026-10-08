package com.zorrodev.bpm.rabbitmq.configuration;

import com.zorrodev.bpm.exchange.UserTaskEventPublisher;
import com.zorrodev.bpm.exchange.UserTaskEvents;
import com.zorrodev.bpm.rabbitmq.ServiceTaskListener;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** The publisher of user task events follows its flag, not the transport. No broker is needed: nothing connects. */
class UserTaskEventsConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
        .withUserConfiguration(RabbitConfiguration.class, ServiceTaskListener.class, UserTaskEventsConfiguration.class)
        .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
        .withPropertyValues("spring.rabbitmq.listener.simple.auto-startup=false");

    @Test
    void absentWithoutTheFlag() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(UserTaskEventPublisher.class);
            assertNoEventBeans(context);
        });
    }

    @Test
    void presentOnRabbitmqWithoutTouchingTheApplicationBeans() {
        runner.withPropertyValues("zorrobpm.events.user-task.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(UserTaskEventPublisher.class);
            assertThat(context).hasSingleBean(ServiceTaskListener.class);
            assertThat(context).hasSingleBean(ConnectionFactory.class);
            assertThat(context).hasSingleBean(RabbitTemplate.class);
            assertThat(context.getBean(RabbitTemplate.class).getMessageConverter()).isInstanceOf(JacksonJsonMessageConverter.class);
            assertNoEventBeans(context);
        });
    }

    @Test
    void presentOnGrpc() {
        runner.withPropertyValues("zorrobpm.events.user-task.enabled=true", "zorrobpm.transport=grpc").run(context -> {
            assertThat(context).hasSingleBean(UserTaskEventPublisher.class);
            assertThat(context).doesNotHaveBean(ServiceTaskListener.class);
            assertThat(context).hasSingleBean(ConnectionFactory.class);
            assertNoEventBeans(context);
        });
    }

    /** The publisher declares the queue on its own channel: no admin of the application declares it. */
    private static void assertNoEventBeans(ApplicationContext context) {
        assertThat(context.getBeansOfType(Queue.class).values()).extracting(Queue::getName).doesNotContain(UserTaskEvents.QUEUE);
        assertThat(context.getBeansOfType(Exchange.class).values()).extracting(Exchange::getName).doesNotContain(UserTaskEvents.QUEUE);
    }
}
