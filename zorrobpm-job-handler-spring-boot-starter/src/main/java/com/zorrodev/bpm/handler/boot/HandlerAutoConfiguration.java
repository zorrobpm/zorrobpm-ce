package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.handler.JobHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class HandlerAutoConfiguration {

    private final ApplicationContext applicationContext;
    private final SimpleRabbitListenerContainerFactory connectionFactory;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;

    @PostConstruct
    public void init() {
        Map<String, JobHandler> handlersMap = applicationContext.getBeansOfType(JobHandler.class);
        log.info("Found {} handlers", handlersMap.size());

        for (Map.Entry<String, JobHandler> entry : handlersMap.entrySet()) {
            JobHandler handler = entry.getValue();
            connectionFactory.setMessageConverter(new JacksonJsonMessageConverter());
            SimpleMessageListenerContainer container = connectionFactory.createListenerContainer();
            String queueName = "zorrobpm.jobs." + handler.getJob();
            if (amqpAdmin.getQueueInfo(queueName) == null) {
                Queue queue = new Queue(queueName, true);
                amqpAdmin.declareQueue(queue);
                log.info("Queue {} created", queueName);
            }
            container.setQueueNames(queueName);
            log.info("Subscribing to {}", queueName);
            container.setMessageListener(message -> {
                ObjectMapper mapper = new ObjectMapper();
                JobDetailModel model = mapper.readValue(message.getBody(), JobDetailModel.class);

                List<ProcessVariable> result = handler.handleJob(model).stream().map(x -> {
                    ProcessVariable v = new ProcessVariable();
                    v.setName(x.getName());
                    v.setValue(x.getValue());
                    v.setType(x.getType().toString());
                    return v;
                }).toList();

                ServiceTaskCompleteData completeData = new ServiceTaskCompleteData();
                completeData.setServiceTaskId(model.getServiceTaskId());
                completeData.setStatus("SUCCESS");
                completeData.setVariables(result);
                rabbitTemplate.setMessageConverter(new JacksonJsonMessageConverter());
                rabbitTemplate.convertAndSend("zorrobpm.complete-service-task", completeData);
            });
            container.start();
        }

    }
}
