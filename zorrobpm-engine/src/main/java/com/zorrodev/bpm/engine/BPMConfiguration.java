package com.zorrodev.bpm.engine;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories
@EntityScan(basePackages = {"com.zorrodev.bpm.engine.entity"})
@ComponentScan(basePackages = {"com.zorrodev.bpm.engine"})
public class BPMConfiguration {
}
