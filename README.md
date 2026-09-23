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
