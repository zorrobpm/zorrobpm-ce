package com.zorrodev.bpm.handler.boot;

import com.zorrodev.bpm.handler.JobHandler;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * The gRPC transport of the starter, active on {@code zorrobpm.handler.transport=grpc}: every
 * {@link JobHandler} gets its jobs from the gRPC server of the engine at
 * {@code zorrobpm.handler.grpc.address}. The RabbitMQ transport is off; the starter creates nothing
 * of RabbitMQ and leaves the application's own messaging settings alone.
 */
@AutoConfiguration
@ConditionalOnProperty(name = HandlerTransport.PROPERTY, havingValue = "grpc")
@EnableConfigurationProperties(GrpcHandlerProperties.class)
public class GrpcHandlerAutoConfiguration {

    static final String ADDRESS = "zorrobpm.handler.grpc.address";

    @Bean
    public GrpcJobWorker zorrobpmGrpcJobWorker(GrpcHandlerProperties properties, ObjectProvider<JobHandler> handlers,
                                               Environment environment) {
        if (properties.getAddress() == null || properties.getAddress().isBlank()) {
            throw new IllegalStateException(ADDRESS + " is required when " + HandlerTransport.PROPERTY
                + "=grpc: the address of the gRPC server of the engine, for example localhost:9090");
        }
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(properties.getAddress());
        if (!properties.isTls()) {
            builder.usePlaintext();
        }
        ManagedChannel channel = builder.build();
        List<JobHandler> jobHandlers = handlers.orderedStream().toList();
        String worker = environment.getProperty("spring.application.name", "zorrobpm-worker");
        return new GrpcJobWorker(channel, jobHandlers, properties, worker);
    }
}
