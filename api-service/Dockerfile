# Build JAR locally first: ./gradlew :api-service:bootJar -x test
# GitHub is blocked on this host so Gradle cannot run inside Docker.
# CI/CD environments with full internet access can restore the multi-stage build.

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN useradd -m springuser

COPY api-service/build/libs/tinniestudio-api-service-*.jar app.jar

USER springuser

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
