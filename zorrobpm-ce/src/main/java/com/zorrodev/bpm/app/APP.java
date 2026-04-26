package com.zorrodev.bpm.app;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "com.zorrodev.bpm.app",
    "com.zorrodev.bpm.engine",
    "com.zorrodev.bpm.admin",
    "com.zorrodev.bpm.rest",
    "com.zorrodev.bpm.rabbitmq",
    "com.zorrodev.bpm.mcp"
})
public class APP implements CommandLineRunner {

    public static void main(String[] args) {
        SpringApplication.run(APP.class, args);
    }

    @Override
    public void run(String... args) {

    }

}
