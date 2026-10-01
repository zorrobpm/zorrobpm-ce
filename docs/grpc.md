# gRPC transport for service task jobs

ZorroBPM hands the jobs of service tasks to workers over one of two transports: RabbitMQ (the default)
or gRPC. This guide covers the gRPC transport: how to turn it on, how to connect a worker, the protocol
for workers written in any language, and what to check when something goes wrong.

- [Overview](#overview)
- [Enabling gRPC on the engine](#enabling-grpc-on-the-engine)
- [Java workers](#java-workers)
- [Protocol](#protocol)
- [Delivery and locking](#delivery-and-locking)
- [Authentication](#authentication)
- [TLS](#tls)
- [Troubleshooting](#troubleshooting)

## Overview

With the gRPC transport a worker connects straight to the engine and no message broker is involved:

```
worker                                engine
  │  SubscribeJobs(jobs=["charge"])     │
  │ ──────────────────────────────────▶ │
  │ ◀──────────── Job ───────────────── │   pushed when a service task "charge" is ready,
  │ ◀──────────── Job ───────────────── │   locked for this worker
  │                                     │
  │  CompleteJob / FailJob /            │
  │  ThrowBpmnError                     │
  │ ──────────────────────────────────▶ │   unary call, the lock is released
```

| | RabbitMQ | gRPC |
|---|---|---|
| Needs a broker | yes | no |
| Where jobs wait | queue `zorrobpm.jobs.<job>` | the engine database |
| Worker connects to | RabbitMQ | the gRPC port of the engine (`9090`) |
| Delivery | at least once | at least once |

An installation runs exactly one transport, and the workers must use the same one. The handler code of
a Java worker is the same on both; only its configuration differs.

## Enabling gRPC on the engine

Set the transport to `grpc`:

```properties
zorrobpm.transport=grpc
```

On `grpc` the engine opens the gRPC server and does not connect to RabbitMQ at all: it starts and runs
processes even when no broker exists. On `rabbitmq` (or with the property unset) the gRPC port stays
closed. Any other value stops the start with an error that names the property and the allowed values.

| Property | Default | Meaning |
|---|---|---|
| `zorrobpm.transport` | `rabbitmq` | Transport of service task jobs: `rabbitmq` or `grpc` |
| `zorrobpm.grpc.port` | `9090` | Port of the gRPC server |
| `zorrobpm.grpc.lock-timeout` | `5m` | How long a pushed job stays locked when the subscription does not set its own lock timeout |
| `zorrobpm.grpc.max-active-jobs` | `32` | Jobs pushed to a subscription and still without a result, when the subscription does not set its own limit |
| `zorrobpm.grpc.poll-interval` | `1s` | How often the engine looks for ready jobs; a ready job reaches a subscriber within this time |

The `zorrobpm-ce` application reads the first two from the environment variables `ZORROBPM_TRANSPORT`
and `ZORROBPM_GRPC_PORT`. The others can be set like any Spring Boot property, for example with the
environment variable `ZORROBPM_GRPC_LOCK_TIMEOUT=10m`.

### Docker Compose

The [`docker-compose.yml`](../docker-compose.yml) of this repository passes `ZORROBPM_TRANSPORT` to the
engine and publishes the gRPC port:

```bash
ZORROBPM_TRANSPORT=grpc docker compose up -d
```

The gRPC port is published on the host as `9090`; change it with `GRPC_PORT`. The compose file still
starts the RabbitMQ container, but the engine does not connect to it.

The engine log shows that the server is up:

```
Registered gRPC service: zorrobpm.jobs.v1.JobService
gRPC Server started, listening on address: [/[0:0:0:0:0:0:0:0]:9090]
```

### Switching the transport of a running installation

- **RabbitMQ → gRPC.** Restart the engine with `zorrobpm.transport=grpc` and switch the workers. Open
  service tasks are not lost: after the restart their jobs are pushed to the gRPC workers.
- **gRPC → RabbitMQ.** A job of a service task that is already open is not published to its queue by
  the restart alone. It is published the next time the process enters the service task, a retry comes
  due, or its incident is resolved.

## Java workers

A Spring Boot worker uses [`zorrobpm-job-handler-spring-boot-starter`](../zorrobpm-job-handler-spring-boot-starter/README.md)
(see [Maven artifacts](../README.md#maven-artifacts) for the dependency). Every bean that implements
`JobHandler` handles the jobs of its type; to run it over gRPC, set:

```properties
zorrobpm.handler.transport=grpc
zorrobpm.handler.grpc.address=engine-host:9090
```

The starter opens one stream for the job types of all handlers, runs the handlers, reports their
outcome, reconnects when the stream breaks, and retries a result the engine could not accept. The worker
starts even when the engine is down. The full list of `zorrobpm.handler.grpc.*` properties (token, lock
timeout, concurrency, TLS, retry and reconnect intervals) is in the
[README of the starter](../zorrobpm-job-handler-spring-boot-starter/README.md#grpc).

## Protocol

A worker in any language can talk to the engine with the service `zorrobpm.jobs.v1.JobService`, defined
in [`jobs.proto`](../zorrobpm-exchange/src/main/proto/zorrobpm/jobs/v1/jobs.proto). Generate a client
from that file with the gRPC tooling of your language. The engine also exposes gRPC server reflection,
so tools such as `grpcurl` work without the file.

| Call | Kind | Purpose |
|---|---|---|
| `SubscribeJobs` | server stream | Receive the jobs of the given types as they become ready |
| `CompleteJob` | unary | Complete the service task and set variables on the process |
| `FailJob` | unary | Report a failure: the engine schedules a retry or opens an incident |
| `ThrowBpmnError` | unary | Throw a BPMN error for an error boundary event of the service task |

### SubscribeJobs

Request `SubscribeJobsRequest`:

| Field | Required | Meaning |
|---|---|---|
| `jobs` | yes | Job types to receive, the `type` of `zeebe:taskDefinition` of the service tasks. At least one, none blank |
| `worker` | no | Name of the worker, shown in the engine log; `unnamed` when empty |
| `lock_timeout` | no | How long a pushed job stays locked for this stream; must be positive. Unset: `zorrobpm.grpc.lock-timeout` of the engine |
| `max_active_jobs` | no | Jobs pushed to this stream and still without a result; must be positive. Unset: `zorrobpm.grpc.max-active-jobs` of the engine |

The stream stays open until the worker or the engine closes it. Each message is a `Job`:

| Field | Meaning |
|---|---|
| `service_task_id` | Id of the service task (UUID); pass it back in the result call |
| `process_instance_id` | Id of the process instance (UUID) |
| `process_definition_id` | Id of the process definition (UUID) |
| `service_task_key` | Id of the BPMN element |
| `job` | Job type |
| `retries` | Retries still available for this service task if this attempt fails |
| `variables` | Input of the job: the result of the input mapping of the service task if it has one, otherwise all variables of the process instance |

An invalid request ends the call with `INVALID_ARGUMENT`.

### Variables

A `Variable` is `name`, `value` and `type`. The value is always a string; the type tells how to read it:

| `type` | `value` |
|---|---|
| `STRING` | the text itself |
| `LONG` | a decimal integer, for example `100` |
| `BOOLEAN` | `true` or `false` |
| `UUID` | a UUID in the canonical text form |
| `JSON` | a JSON document, for example `{"id":7}` |

A result with a variable of another type is rejected with `INVALID_ARGUMENT`.

### CompleteJob

`CompleteJobRequest`: `service_task_id` and `variables`. The service task is completed and the process
moves on. The variables are written to the process instance as they are, or through the output mapping
of the service task if it has one. A failing output mapping opens an incident on the service task; the
call still answers `OK`.

### FailJob

`FailJobRequest`:

| Field | Meaning |
|---|---|
| `service_task_id` | Id of the service task |
| `error_code`, `message`, `details` | What went wrong; shown on the incident. `details` usually carries a stack trace |
| `retries` | Optional. Retries still available at this failure; overrides the counter of the engine. A negative value counts as `0` |
| `retry_timeout` | Optional. ISO-8601 duration until the retry (for example `PT30S`); overrides the interval from the BPMN. An invalid value is ignored |
| `variables` | Not applied: a failure does not change the variables of the process instance |

While retries remain, the engine pushes the job again after the retry interval; when none remain, it
opens an incident and the job is not pushed until the incident is resolved.

### ThrowBpmnError

`ThrowBpmnErrorRequest`: `service_task_id`, `error_code`, `message` and `variables`. The process leaves
the service task through the error boundary event that catches `error_code`, and the variables are set
on the process instance. A call without `error_code` opens an incident with the error code
`INVALID_BPMN_ERROR`, without retries.

### Result statuses

`CompleteJob`, `FailJob` and `ThrowBpmnError` answer with an empty `JobResultResponse` or a status:

| Status | When | What the worker should do |
|---|---|---|
| `OK` | The result is stored. Also for a late result: the service task is already completed, interrupted or waiting for a retry, and nothing changes | Nothing |
| `INVALID_ARGUMENT` | `service_task_id` is missing or not a UUID, or a variable has an unknown type | Fix the request; a retry will fail the same way |
| `NOT_FOUND` | No service task with this id | Drop the result |
| `UNAVAILABLE` | The engine could not reach its database | Retry with a pause |
| `INTERNAL` | Any other error of the engine | Retry with a pause |

A result is accepted from any worker, not only from the one the job was pushed to. The call returns
only after the result is stored, so a successful answer means the process has moved on.

### Trying it with grpcurl

Start a process instance that stops at a service task with the job type `charge`, then subscribe:

```bash
grpcurl -plaintext -d '{"jobs": ["charge"], "worker": "demo", "lock_timeout": "30s"}' \
  localhost:9090 zorrobpm.jobs.v1.JobService/SubscribeJobs
```

The stream prints the job and stays open:

```json
{
  "service_task_id": "6cc9c5e1-3028-49e8-b0c9-3a414ba36552",
  "process_instance_id": "26662386-c074-4de5-8833-ac0c69e113cb",
  "process_definition_id": "75f455a3-222f-498a-9e10-c8da6ee04271",
  "service_task_key": "charge",
  "job": "charge",
  "variables": [
    { "name": "amount", "value": "100", "type": "LONG" }
  ]
}
```

In another terminal, complete it with the `service_task_id` from the job:

```bash
grpcurl -plaintext -d '{
    "service_task_id": "6cc9c5e1-3028-49e8-b0c9-3a414ba36552",
    "variables": [{ "name": "paid", "value": "true", "type": "BOOLEAN" }]
  }' \
  localhost:9090 zorrobpm.jobs.v1.JobService/CompleteJob
```

The answer is `{}`, the service task is completed, and `paid` is set on the process instance.

## Delivery and locking

On the gRPC transport the engine itself stores the jobs. A job is ready to be pushed when its service
task is open, has no open incident, is not waiting for a retry, and is not locked.

- A ready job is pushed to exactly one of the streams subscribed to its type, also when several engine
  nodes share one database, and is locked for the lock timeout of that stream.
- A job created while nobody was subscribed, or open across an engine restart, is pushed as soon as a
  subscriber appears.
- A stream never holds more than `max_active_jobs` jobs without a result. The next job is pushed when
  one of them gets a result or its lock expires.
- The lock is released when a result arrives, when the stream closes or breaks, or when the lock
  timeout expires. If the service task is still open, the job is then pushed again, to any subscriber.
  This does not use up a retry and does not open an incident.

Delivery is therefore **at least once**: a worker can receive a job for the same `service_task_id` more
than once. Two rules follow:

- **The lock timeout must be longer than the handler runs.** Otherwise a slow handler gets its job a
  second time, or another worker gets it, while the first is still working.
- **Side effects must be idempotent by `service_task_id`.** Payments, emails and calls to other systems
  may be attempted twice. The engine applies the first result and ignores repeated ones.

## Authentication

- **Community edition.** The gRPC calls are accepted without credentials, like the REST API. Do not
  expose the port outside a trusted network.
- **Enterprise edition.** Every call must carry the metadata header `authorization: Bearer <token>` with
  an API token of a user with the `ADMIN` or `OPERATOR` role. A call without a token, or with an unknown
  or revoked one, is rejected with `UNAUTHENTICATED`; a token of a user without these roles, with
  `PERMISSION_DENIED`. Revoking a token does not close the streams already open with it, but every later
  call with it is rejected.

A Java worker sends the token from `zorrobpm.handler.grpc.token`. With `grpcurl`, add
`-H "authorization: Bearer $ZORROBPM_API_TOKEN"`.

## TLS

By default the gRPC server of the engine accepts plaintext connections. There are two ways to encrypt
them:

- Terminate TLS at a reverse proxy or ingress that supports HTTP/2 and forwards to the gRPC port.
- Let the engine serve TLS itself. The server is a Spring gRPC server, so it takes a Spring Boot SSL
  bundle through `spring.grpc.server.ssl.bundle`.

In both cases a Java worker connects with `zorrobpm.handler.grpc.tls=true`.

## Troubleshooting

The engine logs every subscription, every pushed job and every released lock at `INFO`, with the name
the worker passed in `worker`. Start there.

| Symptom | Likely cause | What to check |
|---|---|---|
| The engine does not start: `Invalid zorrobpm.transport '...': expected one of rabbitmq, grpc` | A typo in the transport | The value of `zorrobpm.transport` / `ZORROBPM_TRANSPORT` |
| The worker cannot connect to port `9090` | The engine runs on `rabbitmq`, so the port is closed; or the port is not published | The engine log has `gRPC Server started`; the port mapping of the container |
| The worker is connected but gets no jobs | It subscribed to another job type; the service task has an open incident or waits for a retry; another stream holds the lock | `jobs` against the `type` of `zeebe:taskDefinition`; the incidents of the process instance; `Pushed job ... locked until` in the engine log |
| A job arrives only after a long pause | A worker received it and never reported a result, so the job waited for its lock timeout | `Pushed job ... locked until` in the engine log; lower the lock timeout to a little more than the longest handler run |
| The same job arrives twice | The handler ran longer than the lock timeout, or its result did not reach the engine | Raise the lock timeout; look for failed result calls in the worker log; make the handler idempotent |
| A stream gets only a few jobs while many are ready | `max_active_jobs` jobs of the stream have no result yet | Report every job; raise `max_active_jobs` |
| `INVALID_ARGUMENT` on `SubscribeJobs` | Empty `jobs`, a blank job type, a non-positive `lock_timeout` or `max_active_jobs` | The description of the status names the field |
| `INVALID_ARGUMENT` on a result call | `service_task_id` is missing or not a UUID; a variable has an unknown `type` | The description of the status |
| `NOT_FOUND` on a result call | No service task with this id: a wrong id, or a result sent to another installation | The id against the `service_task_id` of the job; the address of the engine |
| `UNAUTHENTICATED` (enterprise edition) | No token, or the token is unknown or revoked | The `authorization` header; issue a new API token |
| `PERMISSION_DENIED` (enterprise edition) | The user of the token has neither `ADMIN` nor `OPERATOR` | The roles of the user |
| The worker reports RabbitMQ as down in its health check | The application has RabbitMQ on the classpath but does not use it | `management.health.rabbit.enabled=false` in the worker |
