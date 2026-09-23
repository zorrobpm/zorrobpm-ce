package com.zorrodev.bpm.handler.boot;

/**
 * How the handlers of the application get their jobs: {@code rabbitmq} (the default) - from the job
 * queues of RabbitMQ, {@code grpc} - over gRPC straight from the engine. It must match the transport of
 * the engine ({@code zorrobpm.transport}).
 */
public final class HandlerTransport {

    public static final String PROPERTY = "zorrobpm.handler.transport";

    private HandlerTransport() {
    }
}
