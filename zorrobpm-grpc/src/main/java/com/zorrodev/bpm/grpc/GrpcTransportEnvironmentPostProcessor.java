package com.zorrodev.bpm.grpc;

import com.zorrodev.bpm.engine.configuration.ZorroTransport;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Switches the gRPC server and RabbitMQ by {@code zorrobpm.transport}. On {@code grpc} the server is
 * on, listens on {@code zorrobpm.grpc.port} (9090 by default) unless {@code spring.grpc.server.port}
 * is set, and the RabbitMQ auto-configuration is excluded, so that the engine neither connects to a
 * broker nor reports its health. On {@code rabbitmq} (the default) the gRPC server is off.
 * <p>
 * An unknown value is left alone here: the engine stops the start with a message that names it.
 */
public class GrpcTransportEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE = "zorrobpmGrpcTransport";
    static final String DEFAULTS_SOURCE = "zorrobpmGrpcTransportDefaults";
    static final String EXCLUDE = "spring.autoconfigure.exclude";
    static final String[] RABBIT_AUTO_CONFIGURATIONS = {
        "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "org.springframework.boot.amqp.autoconfigure.health.RabbitHealthContributorAutoConfiguration",
        "org.springframework.boot.amqp.autoconfigure.metrics.RabbitMetricsAutoConfiguration",
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ZorroTransport transport;
        try {
            transport = ZorroTransport.from(environment.getProperty(ZorroTransport.PROPERTY));
        } catch (IllegalArgumentException e) {
            return;
        }

        Map<String, Object> enforced = new HashMap<>();
        if (transport == ZorroTransport.GRPC) {
            enforced.put("spring.grpc.server.enabled", "true");
            Set<String> excludes = new LinkedHashSet<>(Arrays.asList(
                Binder.get(environment).bind(EXCLUDE, String[].class).orElse(new String[0])));
            excludes.addAll(Arrays.asList(RABBIT_AUTO_CONFIGURATIONS));
            enforced.put(EXCLUDE, String.join(",", excludes));

            Map<String, Object> defaults = new HashMap<>();
            defaults.put("spring.grpc.server.port", "${zorrobpm.grpc.port:9090}");
            environment.getPropertySources().addLast(new MapPropertySource(DEFAULTS_SOURCE, defaults));
        } else {
            enforced.put("spring.grpc.server.enabled", "false");
        }
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, enforced));
    }

    /** After the configuration files are loaded. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
