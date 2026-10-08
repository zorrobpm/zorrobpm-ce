package com.zorrodev.bpm.rabbitmq.configuration;

import com.zorrodev.bpm.exchange.UserTaskEvents;
import com.zorrodev.bpm.rabbitmq.RabbitUserTaskEventPublisher;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.RabbitConnectionFactoryBean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.CachingConnectionFactoryConfigurer;
import org.springframework.boot.amqp.autoconfigure.RabbitConnectionFactoryBeanConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.time.Duration;

/**
 * Publication of the user task events, on with {@code zorrobpm.events.user-task.enabled=true} whatever
 * the transport of service task jobs. The connection factory and the template are private to the
 * publisher (publisher confirms on), not beans: the application's RabbitMQ beans stay as they are. The
 * queue is declared on the publisher's channel, not as a {@code Queue} bean, so that no other admin
 * declares it.
 */
@Configuration
@ConditionalOnProperty(name = UserTaskEvents.ENABLED_PROPERTY, havingValue = "true")
public class UserTaskEventsConfiguration implements DisposableBean {

    private CachingConnectionFactory connectionFactory;

    @Bean
    RabbitUserTaskEventPublisher rabbitUserTaskEventPublisher(
        RabbitConnectionFactoryBeanConfigurer connectionFactoryBeanConfigurer,
        CachingConnectionFactoryConfigurer connectionFactoryConfigurer,
        @Value("${" + UserTaskEvents.PREFIX + ".confirm-timeout:10s}") Duration confirmTimeout) throws Exception {
        RabbitConnectionFactoryBean factoryBean = new RabbitConnectionFactoryBean();
        connectionFactoryBeanConfigurer.configure(factoryBean);
        factoryBean.afterPropertiesSet();
        connectionFactory = new CachingConnectionFactory(factoryBean.getObject());
        connectionFactoryConfigurer.configure(connectionFactory);
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        connectionFactory.setConnectionNameStrategy(cf -> "zorrobpm-user-task-events");
        return new RabbitUserTaskEventPublisher(new RabbitTemplate(connectionFactory), confirmTimeout);
    }

    @EventListener(ApplicationReadyEvent.class)
    void declareQueue(ApplicationReadyEvent event) {
        event.getApplicationContext().getBean(RabbitUserTaskEventPublisher.class).declareQueue();
    }

    @Override
    public void destroy() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }
}
