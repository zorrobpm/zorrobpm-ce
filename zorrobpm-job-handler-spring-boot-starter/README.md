# zorrobpm-job-handler-spring-boot-starter

Runs the service task jobs of the ZorroBPM engine in a Spring Boot application. Every bean that
implements `JobHandler` handles the jobs of its `getJob()` type:

```java
@Component
class ChargeHandler implements JobHandler {
    public String getJob() { return "charge"; }

    public List<ProcessVariable> handleJob(JobDetailModel job) {
        // ... do the work, return the variables to set on the process
    }
}
```

The outcome goes back to the engine by the same rules on every transport:

| Handler                           | Result for the engine                                     |
|-----------------------------------|-----------------------------------------------------------|
| returns variables                 | success, the variables are set on the process             |
| throws `BpmnError`                | BPMN error for an error boundary event of the service task |
| throws `JobFailedException`       | failure with its error code and optional retry override   |
| throws any other exception        | failure with the exception class as the error code        |

## Transports

The transport must match the one of the engine (`zorrobpm.transport`).

### RabbitMQ (default)

```properties
zorrobpm.handler.transport=rabbitmq   # or leave it out
spring.rabbitmq.host=...
```

The handler of a job type listens to the queue `zorrobpm.jobs.<job>` and sends results to
`zorrobpm.complete-service-task`.

### gRPC

```properties
zorrobpm.handler.transport=grpc
zorrobpm.handler.grpc.address=engine-host:9090
# enterprise edition: an API token of a user with the ADMIN or OPERATOR role
zorrobpm.handler.grpc.token=${ZORROBPM_API_TOKEN}
```

The worker opens one stream to the engine for the job types of all its handlers and needs no broker.
On the gRPC transport the starter creates nothing of RabbitMQ; if the application does not use
RabbitMQ itself, it can drop the RabbitMQ health check with `management.health.rabbit.enabled=false`.

| Property                                     | Default                | Meaning                                                                 |
|----------------------------------------------|------------------------|-------------------------------------------------------------------------|
| `zorrobpm.handler.grpc.address`              | — (required)           | Address of the gRPC server of the engine                                |
| `zorrobpm.handler.grpc.token`                | —                      | API token, sent as `authorization: Bearer <token>`                      |
| `zorrobpm.handler.grpc.lock-timeout`         | engine's (`PT5M`)      | How long the engine keeps a pushed job for this worker                  |
| `zorrobpm.handler.grpc.max-active-jobs`      | `32`                   | Jobs handled at the same time                                           |
| `zorrobpm.handler.grpc.tls`                  | `false`                | Connect with TLS                                                        |
| `zorrobpm.handler.grpc.result-attempts`      | `4`                    | Attempts to send a result while the engine is unavailable               |
| `zorrobpm.handler.grpc.result-retry-interval`| `1s`                   | First pause between these attempts, doubled each time                   |
| `zorrobpm.handler.grpc.reconnect-interval`   | `1s`                   | First pause before reconnecting after the stream broke, doubled…        |
| `zorrobpm.handler.grpc.reconnect-max-interval`| `30s`                 | …up to this pause                                                        |

The worker starts even when the engine is down and keeps reconnecting.

## Delivery is at least once

On both transports a handler can run more than once for the same `serviceTaskId`:

- RabbitMQ: a job is acknowledged only after its result is sent; a failed send delivers it again.
- gRPC: the engine locks a pushed job for `lock-timeout`; if no result comes in time (the worker died,
  the stream broke, the result could not be sent), the job is pushed again. **`lock-timeout` must be
  longer than the handler runs**, or a slow handler gets its job a second time while still working.

Side effects outside the process (payments, emails, calls to other systems) must therefore be
idempotent by `serviceTaskId`. The engine applies the first result and ignores repeated ones.
