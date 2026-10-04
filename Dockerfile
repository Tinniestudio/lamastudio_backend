# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY settings.gradle build.gradle ./
COPY api-service/build.gradle api-service/build.gradle
COPY media-worker/build.gradle media-worker/build.gradle
RUN ./gradlew --no-daemon help

COPY api-service/src api-service/src
COPY media-worker/src media-worker/src
RUN ./gradlew --no-daemon :api-service:bootJar :media-worker:bootJar -x test

FROM eclipse-temurin:21-jre-jammy AS api-service
WORKDIR /app
RUN useradd -m springuser
COPY --from=builder /workspace/api-service/build/libs/tinniestudio-api-service-*.jar app.jar
USER springuser
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]

FROM mwader/static-ffmpeg:latest AS ffmpeg

FROM eclipse-temurin:21-jre-jammy AS worker
WORKDIR /app
COPY --from=ffmpeg /ffmpeg  /usr/local/bin/ffmpeg
COPY --from=ffmpeg /ffprobe /usr/local/bin/ffprobe
RUN useradd -m workeruser
COPY --from=builder /workspace/media-worker/build/libs/tinniestudio-media-worker-*.jar app.jar
RUN mkdir -p /tmp/tinniestudio && chown workeruser /tmp/tinniestudio
USER workeruser
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
