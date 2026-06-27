# ========================
# Build Stage
# ========================
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

# Copy Gradle wrapper and root build files first (layer caching)
COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle .
COPY build.gradle .

# Copy api-service build file
COPY api-service/build.gradle api-service/build.gradle

# Download dependencies (cached layer if build files unchanged)
RUN ./gradlew :api-service:dependencies --no-daemon

# Copy source and build
COPY api-service/src api-service/src
RUN ./gradlew :api-service:bootJar --no-daemon -x test

# ========================
# Runtime Stage
# ========================
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN useradd -m springuser

COPY --from=builder /workspace/api-service/build/libs/*.jar app.jar

USER springuser

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
