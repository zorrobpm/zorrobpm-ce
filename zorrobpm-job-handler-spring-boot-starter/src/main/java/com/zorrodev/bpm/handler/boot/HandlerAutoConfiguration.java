package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.contract.job.JobDetailModel;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskData;
import com.zorrodev.bpm.handler.JobHandler;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class HandlerAutoConfiguration {

    private final ApplicationContext applicationContext;
    private final SimpleRabbitListenerContainerFactory connectionFactory;
    private final RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void init() {
        Map<String, JobHandler> handlersMap = applicationContext.getBeansOfType(JobHandler.class);
        log.info("Found {} handlers", handlersMap.size());
        for (Map.Entry<String, JobHandler> entry : handlersMap.entrySet()) {
            JobHandler handler = entry.getValue();
            connectionFactory.setMessageConverter(new JacksonJsonMessageConverter());
            SimpleMessageListenerContainer container = connectionFactory.createListenerContainer();
            String queueName = "zorrobpm.jobs." + handler.getJob();
            container.setQueueNames(queueName);
            log.info("Subscribing to {}", queueName);
            container.setMessageListener(message -> {
                ObjectMapper mapper = new ObjectMapper();
                ServiceTaskData data = mapper.readValue(message.getBody(), ServiceTaskData.class);
                JobDetailModel model = new JobDetailModel();
                model.setServiceTaskId(data.getServiceTaskId());
                model.setProcessDefinitionId(data.getProcessDefinitionId());
                model.setProcessInstanceId(data.getProcessInstanceId());
                Map<String, ProcessVariable> vars = data.getVariables().stream().collect(Collectors.toMap(com.zorrodev.bpm.exchange.ProcessVariable::getName, p -> {
                    ProcessVariable var = new ProcessVariable();
                    var.setName(p.getName());
                    var.setValue(p.getValue());
                    var.setType(ProcessVariableType.valueOf(p.getType()));
                    return var;
                }));
                model.setJob(data.getJob());
                model.setVariables(vars);

                List<com.zorrodev.bpm.exchange.ProcessVariable> result = handler.handleJob(model).stream().map(x -> {
                    var v = new com.zorrodev.bpm.exchange.ProcessVariable();
                    v.setName(x.getName());
                    v.setValue(x.getValue());
                    v.setType(x.getType().toString());
                    return v;
                }).toList();

                ServiceTaskCompleteData completeData = new ServiceTaskCompleteData();
                completeData.setServiceTaskId(data.getServiceTaskId());
                completeData.setStatus("SUCCESS");
                completeData.setVariables(result);
                rabbitTemplate.setMessageConverter(new JacksonJsonMessageConverter());
                rabbitTemplate.convertAndSend("zorrobpm.complete-service-task", completeData);
            });
            container.start();
        }

    }
}
