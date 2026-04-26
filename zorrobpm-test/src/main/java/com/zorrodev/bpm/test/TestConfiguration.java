package com.zorrodev.bpm.test;

import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TestConfiguration {

    @Bean
    public ServiceTaskEnqueueService serviceTaskEnqueueService() {
        return new TestServiceTaskEnqueueService();
    }

}
