# ZorroBPM Community Edition

A lightweight BPM engine built on Spring Boot. This repository holds the engine, its REST API, the client and
contract libraries, the job handler starter for workers, the RabbitMQ and gRPC transports, and the runnable
`zorrobpm-ce` application.

## Maven artifacts

All libraries are published to Maven Central under the group `com.zorrodev.bpm`:

| Artifact | Purpose |
|---|---|
| `zorrobpm` | Parent pom |
| `zorrobpm-contract` | Shared API contract |
| `zorrobpm-event` | Engine events |
| `zorrobpm-client` | Client for the engine REST API |
| `zorrobpm-engine` | The BPM engine |
| `zorrobpm-rest` | REST API of the engine |
| `zorrobpm-job-handler-spring-boot-starter` | Spring Boot starter for service task workers |
| `zorrobpm-test` | Test support |
| `zorrobpm-exchange` | Messages exchanged between the engine and workers |
| `zorrobpm-rabbitmq` | RabbitMQ transport for service task jobs |
| `zorrobpm-grpc` | gRPC transport for service task jobs |

The `zorrobpm-ce` application is not published to Maven Central; it ships as a Docker image
(`ghcr.io/zorrobpm/zorrobpm-ce`).

### Releases

Releases are published on every `v*` tag and are available from Maven Central without extra configuration:

```xml
<dependency>
    <groupId>com.zorrodev.bpm</groupId>
    <artifactId>zorrobpm-job-handler-spring-boot-starter</artifactId>
    <version>0.7.26</version>
</dependency>
```

### Snapshots

Every commit to `main` publishes the current `-SNAPSHOT` version (see `<version>` in [`pom.xml`](pom.xml)) to the
Maven Central snapshots repository:

```
https://central.sonatype.com/repository/maven-snapshots/
```

Snapshots are unstable: the same version is overwritten by every commit, and Central removes old snapshots after
a limited time (about 90 days). Use them to try unreleased changes, and depend on releases for anything
long-lived.

Maven:

```xml
<repositories>
    <repository>
        <id>central-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots/</url>
        <releases>
            <enabled>false</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.zorrodev.bpm</groupId>
        <artifactId>zorrobpm-job-handler-spring-boot-starter</artifactId>
        <version>0.7.27-SNAPSHOT</version>
    </dependency>
</dependencies>
```

Gradle (Kotlin DSL):

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
    }
}

dependencies {
    implementation("com.zorrodev.bpm:zorrobpm-job-handler-spring-boot-starter:0.7.27-SNAPSHOT")
}
```

Add `-U` to Maven (or `--refresh-dependencies` to Gradle) to pick up a newer snapshot of the same version.

## User task events

The engine can publish the creation, assignment, completion and cancellation of user tasks to the RabbitMQ
queue `zorrobpm.user-task-events`, which it declares itself (off by default,
`zorrobpm.events.user-task.enabled=true`). See [docs/user-task-events.md](docs/user-task-events.md) for the
event types, the message format, the delivery guarantees and the move from the former topic exchange.

## HTTP connector

`zorrobpm-http-connector` is an optional module with a built-in worker for outbound HTTP/REST calls. A service
task with `zeebe:taskDefinition type="zorrobpm:http"` is executed by this worker: it turns the input mapping of
the task into the HTTP request and reports the result back to the process, so calling another system does not
need a worker application of your own.

The worker is off by default and reaches nothing until an administrator allows it. `allowed-hosts` is the
allowlist of target hosts and an empty value denies everything. Every target URL is checked against that
allowlist, against the private and reserved IP ranges after DNS resolution, and again on every redirect hop.
A secret is never taken from the process: an authentication header or query parameter is referenced by name
(`http.authRef`) and taken from the secret store of the connector. Any status other than 2xx raises the BPMN
error `HTTP_<status>` on the task, a deterministic configuration error raises `HTTP_CONNECTOR_CONFIG` or
`HTTP_CONNECTOR_DISABLED`, and a transport failure is left to the engine, which retries it.

| Property | Default | Meaning |
|---|---|---|
| `zorrobpm.http-connector.enabled` | `false` | Master switch. `false` rejects every task with the BPMN error `HTTP_CONNECTOR_DISABLED`. |
| `zorrobpm.http-connector.allowed-hosts` | empty | Comma-separated allowlist of target hosts; an empty value denies all. |
| `zorrobpm.http-connector.allow-private-networks` | `false` | Whether private and reserved IP ranges may be reached. Outside the dev and test profiles `true` fails the startup. |
| `zorrobpm.http-connector.secrets.<name>` | | A secret as a JSON object: `{"type":"bearer","token":"…"}`, `{"type":"basic","username":"…","password":"…"}` or `{"type":"apiKey","name":"X-Key","value":"…","in":"header"}`. |
| `zorrobpm.http-connector.secrets-json` | empty | The same secrets as one JSON object of `{name: secret}`, for names that an environment variable cannot express. |
| `zorrobpm.http-connector.max-response-bytes` | `1048576` | Hard cap of the response body; a larger body is rejected with a BPMN error. |
| `zorrobpm.http-connector.max-redirects` | `0` | How many redirects to follow; every hop is validated again. |
| `zorrobpm.http-connector.default-connection-timeout-seconds` | `20` | Default connect timeout. |
| `zorrobpm.http-connector.default-read-timeout-seconds` | `20` | Default deadline for the headers, the redirect hops and the body of one exchange. |
| `zorrobpm.http-connector.max-connection-timeout-seconds` | `120` | Upper cap of the connect timeout. |
| `zorrobpm.http-connector.max-read-timeout-seconds` | `300` | Upper cap of the read timeout. |

The task inputs are `http.url`, `http.method` (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`), `http.headers`,
`http.queryParameters`, `http.body`, `http.authType` (`none`, `apiKey`, `basic`, `bearer`), `http.authRef`,
`http.connectionTimeout` and `http.readTimeout`; a secret passed as a literal is rejected. The result is
`http.status` (`LONG`), `http.headers` (`JSON`, without `set-cookie`) and `http.body` (`JSON` or `STRING`,
depending on the Content-Type), which the output mapping of the element writes into process variables.

## Sentry

The `zorrobpm-ce` application reports errors and traces to [Sentry](https://sentry.io) (SaaS or
self-hosted). It is configured with environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `SENTRY_DSN` | empty | DSN of the Sentry project. Empty or unset: the SDK stays off and nothing is sent. |
| `SENTRY_ENVIRONMENT` | empty | Environment of the events, e.g. `prod`, `staging`. |
| `SENTRY_RELEASE` | version of the build | Release of the events. |
| `SENTRY_TRACES_SAMPLE_RATE` | `0.1` | Share of HTTP requests recorded as traces, `0`..`1`. `0` turns tracing off; errors are still sent. |

Sentry receives:

- exceptions that end an HTTP request with a 5xx response;
- log records of level `ERROR`, including timers, queue listeners and gRPC handlers. Records of level `INFO`
  and above travel along as breadcrumbs.

Refusals the API makes on purpose (404, 409, 400 on a rejected query and other 4xx) are not reported. An
incoming `sentry-trace` header continues the caller's trace.

No personal data is sent: no user, IP address, cookies, `Authorization` header, query strings (the tasklist
passes the person's IIN and groups in them), request or response bodies, process variables. Turning on "Prevent Storing of IP Addresses" in the Sentry project settings is still
recommended. Unreachable Sentry does not affect request processing.

## For maintainers

Publishing is done by GitHub Actions:

- `.github/workflows/maven-snapshot.yml` publishes snapshots on every push to `main` and can be started manually
  from any branch (Actions → *Publish Snapshot to Maven Central* → *Run workflow*). It builds with tests and
  refuses to run when the version is not `-SNAPSHOT`.
- `.github/workflows/maven-publish.yml` publishes a release on every `v*` tag and refuses `-SNAPSHOT` versions.

Both workflows publish the same modules through `.github/scripts/publish-modules.sh`. The script checks its list
against `<modules>` of the root `pom.xml`, so a new module must be added either to `MODULES` (published) or to
`EXCLUDED` (not published) in that script.

One-time setup:

- In the [Central Portal](https://central.sonatype.com/publishing/namespaces), enable SNAPSHOTs for the
  `com.zorrodev` namespace. Without it, snapshot uploads are rejected.
- Repository secrets: `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (a Central Portal user token),
  `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE` (the signing key).

`mvn release:prepare` pushes a commit with the release version to `main` before the next `-SNAPSHOT` commit. If
the snapshot workflow runs on that release commit, it fails on the version check without publishing anything.
This is expected: the tag publishes the release, and the next snapshot commit publishes the snapshot.
