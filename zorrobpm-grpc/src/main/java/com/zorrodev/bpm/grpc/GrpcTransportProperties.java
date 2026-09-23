package com.zorrodev.bpm.grpc;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Settings of the gRPC transport of service task jobs ({@code zorrobpm.grpc.*}). */
@Getter
@Setter
@ConfigurationProperties("zorrobpm.grpc")
public class GrpcTransportProperties {

    /** Port of the gRPC server. */
    private int port = 9090;

    /** How long a pushed job stays locked when the subscription does not set its own lock timeout. */
    private Duration lockTimeout = Duration.ofMinutes(5);

    /** How often the engine looks for ready jobs that no event announced. */
    private Duration pollInterval = Duration.ofSeconds(1);

    /** Jobs pushed to a subscription and still without a result, when the subscription does not set it. */
    private int maxActiveJobs = 32;
}
