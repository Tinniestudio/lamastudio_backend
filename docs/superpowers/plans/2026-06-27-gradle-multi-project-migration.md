# Gradle Multi-Project Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the single Maven project into a Gradle multi-project build with two independently deployable subprojects — `api-service` (existing code, renamed package) and `media-worker` (new scaffold).

**Architecture:** Root Gradle project at `/server` contains two subprojects. All existing source moves into `api-service/src/`, package renamed from `com.lamastudio.backend` to `com.tinniestudio.api`. `media-worker/` is a new, compilable-but-empty Spring Boot app with stub classes. Each subproject has its own `Dockerfile`. `docker-compose.yml` at repo root manages the full stack.

**Tech Stack:** Java 21, Gradle 8.8, Spring Boot 3.3.5, Spring AMQP (worker), PostgreSQL, Redis, RabbitMQ, MinIO.

**Working directory for all commands:** `/home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server`

---

## File Map

### Created
| File | Purpose |
|------|---------|
| `settings.gradle` | Root: defines project name + includes subprojects |
| `build.gradle` | Root: shared allprojects config only |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle wrapper version pin |
| `gradle/wrapper/gradle-wrapper.jar` | Gradle wrapper binary |
| `gradlew` | Unix wrapper script |
| `gradlew.bat` | Windows wrapper script |
| `api-service/build.gradle` | All current pom.xml deps, Gradle syntax |
| `api-service/Dockerfile` | Multi-stage: Gradle build + JRE 21 runtime |
| `api-service/src/main/java/com/tinniestudio/api/ApiServiceApplication.java` | Renamed main class |
| `media-worker/build.gradle` | Worker deps: AMQP, JPA, PostgreSQL, S3 SDK |
| `media-worker/Dockerfile` | Multi-stage: Gradle build + JRE 21 + FFmpeg |
| `media-worker/src/main/java/com/tinniestudio/worker/MediaWorkerApplication.java` | Worker main class |
| `media-worker/src/main/java/com/tinniestudio/worker/config/RabbitConfig.java` | Stub: RabbitMQ topology |
| `media-worker/src/main/java/com/tinniestudio/worker/consumer/VideoProcessingConsumer.java` | Stub: queue listener |
| `media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java` | Stub: pipeline orchestrator |
| `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFmpegRunner.java` | Stub: FFmpeg executor |
| `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFprobeRunner.java` | Stub: FFprobe executor |
| `media-worker/src/main/resources/application.yml` | Worker config |

### Modified
| File | Change |
|------|--------|
| `docker-compose.yml` | Replace single `app` with `api-service` + `media-worker`; add `redis`, `rabbitmq`, `minio` |
| All `*.java` in `api-service/src/` | Package declaration + imports renamed |
| `api-service/src/main/resources/application.yml` | app name + logging prefix updated |

### Deleted
| File | Reason |
|------|--------|
| `pom.xml` | Replaced by Gradle |
| `Dockerfile` | Replaced by `api-service/Dockerfile` |
| `src/` (root-level) | Moved into `api-service/src/` |

---

## Task 1: Install Gradle and Generate Wrapper

**Files:** `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`

- [ ] **Step 1: Install Gradle 8.8 via SDKMAN**

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk install gradle 8.8
```

Expected output: `Gradle 8.8 has been successfully installed.` Wait for it to complete — it downloads ~100 MB.

- [ ] **Step 2: Verify Gradle is available**

```bash
source ~/.sdkman/bin/sdkman-init.sh && gradle --version
```

Expected: `Gradle 8.8` in the output.

- [ ] **Step 3: Generate Gradle wrapper at repo root**

```bash
source ~/.sdkman/bin/sdkman-init.sh && gradle wrapper --gradle-version 8.8
```

Expected output: `BUILD SUCCESSFUL`. This creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.

- [ ] **Step 4: Verify wrapper works**

```bash
./gradlew --version
```

Expected: `Gradle 8.8` in the output (runs without errors).

- [ ] **Step 5: Make gradlew executable (already set by generator, but confirm)**

```bash
chmod +x gradlew
```

---

## Task 2: Create Root Build Files

**Files:** `settings.gradle`, `build.gradle`

- [ ] **Step 1: Create `settings.gradle`**

Create file at repo root:

```groovy
rootProject.name = 'tinniestudio'

include 'api-service'
include 'media-worker'
```

- [ ] **Step 2: Create root `build.gradle`**

Create file at repo root:

```groovy
allprojects {
    group = 'com.tinniestudio'
    version = '1.0.0'

    repositories {
        mavenCentral()
    }
}
```

- [ ] **Step 3: Verify root project is recognized (subprojects don't exist yet — error is expected)**

```bash
./gradlew projects
```

Expected: Lists `api-service` and `media-worker` as subprojects. Error about missing `build.gradle` in each is fine at this point.

---

## Task 3: Create `api-service` Directory and Move Source

**Files:** `api-service/src/` (moved from `src/`)

- [ ] **Step 1: Create the api-service directory structure**

```bash
mkdir -p api-service
```

- [ ] **Step 2: Move entire `src/` into `api-service/`**

```bash
mv src api-service/src
```

- [ ] **Step 3: Verify source files are in place**

```bash
find api-service/src/main/java -name "*.java" | wc -l
```

Expected: a number greater than 0 (matches your original Java file count).

---

## Task 4: Create `api-service/build.gradle`

**Files:** `api-service/build.gradle`

- [ ] **Step 1: Create `api-service/build.gradle`**

```groovy
plugins {
    id 'org.springframework.boot' version '3.3.5'
    id 'io.spring.dependency-management' version '1.1.6'
    id 'java'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom annotationProcessor
    }
}

dependencies {
    // Spring Boot Core
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // Redis connection pool
    implementation 'io.lettuce:lettuce-core'
    implementation 'org.apache.commons:commons-pool2'

    // Database
    runtimeOnly 'org.postgresql:postgresql'

    // Flyway
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'

    // JWT (JJWT 0.12.6)
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
    runtimeOnly   'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly   'io.jsonwebtoken:jjwt-jackson:0.12.6'

    // Lombok
    compileOnly         'org.projectlombok:lombok:1.18.32'
    annotationProcessor 'org.projectlombok:lombok:1.18.32'

    // SpringDoc OpenAPI / Swagger UI
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0'

    // Resend Email
    implementation 'com.resend:resend-java:4.13.0'

    // Stripe
    implementation 'com.stripe:stripe-java:26.3.0'

    // Dev tools (excluded from production JAR automatically)
    developmentOnly 'org.springframework.boot:spring-boot-devtools'

    // Test
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testImplementation 'org.testcontainers:junit-jupiter'
    testImplementation 'org.testcontainers:postgresql'
    testCompileOnly         'org.projectlombok:lombok:1.18.32'
    testAnnotationProcessor 'org.projectlombok:lombok:1.18.32'
}

test {
    useJUnitPlatform()
}

// Exclude Lombok from the final JAR (same as Maven config)
springBoot {
    mainClass = 'com.tinniestudio.api.ApiServiceApplication'
}
```

- [ ] **Step 2: Attempt compile (will fail — old package names, wrong main class)**

```bash
./gradlew :api-service:compileJava 2>&1 | tail -20
```

Expected: compilation errors about `com.lamastudio.backend` package not matching directory structure. This is expected — we fix it in the next task.

---

## Task 5: Rename Packages and Directory Structure

**Files:** All `.java` files under `api-service/src/`

This task renames:
- Package declarations: `com.lamastudio.backend` → `com.tinniestudio.api`
- Import statements: `import com.lamastudio.backend` → `import com.tinniestudio.api`
- Physical directory tree: `com/lamastudio/backend/` → `com/tinniestudio/api/`
- Main class file: `LamaStudioApplication.java` → `ApiServiceApplication.java`

- [ ] **Step 1: Create new package directory structure**

```bash
mkdir -p api-service/src/main/java/com/tinniestudio/api
mkdir -p api-service/src/test/java/com/tinniestudio/api
```

- [ ] **Step 2: Move all Java source files to new package directory**

```bash
cp -r api-service/src/main/java/com/lamastudio/backend/. api-service/src/main/java/com/tinniestudio/api/
cp -r api-service/src/test/java/com/lamastudio/backend/. api-service/src/test/java/com/tinniestudio/api/
```

- [ ] **Step 3: Replace all package declarations and imports in main sources**

```bash
find api-service/src/main/java/com/tinniestudio -name "*.java" \
  -exec sed -i 's/com\.lamastudio\.backend/com.tinniestudio.api/g' {} +
```

- [ ] **Step 4: Replace all package declarations and imports in test sources**

```bash
find api-service/src/test/java/com/tinniestudio -name "*.java" \
  -exec sed -i 's/com\.lamastudio\.backend/com.tinniestudio.api/g' {} +
```

- [ ] **Step 5: Rename main application class file**

```bash
mv api-service/src/main/java/com/tinniestudio/api/LamaStudioApplication.java \
   api-service/src/main/java/com/tinniestudio/api/ApiServiceApplication.java
```

- [ ] **Step 6: Update class name inside `ApiServiceApplication.java`**

Open `api-service/src/main/java/com/tinniestudio/api/ApiServiceApplication.java` and replace the class declaration:

```java
package com.tinniestudio.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ApiServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiServiceApplication.class, args);
    }
}
```

- [ ] **Step 7: Delete the old `com.lamastudio` directory trees**

```bash
rm -rf api-service/src/main/java/com/lamastudio
rm -rf api-service/src/test/java/com/lamastudio
```

- [ ] **Step 8: Update `application.yml` — app name, logging, JWT issuer**

In `api-service/src/main/resources/application.yml`, apply these three changes:

Change `spring.application.name`:
```yaml
  application:
    name: tinniestudio-api
```

Change `logging.level`:
```yaml
logging:
  level:
    com.tinniestudio: DEBUG
    org.springframework.security: INFO
```

Change JWT issuer (search for `issuer: lamastudio`):
```yaml
    issuer: tinniestudio
```

- [ ] **Step 9: Compile api-service to verify package rename is complete**

```bash
./gradlew :api-service:compileJava
```

Expected: `BUILD SUCCESSFUL`. If there are remaining `com.lamastudio` references, find them:

```bash
grep -r "com\.lamastudio" api-service/src --include="*.java" -l
```

Fix any remaining occurrences with the same `sed` command from Step 3.

- [ ] **Step 10: Run existing tests to confirm nothing broke**

```bash
./gradlew :api-service:test
```

Expected: `BUILD SUCCESSFUL` — all tests pass. If tests fail due to missing infrastructure (Redis, DB), that is expected for integration tests. Unit tests must pass.

- [ ] **Step 11: Commit the api-service migration**

```bash
git add api-service/ settings.gradle build.gradle gradlew gradlew.bat gradle/
git commit -m "feat: migrate api-service to Gradle multi-project, rename package to com.tinniestudio.api"
```

---

## Task 6: Create `api-service/Dockerfile`

**Files:** `api-service/Dockerfile`

- [ ] **Step 1: Create `api-service/Dockerfile`**

```dockerfile
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
```

- [ ] **Step 2: Verify Dockerfile syntax (no build yet — just check it exists)**

```bash
cat api-service/Dockerfile | head -5
```

Expected: `# Build Stage` in the output.

- [ ] **Step 3: Commit**

```bash
git add api-service/Dockerfile
git commit -m "feat: add api-service Dockerfile (Gradle multi-stage build)"
```

---

## Task 7: Create `media-worker` Scaffold

**Files:** `media-worker/build.gradle`, all stub Java classes, `media-worker/src/main/resources/application.yml`

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p media-worker/src/main/java/com/tinniestudio/worker/config
mkdir -p media-worker/src/main/java/com/tinniestudio/worker/consumer
mkdir -p media-worker/src/main/java/com/tinniestudio/worker/processor
mkdir -p media-worker/src/main/java/com/tinniestudio/worker/ffmpeg
mkdir -p media-worker/src/main/resources
mkdir -p media-worker/src/test/java/com/tinniestudio/worker
```

- [ ] **Step 2: Create `media-worker/build.gradle`**

```groovy
plugins {
    id 'org.springframework.boot' version '3.3.5'
    id 'io.spring.dependency-management' version '1.1.6'
    id 'java'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom annotationProcessor
    }
}

dependencies {
    // Spring Boot Core
    implementation 'org.springframework.boot:spring-boot-starter'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'

    // RabbitMQ
    implementation 'org.springframework.boot:spring-boot-starter-amqp'

    // Database
    runtimeOnly 'org.postgresql:postgresql'

    // AWS S3 SDK v2 (for storage upload/download)
    implementation platform('software.amazon.awssdk:bom:2.26.0')
    implementation 'software.amazon.awssdk:s3'

    // Lombok
    compileOnly         'org.projectlombok:lombok:1.18.32'
    annotationProcessor 'org.projectlombok:lombok:1.18.32'

    // Test
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testCompileOnly         'org.projectlombok:lombok:1.18.32'
    testAnnotationProcessor 'org.projectlombok:lombok:1.18.32'
}

test {
    useJUnitPlatform()
}

springBoot {
    mainClass = 'com.tinniestudio.worker.MediaWorkerApplication'
}
```

- [ ] **Step 3: Create `MediaWorkerApplication.java`**

```java
package com.tinniestudio.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MediaWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaWorkerApplication.class, args);
    }
}
```

Save to: `media-worker/src/main/java/com/tinniestudio/worker/MediaWorkerApplication.java`

- [ ] **Step 4: Create `RabbitConfig.java` stub**

```java
package com.tinniestudio.worker.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "tinniestudio.direct";
    public static final String QUEUE_VIDEO_PROCESS = "media.video.process";
    public static final String QUEUE_VIDEO_RETRY   = "media.video.retry";
    public static final String QUEUE_VIDEO_FAILED  = "media.video.failed";

    @Bean
    public DirectExchange exchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue videoProcessQueue() {
        return QueueBuilder.durable(QUEUE_VIDEO_PROCESS)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_VIDEO_FAILED)
                .build();
    }

    @Bean
    public Queue videoFailedQueue() {
        return QueueBuilder.durable(QUEUE_VIDEO_FAILED).build();
    }

    @Bean
    public Binding videoProcessBinding(Queue videoProcessQueue, DirectExchange exchange) {
        return BindingBuilder.bind(videoProcessQueue).to(exchange).with(QUEUE_VIDEO_PROCESS);
    }

    @Bean
    public Binding videoFailedBinding(Queue videoFailedQueue, DirectExchange exchange) {
        return BindingBuilder.bind(videoFailedQueue).to(exchange).with(QUEUE_VIDEO_FAILED);
    }
}
```

Save to: `media-worker/src/main/java/com/tinniestudio/worker/config/RabbitConfig.java`

- [ ] **Step 5: Create `VideoProcessingConsumer.java` stub**

```java
package com.tinniestudio.worker.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.tinniestudio.worker.config.RabbitConfig;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoProcessingConsumer {

    @RabbitListener(queues = RabbitConfig.QUEUE_VIDEO_PROCESS)
    public void consume(String message) {
        // TODO (Batch 7): deserialize MediaProcessingJob and invoke VideoProcessingService
        log.info("Received video processing job: {}", message);
    }
}
```

Save to: `media-worker/src/main/java/com/tinniestudio/worker/consumer/VideoProcessingConsumer.java`

- [ ] **Step 6: Create `VideoProcessingService.java` stub**

```java
package com.tinniestudio.worker.processor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VideoProcessingService {

    // TODO (Batch 7): implement full FFmpeg HLS pipeline
    // Stages: VALIDATING → DOWNLOADING → PROBING → TRANSCODING → UPLOADING → FINALIZING → CLEANUP
    public void process(String videoAssetId) {
        log.info("Processing video asset: {}", videoAssetId);
        throw new UnsupportedOperationException("Media pipeline not yet implemented — coming in Batch 7");
    }
}
```

Save to: `media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java`

- [ ] **Step 7: Create `FFmpegRunner.java` stub**

```java
package com.tinniestudio.worker.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
public class FFmpegRunner {

    // TODO (Batch 7): implement HLS transcoding command builder and executor
    public void transcode(String inputPath, String outputDir, int width, int height,
                          String videoBitrate, String audioBitrate) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("FFmpeg transcoding not yet implemented — coming in Batch 7");
    }

    private void execute(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg exited with code: " + exitCode);
        }
    }
}
```

Save to: `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFmpegRunner.java`

- [ ] **Step 8: Create `FFprobeRunner.java` stub**

```java
package com.tinniestudio.worker.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FFprobeRunner {

    // TODO (Batch 7): run ffprobe -v quiet -print_format json -show_streams -show_format
    // and parse duration, width, height, codec, bitrate, hasAudio
    public VideoMetadata probe(String inputPath) {
        throw new UnsupportedOperationException("FFprobe not yet implemented — coming in Batch 7");
    }

    public record VideoMetadata(
            int durationSeconds,
            int width,
            int height,
            String codec,
            long bitrate,
            boolean hasAudio
    ) {}
}
```

Save to: `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFprobeRunner.java`

- [ ] **Step 9: Create `media-worker/src/main/resources/application.yml`**

```yaml
spring:
  application:
    name: tinniestudio-media-worker

  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:tinniestudio_db}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: none
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        jdbc:
          time_zone: UTC

  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}

  flyway:
    enabled: false

worker:
  processing:
    max-job-concurrency: 2
    temp-dir: /tmp/tinniestudio
    max-duration-seconds: 14400
  ffmpeg:
    path: /usr/bin/ffmpeg
    ffprobe-path: /usr/bin/ffprobe
    hls-segment-duration: 6

logging:
  level:
    com.tinniestudio: INFO
```

- [ ] **Step 10: Compile media-worker to verify scaffold is valid**

```bash
./gradlew :media-worker:compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Run media-worker tests (empty — just verifies JUnit wiring)**

```bash
./gradlew :media-worker:test
```

Expected: `BUILD SUCCESSFUL` (no tests to run yet).

- [ ] **Step 12: Commit media-worker scaffold**

```bash
git add media-worker/
git commit -m "feat: scaffold media-worker subproject (stubs — Batch 7 will implement FFmpeg pipeline)"
```

---

## Task 8: Create `media-worker/Dockerfile`

**Files:** `media-worker/Dockerfile`

- [ ] **Step 1: Create `media-worker/Dockerfile`**

```dockerfile
# ========================
# Build Stage
# ========================
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

# Copy Gradle wrapper and root build files (layer caching)
COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle .
COPY build.gradle .

# Copy worker build file
COPY media-worker/build.gradle media-worker/build.gradle

# Download dependencies
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
```

- [ ] **Step 2: Commit**

```bash
git add media-worker/Dockerfile
git commit -m "feat: add media-worker Dockerfile with FFmpeg installation"
```

---

## Task 9: Update `docker-compose.yml`

**Files:** `docker-compose.yml`

- [ ] **Step 1: Replace `docker-compose.yml` entirely**

```yaml
services:

  api-service:
    build:
      context: .
      dockerfile: api-service/Dockerfile
    container_name: tinniestudio-api
    restart: unless-stopped
    ports:
      - "8080:8080"
    env_file: .env
    environment:
      DB_HOST: db
      DB_PORT: 5432
      DB_NAME: tinniestudio_db
      DB_USER: postgres
      DB_PASSWORD: postgres
      REDIS_URL: redis://redis:6379
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
      RABBITMQ_USER: guest
      RABBITMQ_PASSWORD: guest
    depends_on:
      db:
        condition: service_healthy
      redis:
        condition: service_started
      rabbitmq:
        condition: service_healthy
    networks:
      - tinniestudio-network

  media-worker:
    build:
      context: .
      dockerfile: media-worker/Dockerfile
    container_name: tinniestudio-worker
    restart: unless-stopped
    env_file: .env
    environment:
      DB_HOST: db
      DB_PORT: 5432
      DB_NAME: tinniestudio_db
      DB_USER: postgres
      DB_PASSWORD: postgres
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
      RABBITMQ_USER: guest
      RABBITMQ_PASSWORD: guest
    depends_on:
      db:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    networks:
      - tinniestudio-network

  db:
    image: postgis/postgis:16-3.4
    container_name: tinniestudio-db
    restart: always
    environment:
      POSTGRES_DB: tinniestudio_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - db_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d tinniestudio_db"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - tinniestudio-network

  redis:
    image: redis:7-alpine
    container_name: tinniestudio-redis
    restart: always
    ports:
      - "6379:6379"
    networks:
      - tinniestudio-network

  rabbitmq:
    image: rabbitmq:3-management-alpine
    container_name: tinniestudio-rabbitmq
    restart: always
    ports:
      - "5672:5672"
      - "15672:15672"
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - tinniestudio-network

  minio:
    image: minio/minio:latest
    container_name: tinniestudio-minio
    restart: always
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio_data:/data
    networks:
      - tinniestudio-network

networks:
  tinniestudio-network:
    driver: bridge

volumes:
  db_data:
  minio_data:
```

- [ ] **Step 2: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: update docker-compose with full stack (api-service, media-worker, redis, rabbitmq, minio)"
```

---

## Task 10: Clean Up Maven Artifacts

**Files:** `pom.xml`, `Dockerfile` (root)

- [ ] **Step 1: Delete root `pom.xml`**

```bash
rm pom.xml
```

- [ ] **Step 2: Delete root `Dockerfile` (replaced by service-specific ones)**

```bash
rm Dockerfile
```

- [ ] **Step 3: Commit**

```bash
git add -u
git commit -m "chore: remove Maven pom.xml and root Dockerfile (replaced by Gradle + service Dockerfiles)"
```

---

## Task 11: Final Verification

- [ ] **Step 1: Full Gradle build — both subprojects**

```bash
./gradlew build -x test
```

Expected: `BUILD SUCCESSFUL` for both `:api-service:bootJar` and `:media-worker:bootJar`.

- [ ] **Step 2: Run api-service tests**

```bash
./gradlew :api-service:test
```

Expected: `BUILD SUCCESSFUL`. Integration tests that need a running DB may be skipped or fail — unit tests must pass.

- [ ] **Step 3: Verify the api-service JAR runs locally (without DB)**

```bash
java -jar api-service/build/libs/api-service-1.0.0.jar \
  --spring.profiles.active=dev \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/tinniestudio_db 2>&1 | head -30
```

Expected: Spring context starts, Flyway runs, app binds to port 8080. If DB isn't running, a connection error is fine — the key check is that the JAR itself is valid and the class `ApiServiceApplication` is found.

- [ ] **Step 4: Verify the media-worker JAR is valid**

```bash
java -jar media-worker/build/libs/media-worker-1.0.0.jar --help 2>&1 | head -5
```

Expected: JVM starts and Spring Boot banner appears.

- [ ] **Step 5: Verify docker-compose builds both images**

```bash
docker-compose build
```

Expected: both `tinniestudio-api` and `tinniestudio-worker` images built successfully.

- [ ] **Step 6: Update `task.md` — mark migration as complete**

In `task.md`, under section 5 "Immediate Task: Gradle Migration", tick all checkboxes and add a completion note.

- [ ] **Step 7: Final commit**

```bash
git add task.md
git commit -m "docs: mark Gradle migration complete in task.md"
```

---

## Self-Review

**Spec coverage check:**
- ✅ Gradle multi-project wrapper generated (Task 1)
- ✅ Root `settings.gradle` + `build.gradle` (Task 2)
- ✅ All existing source moved to `api-service/` (Task 3)
- ✅ All pom.xml deps translated to Gradle (Task 4)
- ✅ Package rename `com.lamastudio.backend` → `com.tinniestudio.api` (Task 5)
- ✅ Main class renamed `LamaStudioApplication` → `ApiServiceApplication` (Task 5)
- ✅ `application.yml` updated (app name, logging, JWT issuer) (Task 5)
- ✅ `api-service/Dockerfile` (multi-stage, Gradle build) (Task 6)
- ✅ `media-worker/` scaffold with all stub classes (Task 7)
- ✅ `media-worker/Dockerfile` with FFmpeg (Task 8)
- ✅ `docker-compose.yml` updated with all 6 services (Task 9)
- ✅ `pom.xml` and root `Dockerfile` removed (Task 10)
- ✅ Full build + Docker image verification (Task 11)

**Placeholder scan:** No TBD, no TODO without explicit Batch reference, all code blocks are complete.

**Type consistency:** `MediaWorkerApplication`, `VideoProcessingConsumer`, `VideoProcessingService`, `FFmpegRunner`, `FFprobeRunner`, `RabbitConfig` — all consistent across tasks.
