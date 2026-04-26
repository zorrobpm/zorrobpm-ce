FROM docker.io/amazoncorretto:17 AS builder
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar

RUN java -Djarmode=layertools -jar application.jar extract

FROM docker.io/amazoncorretto:17
COPY --from=builder dependencies/ ./
COPY --from=builder snapshot-dependencies/ ./
COPY --from=builder spring-boot-loader/ ./
COPY --from=builder application/ ./

ENV SERVER_PORT=80
EXPOSE 80

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
