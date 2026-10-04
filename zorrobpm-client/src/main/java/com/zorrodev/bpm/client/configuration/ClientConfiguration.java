package com.zorrodev.bpm.client.configuration;

import com.zorrodev.bpm.client.*;
import com.zorrodev.bpm.client.resolver.ProcessDefinitionQueryParametersArgumentResolver;
import com.zorrodev.bpm.client.resolver.ProcessInstanceQueryParameterArgumentResolver;
import com.zorrodev.bpm.client.resolver.ServiceTaskQueryParametersArgumentResolver;
import com.zorrodev.bpm.client.resolver.UserTaskQueryParametersArgumentResolver;
import com.zorrodev.bpm.client.resolver.VariableQueryParametersArgumentResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * HTTP clients of the engine REST API. Every request goes to {@code app.m11s.zorrodev.bpm.url}; when
 * {@code app.m11s.zorrodev.bpm.token} is set, it is sent as {@code Authorization: Bearer} - the API
 * token an enterprise installation issues in its admin UI. Left empty, the requests carry no
 * authorization, which is what the open API of the community application expects.
 *
 * <p>The clients are built from the application's {@link RestClient.Builder} when it has one (Spring
 * Boot 4 provides it with {@code spring-boot-starter-restclient}), so whatever the application customises on it - tracing headers,
 * timeouts, observations - applies to the engine calls too. Without such a bean a plain builder is
 * used.
 */
@Configuration
@PropertySource("classpath:zorrobpm-client.properties")
public class ClientConfiguration {

    private final String baseUrl;
    private final String token;
    private final ObjectProvider<RestClient.Builder> builders;

    public ClientConfiguration(@Value("${app.m11s.zorrodev.bpm.url}") String baseUrl,
                               @Value("${app.m11s.zorrodev.bpm.token:}") String token,
                               ObjectProvider<RestClient.Builder> builders) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.builders = builders;
    }

    @Bean
    public RuntimeClient runtimeClient() {
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter())
            .build();

        return factory.createClient(RuntimeClient.class);
    }

    @Bean
    public ProcessDefinitionClient processDefinitionClient() {
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter())
                .customArgumentResolver(new ProcessDefinitionQueryParametersArgumentResolver())
                .build();

        return factory.createClient(ProcessDefinitionClient.class);
    }

    @Bean
    public QueryClient queryClient() {
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter())
            .customArgumentResolver(new ProcessDefinitionQueryParametersArgumentResolver())
            .customArgumentResolver(new ProcessInstanceQueryParameterArgumentResolver())
            .customArgumentResolver(new ServiceTaskQueryParametersArgumentResolver())
            .customArgumentResolver(new UserTaskQueryParametersArgumentResolver())
            .customArgumentResolver(new VariableQueryParametersArgumentResolver())
            .build();

        return factory.createClient(QueryClient.class);
    }

    private RestClientAdapter adapter() {
        return RestClientAdapter.create(configure(applicationBuilder(), baseUrl, token).build());
    }

    /**
     * A builder of its own for every client: the application's bean is cloned, because an application
     * may declare it as a singleton and the default headers must not pile up across the clients.
     */
    private RestClient.Builder applicationBuilder() {
        RestClient.Builder builder = builders.getIfAvailable();
        return builder != null ? builder.clone() : RestClient.builder();
    }

    /** Applies the base URL, the JSON headers and, when there is one, the bearer token. */
    static RestClient.Builder configure(RestClient.Builder builder, String baseUrl, String token) {
        builder
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .defaultHeader(HttpHeaders.ACCEPT, "application/json");
        if (StringUtils.hasText(token)) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.trim());
        }
        return builder;
    }
}
