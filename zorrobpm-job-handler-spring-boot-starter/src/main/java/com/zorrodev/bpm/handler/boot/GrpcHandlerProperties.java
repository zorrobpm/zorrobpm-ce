package com.zorrodev.bpm.handler.boot;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Settings of the gRPC transport of the handlers ({@code zorrobpm.handler.grpc.*}). */
@Getter
@Setter
@ConfigurationProperties("zorrobpm.handler.grpc")
public class GrpcHandlerProperties {

    /** Address of the gRPC server of the engine, for example {@code localhost:9090}. Required. */
    private String address;

    /** API token of the engine (enterprise edition); sent as {@code authorization: Bearer <token>}. */
    private String token;

    /** How long the engine keeps a pushed job for this worker; unset - the engine's default. */
    private Duration lockTimeout;

    /** Jobs handled at the same time; the engine pushes no more jobs without a result. */
    private int maxActiveJobs = 32;

    /** Connect with TLS instead of plaintext. */
    private boolean tls;

    /** Attempts to send a result when the engine is unavailable, the first one included. */
    private int resultAttempts = 4;

    /** Pause before the second attempt to send a result; doubles with each further attempt. */
    private Duration resultRetryInterval = Duration.ofSeconds(1);

    /** First pause before reconnecting after the stream of jobs broke; doubles up to {@link #reconnectMaxInterval}. */
    private Duration reconnectInterval = Duration.ofSeconds(1);

    private Duration reconnectMaxInterval = Duration.ofSeconds(30);
}
