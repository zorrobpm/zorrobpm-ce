package com.zorrodev.bpm.client.configuration;

import com.zorrodev.bpm.client.*;
import com.zorrodev.bpm.client.resolver.ProcessDefinitionQueryParametersArgumentResolver;
import com.zorrodev.bpm.client.resolver.ProcessInstanceQueryParameterArgumentResolver;
import com.zorrodev.bpm.client.resolver.ServiceTaskQueryParametersArgumentResolver;
import com.zorrodev.bpm.client.resolver.UserTaskQueryParametersArgumentResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
@PropertySource("classpath:zorrobpm-client.properties")
public class ClientConfiguration {

    @Value("${app.m11s.zorrodev.bpm.url}")
    private String baseUrl;

    @Bean
    public RuntimeClient runtimeClient() {
        RestClient client = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("Accept", "application/json")
            .build();
        RestClientAdapter adapter = RestClientAdapter.create(client);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter)
            .build();

        return factory.createClient(RuntimeClient.class);
    }

    @Bean
    public ProcessDefinitionClient processDefinitionClient() {
        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
        RestClientAdapter adapter = RestClientAdapter.create(client);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter)
                .customArgumentResolver(new ProcessDefinitionQueryParametersArgumentResolver())
                .build();

        return factory.createClient(ProcessDefinitionClient.class);
    }

    @Bean
    public QueryClient queryClient() {
        RestClient client = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("Accept", "application/json")
            .build();
        RestClientAdapter adapter = RestClientAdapter.create(client);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter)
            .customArgumentResolver(new ProcessDefinitionQueryParametersArgumentResolver())
            .customArgumentResolver(new ProcessInstanceQueryParameterArgumentResolver())
            .customArgumentResolver(new ServiceTaskQueryParametersArgumentResolver())
            .customArgumentResolver(new UserTaskQueryParametersArgumentResolver())
            .build();

        return factory.createClient(QueryClient.class);
    }
}
