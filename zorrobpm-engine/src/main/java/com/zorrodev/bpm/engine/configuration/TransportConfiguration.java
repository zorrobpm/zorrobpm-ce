package com.zorrodev.bpm.engine.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The transport of service task jobs ({@code zorrobpm.transport}: {@code rabbitmq} by default or
 * {@code grpc}). An unknown value stops the start; the transport modules switch themselves on and off
 * by the same property.
 */
@Configuration
public class TransportConfiguration {

    @Bean
    public ZorroTransport zorroTransport(@Value("${" + ZorroTransport.PROPERTY + ":}") String value) {
        return ZorroTransport.from(value);
    }
}
