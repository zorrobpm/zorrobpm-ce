package com.zorrodev.bpm.rabbitmq;

import com.zorrodev.bpm.event.data.Variable;
import com.zorrodev.bpm.event.inner.ServiceTaskCompleted;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.exchange.ServiceTaskCompleteData;
import com.zorrodev.bpm.exchange.ServiceTaskData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceTaskListener {

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final ApplicationEventPublisher publisher;

    @EventListener
    public void on(com.zorrodev.bpm.event.inner.ServiceTaskEnqueued event) {
        UUID pid = event.getProcessInstanceId();

        ServiceTaskData data = new ServiceTaskData();
        data.setServiceTaskId(event.getServiceTaskId());
        data.setProcessDefinitionId(event.getProcessDefinitionId());
        data.setProcessInstanceId(event.getProcessInstanceId());
        data.setJob(event.getJob());

        List<ProcessVariable> variables = Optional.ofNullable(event.getVariables()).orElse(List.of()).stream()
            .map(pv -> {
                ProcessVariable var = new ProcessVariable();
                var.setName(pv.getName());
                var.setType(pv.getType());
                var.setValue(pv.getValue());
                return var;
            })
            .toList();
        data.setVariables(variables);

        String queueName = "zorrobpm.jobs."+event.getJob();
        if (amqpAdmin.getQueueInfo(queueName) == null) {
            Queue queue = new Queue(queueName, true);
            amqpAdmin.declareQueue(queue);
            log.info("Queue {} created", queueName);
        }

        log.info("Data {}", data);
        rabbitTemplate.convertAndSend(queueName, data);
        log.info("Sent data for job {} to {}: {}", event.getJob(), queueName, event.getVariables());
    }

    @RabbitListener(queuesToDeclare = @org.springframework.amqp.rabbit.annotation.Queue("zorrobpm.complete-service-task"))
    public void on(ServiceTaskCompleteData data) {
        log.info("Service task to complete message received - {}", data.getServiceTaskId());
        ServiceTaskCompleted serviceTaskCompleted = new ServiceTaskCompleted();
        serviceTaskCompleted.setServiceTaskId(data.getServiceTaskId());
        List<Variable> variables = data.getVariables().stream()
            .map(pv -> {
                Variable v = new Variable();
                v.setName(v.getName());
                v.setType(v.getType());
                v.setValue(v.getValue());
                return v;
            })
            .toList();
        serviceTaskCompleted.setVariables(variables);
        publisher.publishEvent(serviceTaskCompleted);
    }
}
