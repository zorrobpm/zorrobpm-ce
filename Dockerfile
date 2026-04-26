FROM docker.io/maven:3.9-amazoncorretto-17 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY zorrobpm-contract/pom.xml zorrobpm-contract/
COPY zorrobpm-event/pom.xml zorrobpm-event/
COPY zorrobpm-client/pom.xml zorrobpm-client/
COPY zorrobpm-engine/pom.xml zorrobpm-engine/
COPY zorrobpm-rest/pom.xml zorrobpm-rest/
COPY zorrobpm-job-handler-spring-boot-starter/pom.xml zorrobpm-job-handler-spring-boot-starter/
COPY zorrobpm-test/pom.xml zorrobpm-test/
COPY zorrobpm-exchange/pom.xml zorrobpm-exchange/
COPY zorrobpm-rabbitmq/pom.xml zorrobpm-rabbitmq/
COPY zorrobpm-ce/pom.xml zorrobpm-ce/

RUN mvn -B -ntp -pl zorrobpm-ce -am dependency:go-offline -DskipTests || true

COPY zorrobpm-contract/src zorrobpm-contract/src
COPY zorrobpm-event/src zorrobpm-event/src
COPY zorrobpm-client/src zorrobpm-client/src
COPY zorrobpm-engine/src zorrobpm-engine/src
COPY zorrobpm-rest/src zorrobpm-rest/src
COPY zorrobpm-job-handler-spring-boot-starter/src zorrobpm-job-handler-spring-boot-starter/src
COPY zorrobpm-test/src zorrobpm-test/src
COPY zorrobpm-exchange/src zorrobpm-exchange/src
COPY zorrobpm-rabbitmq/src zorrobpm-rabbitmq/src
COPY zorrobpm-ce/src zorrobpm-ce/src

RUN mvn -B -ntp -pl zorrobpm-ce -am package -DskipTests

RUN mkdir -p /workspace/extracted \
 && cp zorrobpm-ce/target/*.jar /workspace/extracted/application.jar \
 && cd /workspace/extracted \
 && java -Djarmode=layertools -jar application.jar extract \
 && rm application.jar

FROM docker.io/amazoncorretto:17 AS runtime
WORKDIR /app

RUN yum install -y shadow-utils \
 && groupadd -r zorro \
 && useradd -r -g zorro -d /app -s /sbin/nologin zorro \
 && mkdir -p /app/files \
 && chown -R zorro:zorro /app \
 && yum clean all

USER zorro

COPY --from=build --chown=zorro:zorro /workspace/extracted/dependencies/ ./
COPY --from=build --chown=zorro:zorro /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=zorro:zorro /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=zorro:zorro /workspace/extracted/application/ ./

ENV SERVER_PORT=8080 \
    APP_FILES_DIR=/app/files \
    JAVA_OPTS=""

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
