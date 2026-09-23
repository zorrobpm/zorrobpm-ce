package com.zorrodev.bpm.engine.configuration;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * How service task jobs reach the workers and how their results come back: through the queues of
 * RabbitMQ, or over gRPC straight to the engine. One installation runs exactly one of them, chosen by
 * {@code zorrobpm.transport}.
 */
public enum ZorroTransport {
    RABBITMQ,
    GRPC;

    public static final String PROPERTY = "zorrobpm.transport";

    /** The value of {@link #PROPERTY}; no value means {@link #RABBITMQ}, as before the property existed. */
    public static ZorroTransport from(String value) {
        if (value == null || value.isBlank()) {
            return RABBITMQ;
        }
        for (ZorroTransport transport : values()) {
            if (transport.value().equals(value.trim().toLowerCase(Locale.ROOT))) {
                return transport;
            }
        }
        throw new IllegalArgumentException("Invalid " + PROPERTY + " '" + value + "': expected one of "
            + Arrays.stream(values()).map(ZorroTransport::value).collect(Collectors.joining(", ")));
    }

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
