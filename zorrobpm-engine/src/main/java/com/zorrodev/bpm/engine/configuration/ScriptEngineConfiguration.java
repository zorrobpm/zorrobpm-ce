package com.zorrodev.bpm.engine.configuration;

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
}
