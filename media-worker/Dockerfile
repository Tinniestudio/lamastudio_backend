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

# Copy worker build file
COPY media-worker/build.gradle media-worker/build.gradle

# Download dependencies (cached layer if build files unchanged)
RUN ./gradlew :media-worker:dependencies --no-daemon

# Copy source and build
COPY media-worker/src media-worker/src
RUN ./gradlew :media-worker:bootJar --no-daemon -x test

# ========================
# Runtime Stage
# ========================
FROM eclipse-temurin:21-jre-jammy

# Install FFmpeg and FFprobe
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN useradd -m workeruser

COPY --from=builder /workspace/media-worker/build/libs/*.jar app.jar

RUN mkdir -p /tmp/tinniestudio && chown workeruser /tmp/tinniestudio

USER workeruser

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
