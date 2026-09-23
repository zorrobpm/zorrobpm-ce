package com.zorrodev.bpm.grpc;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The engine with both transports, as in the application; the tests run it on grpc without a broker. */
@SpringBootApplication(scanBasePackages = {
    "com.zorrodev.bpm.engine",
    "com.zorrodev.bpm.grpc",
    "com.zorrodev.bpm.rabbitmq",
})
public class GrpcTestApplication {
}
