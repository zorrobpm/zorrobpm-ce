package com.zorrodev.bpm.exchange;

/**
 * Properties of the user task lifecycle events ({@code engine-user-task-events}). The flag is read by
 * the engine (writing and relaying the outbox), the RabbitMQ module (publishing) and the gRPC transport
 * (keeping RabbitMQ on).
 */
public final class UserTaskEvents {

    public static final String PREFIX = "zorrobpm.events.user-task";
    /** {@code true} turns the events on; off when not set. */
    public static final String ENABLED_PROPERTY = PREFIX + ".enabled";
    /** {@code false} keeps the outbox written but not relayed, for tests. */
    public static final String RELAY_ENABLED_PROPERTY = PREFIX + ".relay-enabled";
    /** The durable queue the engine declares and publishes every event to, through the default exchange. */
    public static final String QUEUE = "zorrobpm.user-task-events";

    private UserTaskEvents() {
    }

    public static boolean enabled(String value) {
        return value != null && "true".equalsIgnoreCase(value.trim());
    }
}
