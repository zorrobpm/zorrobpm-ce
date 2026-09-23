package com.zorrodev.bpm.grpc;

import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.JobDetailFactory;
import com.zorrodev.bpm.engine.service.ServiceTaskResultService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * The gRPC transport of service task jobs, active on {@code zorrobpm.transport=grpc}: the service the
 * gRPC server exposes and the dispatcher that pushes jobs to the subscribed workers.
 */
@Configuration
@ConditionalOnProperty(name = "zorrobpm.transport", havingValue = "grpc")
@EnableConfigurationProperties(GrpcTransportProperties.class)
public class GrpcTransportConfiguration {

    @Bean
    public GrpcJobDispatcher grpcJobDispatcher(DBService dbService, JobDetailFactory jobDetailFactory,
                                               PlatformTransactionManager transactionManager, Clock clock,
                                               GrpcTransportProperties properties) {
        return new GrpcJobDispatcher(dbService, jobDetailFactory, transactionManager, clock, properties);
    }

    @Bean
    public JobGrpcService jobGrpcService(GrpcJobDispatcher dispatcher, ServiceTaskResultService resultService,
                                         PlatformTransactionManager transactionManager) {
        return new JobGrpcService(dispatcher, resultService, transactionManager);
    }
}
