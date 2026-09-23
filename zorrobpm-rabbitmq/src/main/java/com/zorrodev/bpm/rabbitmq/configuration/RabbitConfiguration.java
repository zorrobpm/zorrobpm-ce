package com.zorrodev.bpm.rabbitmq.configuration;

import com.zorrodev.bpm.rabbitmq.ServiceTaskListener;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateConfigurer;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;

import java.time.Duration;

/** Active on the RabbitMQ transport only ({@code zorrobpm.transport}, {@code rabbitmq} by default). */
@Configuration
@ConditionalOnProperty(name = "zorrobpm.transport", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitConfiguration {

    public static final String COMPLETE_SERVICE_TASK_CONTAINER_FACTORY = "completeServiceTaskContainerFactory";

    @Bean
    MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(RabbitTemplateConfigurer configurer,
                                         ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate();
        configurer.configure(template, connectionFactory);
        template.setMessageConverter(new JacksonJsonMessageConverter());
        return template;
    }

    /** Declared by the admin on connection; the result queue itself keeps its old declaration. */
    @Bean
    Queue completeServiceTaskDeadLetterQueue() {
        return new Queue(ServiceTaskListener.COMPLETE_SERVICE_TASK_DLQ, true);
    }

    /**
     * The container of the result queue only: the listener settings of the application apply, the
     * retries and the dead-letter queue do not leak to other listeners.
     */
    @Bean(COMPLETE_SERVICE_TASK_CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory completeServiceTaskContainerFactory(
        SimpleRabbitListenerContainerFactoryConfigurer configurer,
        ConnectionFactory connectionFactory,
        RabbitTemplate rabbitTemplate,
        @Value("${zorrobpm.rabbitmq.complete-service-task.max-attempts:3}") int maxAttempts,
        @Value("${zorrobpm.rabbitmq.complete-service-task.initial-interval:1s}") Duration initialInterval,
        @Value("${zorrobpm.rabbitmq.complete-service-task.multiplier:2}") double multiplier,
        @Value("${zorrobpm.rabbitmq.complete-service-task.max-interval:10s}") Duration maxInterval) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain(completeServiceTaskRetry(rabbitTemplate, maxAttempts, initialInterval, multiplier, maxInterval));
        return factory;
    }

    /**
     * Retries a result whose processing failed, with a growing pause, then moves it to the
     * dead-letter queue and acknowledges it. An unreadable result or one without a service task id
     * goes there at once. If the move fails, the exception reaches the container and the message
     * stays in the queue.
     */
    public static MethodInterceptor completeServiceTaskRetry(AmqpTemplate template, int maxAttempts, Duration initialInterval,
                                                             double multiplier, Duration maxInterval) {
        RetryPolicy policy = RetryPolicy.builder()
            .maxRetries(Math.max(0, maxAttempts - 1))
            .delay(initialInterval)
            .multiplier(multiplier)
            .maxDelay(maxInterval)
            .predicate(e -> !isFatal(e))
            .build();
        return RetryInterceptorBuilder.stateless()
            .retryPolicy(policy)
            .recoverer(new RepublishMessageRecoverer(template, "", ServiceTaskListener.COMPLETE_SERVICE_TASK_DLQ))
            .build();
    }

    /** No attempt can succeed: the listener wraps the cause, so the whole chain is checked. */
    static boolean isFatal(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof MessageConversionException || t instanceof AmqpRejectAndDontRequeueException) {
                return true;
            }
        }
        return false;
    }
}
