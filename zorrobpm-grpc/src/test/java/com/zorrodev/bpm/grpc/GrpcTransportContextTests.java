package com.zorrodev.bpm.grpc;

import com.zorrodev.bpm.rabbitmq.ServiceTaskListener;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine on {@code zorrobpm.transport=grpc} with the RabbitMQ transport and Spring AMQP on the
 * classpath, as in the application, and no broker: it starts, and nothing of RabbitMQ is created.
 */
@SpringBootTest(classes = GrpcTestApplication.class)
class GrpcTransportContextTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void grpcTransportStartsWithoutRabbitMq() {
        assertThat(context.getBeansOfType(JobGrpcService.class)).hasSize(1);
        assertThat(context.getBeansOfType(GrpcJobDispatcher.class)).hasSize(1);

        assertThat(context.getBeansOfType(ServiceTaskListener.class)).isEmpty();
        assertThat(context.containsBean("completeServiceTaskContainerFactory")).isFalse();
        assertThat(context.containsBean("completeServiceTaskDeadLetterQueue")).isFalse();
        assertThat(context.getBeansOfType(ConnectionFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(RabbitTemplate.class)).isEmpty();
        assertThat(context.getBeansOfType(AmqpAdmin.class)).isEmpty();
    }
}
