package com.zorrodev.bpm.engine.configuration;

import org.camunda.feel.api.FeelEngineApi;
import org.camunda.feel.api.FeelEngineBuilder;
import org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.script.ScriptEngine;

@Configuration
public class ScriptEngineConfiguration {

    @Bean
    public ScriptEngine feelScriptEngine() {
        return new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
    }

    @Bean
    public FeelEngineApi feelEngineApi() {
        return FeelEngineBuilder.forJava().build();
    }
}
