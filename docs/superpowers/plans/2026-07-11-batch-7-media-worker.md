# Batch 7 — Media Processing Worker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the async FFmpeg HLS transcoding pipeline in the `media-worker` service — consuming jobs from RabbitMQ, probing metadata via FFprobe, generating adaptive HLS output via FFmpeg, uploading results to MinIO, and updating VideoAsset status to READY.

**Architecture:** The media-worker is an independent Spring Boot app in the same Gradle multi-project build. It shares the PostgreSQL database with the api-service (Flyway migrations live in api-service only) but has its own JPA entity definitions. Processing is driven by RabbitMQ messages published by the api-service's `UploadService.completeSession()`. The worker implements a 10-stage pipeline with per-message TTL retry via `media.video.retry` → DLX → `media.video.process`. All FFmpeg/FFprobe interaction is behind a `ProcessRunner` interface for testability without requiring FFmpeg to be installed in the test environment.

**Tech Stack:** Spring Boot 3.3.5, Java 21, Spring AMQP (RabbitMQ), JPA (PostgreSQL, no Flyway in worker), AWS SDK v2 S3 (MinIO), FFmpeg/FFprobe via ProcessBuilder, Jackson for JSON parsing of FFprobe output, Mockito for unit tests.

---

## File Structure

**New (api-service):**
- `api-service/src/main/resources/db/migration/V29__add_processing_jobs.sql` — `processing_attempts` column + `processing_jobs` table

**New / Replace (media-worker):**

Config:
- `media-worker/src/main/java/com/tinniestudio/worker/config/WorkerProperties.java` — `@ConfigurationProperties("worker")`
- `media-worker/src/main/java/com/tinniestudio/worker/config/StorageProperties.java` — `@ConfigurationProperties("app.storage")`
- `media-worker/src/main/java/com/tinniestudio/worker/config/StorageConfig.java` — `@Bean S3Client`
- `media-worker/src/main/java/com/tinniestudio/worker/config/RabbitConfig.java` — **replace** stub with full config (retry queue, notifications queue, Jackson converter)
- `media-worker/src/main/resources/application.yml` — add `app.storage.*` entries

Entities:
- `media-worker/src/main/java/com/tinniestudio/worker/entity/VideoAsset.java` — maps `video_assets`, worker-relevant fields only
- `media-worker/src/main/java/com/tinniestudio/worker/entity/VideoVariant.java` — maps `video_variants`
- `media-worker/src/main/java/com/tinniestudio/worker/entity/ProcessingJob.java` — maps `processing_jobs`

Repositories:
- `media-worker/src/main/java/com/tinniestudio/worker/repository/VideoAssetRepository.java`
- `media-worker/src/main/java/com/tinniestudio/worker/repository/VideoVariantRepository.java`
- `media-worker/src/main/java/com/tinniestudio/worker/repository/ProcessingJobRepository.java`

DTOs:
- `media-worker/src/main/java/com/tinniestudio/worker/dto/MediaProcessingJobPayload.java` — inner payload matching api-service's record
- `media-worker/src/main/java/com/tinniestudio/worker/dto/ProcessingJobEnvelope.java` — outer QueueMessage wrapper (`messageId`, `type`, `attempt`, `payload`)

Storage:
- `media-worker/src/main/java/com/tinniestudio/worker/storage/WorkerStorageService.java` — interface: `download`, `upload`, `uploadDirectory`
- `media-worker/src/main/java/com/tinniestudio/worker/storage/MinioWorkerStorageService.java` — AWS SDK v2 S3 implementation

FFmpeg layer:
- `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/ProcessRunner.java` — interface: `String run(List<String> command) throws IOException, InterruptedException`
- `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/SystemProcessRunner.java` — real `ProcessBuilder` impl
- `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFprobeRunner.java` — **replace** stub: run FFprobe, parse JSON output
- `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFmpegRunner.java` — **replace** stub: HLS transcode + thumbnail
- `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/ResolutionLadder.java` — determine resolutions from source height
- `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/MasterPlaylistGenerator.java` — generate HLS master.m3u8 content

Processor:
- `media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java` — **replace** stub: full 10-stage pipeline

Consumer:
- `media-worker/src/main/java/com/tinniestudio/worker/consumer/VideoProcessingConsumer.java` — **replace** stub: deserialize envelope, idempotency, delegate
- `media-worker/src/main/java/com/tinniestudio/worker/consumer/RetryPublisher.java` — publish to retry queue with message TTL delay

Tests:
- `media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/FFprobeRunnerTest.java`
- `media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/FFmpegRunnerTest.java`
- `media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/ResolutionLadderTest.java`
- `media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/MasterPlaylistGeneratorTest.java`
- `media-worker/src/test/java/com/tinniestudio/worker/processor/VideoProcessingServiceTest.java`

---

## Task 1: V29 Migration — processing_jobs table

**Files:**
- Create: `api-service/src/main/resources/db/migration/V29__add_processing_jobs.sql`

- [ ] **Step 1: Write migration**

```sql
-- Add processing_attempts tracking to video_assets
ALTER TABLE video_assets
  ADD COLUMN IF NOT EXISTS processing_attempts INTEGER NOT NULL DEFAULT 0;

-- Processing jobs table for tracking per-attempt lifecycle
CREATE TABLE processing_jobs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_asset_id   UUID NOT NULL REFERENCES video_assets(id) ON DELETE CASCADE,
    job_id           VARCHAR(255) NOT NULL,
    status           VARCHAR(50)  NOT NULL DEFAULT 'VALIDATING',
    stage_started_at TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    error_message    TEXT,
    attempt          INTEGER NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uidx_processing_jobs_job_id ON processing_jobs(job_id);
CREATE INDEX idx_processing_jobs_video_asset_id ON processing_jobs(video_asset_id);
CREATE INDEX idx_processing_jobs_status ON processing_jobs(status);
```

- [ ] **Step 2: Verify the api-service compiles and migration is valid**

```bash
./gradlew :api-service:compileJava
```
Expected: BUILD SUCCESSFUL (no compile errors; Flyway runs at runtime, not build time)

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/resources/db/migration/V29__add_processing_jobs.sql
git commit -m "feat(worker): V29 add processing_jobs table and processing_attempts to video_assets"
```

---

## Task 2: Worker Config (WorkerProperties + StorageProperties + StorageConfig)

**Files:**
- Create: `media-worker/src/main/java/com/tinniestudio/worker/config/WorkerProperties.java`
- Create: `media-worker/src/main/java/com/tinniestudio/worker/config/StorageProperties.java`
- Create: `media-worker/src/main/java/com/tinniestudio/worker/config/StorageConfig.java`
- Modify: `media-worker/src/main/resources/application.yml`

- [ ] **Step 1: Create WorkerProperties**

```java
package com.tinniestudio.worker.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "worker")
@Getter @Setter
public class WorkerProperties {
    private Processing processing = new Processing();
    private Ffmpeg ffmpeg = new Ffmpeg();

    @Getter @Setter
    public static class Processing {
        private int maxJobConcurrency = 2;
        private String tempDir = "/tmp/tinniestudio";
        private int maxDurationSeconds = 14400;
    }

    @Getter @Setter
    public static class Ffmpeg {
        private String path = "/usr/bin/ffmpeg";
        private String ffprobePath = "/usr/bin/ffprobe";
        private int hlsSegmentDuration = 6;
    }
}
```

- [ ] **Step 2: Create StorageProperties**

```java
package com.tinniestudio.worker.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.storage")
@Getter @Setter
public class StorageProperties {
    private String endpoint;
    private String bucket;
    private String region = "us-east-1";
    private String accessKey;
    private String secretKey;
}
```

- [ ] **Step 3: Create StorageConfig**

```java
package com.tinniestudio.worker.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import java.net.URI;

@Configuration
@RequiredArgsConstructor
public class StorageConfig {

    private final StorageProperties props;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .region(Region.of(props.getRegion()))
                .forcePathStyle(true)
                .build();
    }
}
```

- [ ] **Step 4: Update application.yml — add app.storage.* entries**

Add after the existing `worker:` block:

```yaml
app:
  storage:
    endpoint: ${STORAGE_ENDPOINT:http://localhost:9000}
    bucket: ${STORAGE_BUCKET:tinniestudio}
    region: ${STORAGE_REGION:us-east-1}
    access-key: ${AWS_ACCESS_KEY_ID:minioadmin}
    secret-key: ${AWS_SECRET_ACCESS_KEY:minioadmin}
```

- [ ] **Step 5: Compile**

```bash
./gradlew :media-worker:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/config/ \
        media-worker/src/main/resources/application.yml
git commit -m "feat(worker): add WorkerProperties, StorageProperties, StorageConfig"
```

---

## Task 3: Worker Entities + Repositories

**Files:**
- Create: `media-worker/src/main/java/com/tinniestudio/worker/entity/VideoAsset.java`
- Create: `media-worker/src/main/java/com/tinniestudio/worker/entity/VideoVariant.java`
- Create: `media-worker/src/main/java/com/tinniestudio/worker/entity/ProcessingJob.java`
- Create: `media-worker/src/main/java/com/tinniestudio/worker/repository/VideoAssetRepository.java`
- Create: `media-worker/src/main/java/com/tinniestudio/worker/repository/VideoVariantRepository.java`
- Create: `media-worker/src/main/java/com/tinniestudio/worker/repository/ProcessingJobRepository.java`

- [ ] **Step 1: Create VideoAsset entity** (only fields the worker reads/writes)

```java
package com.tinniestudio.worker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "video_assets")
@Getter @Setter @NoArgsConstructor
public class VideoAsset {

    @Id
    private UUID id;

    private UUID contentId;
    private UUID uploadSessionId;

    @Column(name = "raw_storage_key", nullable = false)
    private String rawStorageKey;

    private String originalFilename;

    @Column(name = "manifest_url")
    private String manifestUrl;

    private Integer durationSeconds;
    private Integer width;
    private Integer height;
    private Long bitrate;
    private String codec;
    private Long fileSizeBytes;

    private String processingStatus;
    private String processingError;
    private int processingAttempts;

    private UUID uploadedBy;

    @UpdateTimestamp
    private Instant updatedAt;
}
```

- [ ] **Step 2: Create VideoVariant entity**

```java
package com.tinniestudio.worker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "video_variants")
@Getter @Setter @NoArgsConstructor
public class VideoVariant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "video_asset_id", nullable = false)
    private UUID videoAssetId;

    @Column(nullable = false)
    private String resolution;

    private Integer width;
    private Integer height;
    private Long bitrate;
    private String manifestKey;
    private Integer segmentCount;
}
```

- [ ] **Step 3: Create ProcessingJob entity**

```java
package com.tinniestudio.worker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processing_jobs")
@Getter @Setter @NoArgsConstructor
public class ProcessingJob {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "video_asset_id", nullable = false)
    private UUID videoAssetId;

    @Column(nullable = false, unique = true)
    private String jobId;

    @Column(nullable = false)
    private String status;

    private Instant stageStartedAt;
    private Instant completedAt;
    private String errorMessage;
    private int attempt;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
```

- [ ] **Step 4: Create VideoAssetRepository**

```java
package com.tinniestudio.worker.repository;

import com.tinniestudio.worker.entity.VideoAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface VideoAssetRepository extends JpaRepository<VideoAsset, UUID> {}
```

- [ ] **Step 5: Create VideoVariantRepository**

```java
package com.tinniestudio.worker.repository;

import com.tinniestudio.worker.entity.VideoVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface VideoVariantRepository extends JpaRepository<VideoVariant, UUID> {}
```

- [ ] **Step 6: Create ProcessingJobRepository**

```java
package com.tinniestudio.worker.repository;

import com.tinniestudio.worker.entity.ProcessingJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {
    boolean existsByJobIdAndStatus(String jobId, String status);
    Optional<ProcessingJob> findByJobId(String jobId);
}
```

- [ ] **Step 7: Compile**

```bash
./gradlew :media-worker:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/entity/ \
        media-worker/src/main/java/com/tinniestudio/worker/repository/
git commit -m "feat(worker): add VideoAsset, VideoVariant, ProcessingJob entities and repositories"
```

---

## Task 4: DTOs — MediaProcessingJobPayload + ProcessingJobEnvelope

**Files:**
- Create: `media-worker/src/main/java/com/tinniestudio/worker/dto/MediaProcessingJobPayload.java`
- Create: `media-worker/src/main/java/com/tinniestudio/worker/dto/ProcessingJobEnvelope.java`

The api-service publishes `QueueMessage<MediaProcessingJobPayload>` serialized as:
```json
{
  "messageId": "...",
  "type": "VIDEO_PROCESSING_JOB",
  "publishedAt": "...",
  "attempt": 1,
  "version": 1,
  "payload": { "jobId": "...", "videoAssetId": "...", "storageKey": "...", "uploadSessionId": "..." }
}
```

- [ ] **Step 1: Create MediaProcessingJobPayload**

```java
package com.tinniestudio.worker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@Getter @Setter @NoArgsConstructor
public class MediaProcessingJobPayload {
    private String jobId;
    private UUID videoAssetId;
    private String storageKey;
    private UUID uploadSessionId;
}
```

- [ ] **Step 2: Create ProcessingJobEnvelope**

```java
package com.tinniestudio.worker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@JsonIgnoreProperties(ignoreUnknown = true)
@Getter @Setter @NoArgsConstructor
public class ProcessingJobEnvelope {
    private String messageId;
    private String type;
    private int attempt;
    private MediaProcessingJobPayload payload;
}
```

- [ ] **Step 3: Compile**

```bash
./gradlew :media-worker:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/dto/
git commit -m "feat(worker): add ProcessingJobEnvelope and MediaProcessingJobPayload DTOs"
```

---

## Task 5: WorkerStorageService + MinioWorkerStorageService

**Files:**
- Create: `media-worker/src/main/java/com/tinniestudio/worker/storage/WorkerStorageService.java`
- Create: `media-worker/src/main/java/com/tinniestudio/worker/storage/MinioWorkerStorageService.java`

- [ ] **Step 1: Create WorkerStorageService interface**

```java
package com.tinniestudio.worker.storage;

import java.io.IOException;
import java.nio.file.Path;

public interface WorkerStorageService {
    /** Download object at storageKey to targetPath on local disk. */
    void download(String storageKey, Path targetPath) throws IOException;

    /** Upload a single local file to storageKey in the configured bucket. */
    void upload(String storageKey, Path sourcePath) throws IOException;

    /**
     * Upload all regular files under sourceDir recursively.
     * Each file's storage key = keyPrefix + "/" + relative path from sourceDir.
     */
    void uploadDirectory(String keyPrefix, Path sourceDir) throws IOException;
}
```

- [ ] **Step 2: Create MinioWorkerStorageService**

```java
package com.tinniestudio.worker.storage;

import com.tinniestudio.worker.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioWorkerStorageService implements WorkerStorageService {

    private final S3Client s3Client;
    private final StorageProperties storageProperties;

    @Override
    public void download(String storageKey, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        log.debug("Downloading {} → {}", storageKey, targetPath);
        s3Client.getObject(
            GetObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(storageKey)
                .build(),
            ResponseTransformer.toFile(targetPath)
        );
    }

    @Override
    public void upload(String storageKey, Path sourcePath) throws IOException {
        log.debug("Uploading {} → {}", sourcePath, storageKey);
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(storageKey)
                .build(),
            RequestBody.fromFile(sourcePath)
        );
    }

    @Override
    public void uploadDirectory(String keyPrefix, Path sourceDir) throws IOException {
        try (var stream = Files.walk(sourceDir)) {
            var files = stream.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                String relative = sourceDir.relativize(file).toString().replace('\\', '/');
                String key = keyPrefix + "/" + relative;
                upload(key, file);
            }
        }
    }
}
```

- [ ] **Step 3: Compile**

```bash
./gradlew :media-worker:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/storage/
git commit -m "feat(worker): add WorkerStorageService interface and MinioWorkerStorageService"
```

---

## Task 6: ProcessRunner + FFprobeRunner with TDD

**Files:**
- Create: `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/ProcessRunner.java`
- Create: `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/SystemProcessRunner.java`
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFprobeRunner.java`
- Create: `media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/FFprobeRunnerTest.java`

The `VideoMetadata` record already exists in the stub `FFprobeRunner.java`. It will be preserved.

- [ ] **Step 1: Create ProcessRunner interface**

```java
package com.tinniestudio.worker.ffmpeg;

import java.io.IOException;
import java.util.List;

public interface ProcessRunner {
    /**
     * Executes a command, waits for completion, returns stdout.
     * Throws RuntimeException if exit code != 0.
     */
    String run(List<String> command) throws IOException, InterruptedException;
}
```

- [ ] **Step 2: Create SystemProcessRunner**

```java
package com.tinniestudio.worker.ffmpeg;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Component
public class SystemProcessRunner implements ProcessRunner {

    @Override
    public String run(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (InputStream is = process.getInputStream()) {
            output = new String(is.readAllBytes());
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed (exit=" + exitCode + "): " + String.join(" ", command)
                + "\nOutput: " + output);
        }
        return output;
    }
}
```

- [ ] **Step 3: Write failing tests for FFprobeRunner**

```java
package com.tinniestudio.worker.ffmpeg;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FFprobeRunnerTest {

    @Mock
    private ProcessRunner processRunner;

    private FFprobeRunner runner;

    @BeforeEach
    void setUp() {
        runner = new FFprobeRunner(processRunner, "/usr/bin/ffprobe");
    }

    @Nested
    class probe {
        @Test
        void parsesMetadataFromFfprobeJsonOutput() throws Exception {
            String json = """
                {
                  "streams": [
                    {"codec_type":"video","codec_name":"h264","width":1920,"height":1080,
                     "bit_rate":"5000000"},
                    {"codec_type":"audio","codec_name":"aac"}
                  ],
                  "format": {"duration":"120.5","size":"75000000"}
                }
                """;
            when(processRunner.run(anyList())).thenReturn(json);

            FFprobeRunner.VideoMetadata meta = runner.probe("/tmp/test.mp4");

            assertThat(meta.durationSeconds()).isEqualTo(120);
            assertThat(meta.width()).isEqualTo(1920);
            assertThat(meta.height()).isEqualTo(1080);
            assertThat(meta.codec()).isEqualTo("h264");
            assertThat(meta.bitrate()).isEqualTo(5000000L);
            assertThat(meta.hasAudio()).isTrue();
        }

        @Test
        void throwsValidationExceptionWhenNoVideoStream() throws Exception {
            String json = """
                {
                  "streams": [{"codec_type":"audio","codec_name":"aac"}],
                  "format": {"duration":"30.0","size":"1000000"}
                }
                """;
            when(processRunner.run(anyList())).thenReturn(json);

            assertThatThrownBy(() -> runner.probe("/tmp/audio-only.mp3"))
                .isInstanceOf(FFprobeRunner.ValidationException.class)
                .hasMessageContaining("no video stream");
        }

        @Test
        void throwsValidationExceptionWhenNoAudioStream() throws Exception {
            String json = """
                {
                  "streams": [{"codec_type":"video","codec_name":"h264","width":1280,"height":720,
                               "bit_rate":"2800000"}],
                  "format": {"duration":"60.0","size":"21000000"}
                }
                """;
            when(processRunner.run(anyList())).thenReturn(json);

            assertThatThrownBy(() -> runner.probe("/tmp/silent.mp4"))
                .isInstanceOf(FFprobeRunner.ValidationException.class)
                .hasMessageContaining("no audio stream");
        }
    }
}
```

- [ ] **Step 4: Run tests — expect FAIL (UnsupportedOperationException from stub)**

```bash
./gradlew :media-worker:test --tests "*.FFprobeRunnerTest" 2>&1 | tail -20
```
Expected: FAILED with `UnsupportedOperationException` (stub throws it)

- [ ] **Step 5: Replace FFprobeRunner with real implementation**

```java
package com.tinniestudio.worker.ffmpeg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class FFprobeRunner {

    private final ProcessRunner processRunner;
    private final String ffprobePath;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FFprobeRunner(ProcessRunner processRunner,
                         @Value("${worker.ffmpeg.ffprobe-path:/usr/bin/ffprobe}") String ffprobePath) {
        this.processRunner = processRunner;
        this.ffprobePath = ffprobePath;
    }

    public VideoMetadata probe(String inputPath) throws Exception {
        List<String> command = List.of(
            ffprobePath, "-v", "quiet",
            "-print_format", "json",
            "-show_streams", "-show_format",
            inputPath
        );
        String output = processRunner.run(command);
        return parseOutput(output);
    }

    private VideoMetadata parseOutput(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode streams = root.get("streams");
        JsonNode format = root.get("format");

        JsonNode videoStream = null;
        boolean hasAudio = false;

        for (JsonNode stream : streams) {
            String codecType = stream.path("codec_type").asText();
            if ("video".equals(codecType) && videoStream == null) {
                videoStream = stream;
            } else if ("audio".equals(codecType)) {
                hasAudio = true;
            }
        }

        if (videoStream == null) {
            throw new ValidationException("Probe failed: no video stream found in " + json);
        }
        if (!hasAudio) {
            throw new ValidationException("Probe failed: no audio stream found in " + json);
        }

        int durationSeconds = (int) Double.parseDouble(format.path("duration").asText("0"));
        int width = videoStream.path("width").asInt();
        int height = videoStream.path("height").asInt();
        String codec = videoStream.path("codec_name").asText();
        long bitrate = videoStream.path("bit_rate").asLong(0);
        if (bitrate == 0) {
            bitrate = format.path("bit_rate").asLong(0);
        }

        return new VideoMetadata(durationSeconds, width, height, codec, bitrate, true);
    }

    public record VideoMetadata(
        int durationSeconds,
        int width,
        int height,
        String codec,
        long bitrate,
        boolean hasAudio
    ) {}

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }
}
```

- [ ] **Step 6: Run tests — expect PASS**

```bash
./gradlew :media-worker:test --tests "*.FFprobeRunnerTest" 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 3 tests passing

- [ ] **Step 7: Commit**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/ \
        media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/FFprobeRunnerTest.java
git commit -m "feat(worker): implement FFprobeRunner with ProcessRunner abstraction and tests"
```

---

## Task 7: ResolutionLadder + MasterPlaylistGenerator with TDD

**Files:**
- Create: `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/ResolutionLadder.java`
- Create: `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/MasterPlaylistGenerator.java`
- Create: `media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/ResolutionLadderTest.java`
- Create: `media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/MasterPlaylistGeneratorTest.java`

- [ ] **Step 1: Write failing test for ResolutionLadder**

```java
package com.tinniestudio.worker.ffmpeg;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ResolutionLadderTest {

    @Test
    void source1080pGeneratesAllFourResolutions() {
        List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(1080);
        assertThat(tiers).extracting(ResolutionLadder.Tier::label)
            .containsExactly("1080p", "720p", "480p", "360p");
    }

    @Test
    void source720pGeneratesThreeResolutions() {
        List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(720);
        assertThat(tiers).extracting(ResolutionLadder.Tier::label)
            .containsExactly("720p", "480p", "360p");
    }

    @Test
    void source480pGeneratesTwoResolutions() {
        List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(480);
        assertThat(tiers).extracting(ResolutionLadder.Tier::label)
            .containsExactly("480p", "360p");
    }

    @Test
    void sourceBelowThresholdGenerates360pOnly() {
        List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(360);
        assertThat(tiers).extracting(ResolutionLadder.Tier::label)
            .containsExactly("360p");
    }

    @Test
    void tier1080pHasCorrectDimensions() {
        ResolutionLadder.Tier tier = ResolutionLadder.forSourceHeight(1080).get(0);
        assertThat(tier.width()).isEqualTo(1920);
        assertThat(tier.height()).isEqualTo(1080);
        assertThat(tier.videoBitrate()).isEqualTo("5000k");
        assertThat(tier.audioBitrate()).isEqualTo("192k");
    }
}
```

- [ ] **Step 2: Run test — expect FAIL (class not found)**

```bash
./gradlew :media-worker:test --tests "*.ResolutionLadderTest" 2>&1 | tail -10
```
Expected: compilation error / test failure

- [ ] **Step 3: Create ResolutionLadder**

```java
package com.tinniestudio.worker.ffmpeg;

import java.util.ArrayList;
import java.util.List;

public final class ResolutionLadder {

    private static final List<Tier> ALL_TIERS = List.of(
        new Tier("1080p", 1920, 1080, "5000k", "192k", 5_000_000L),
        new Tier("720p",  1280,  720, "2800k", "128k", 2_800_000L),
        new Tier("480p",   854,  480, "1400k", "128k", 1_400_000L),
        new Tier("360p",   640,  360,  "800k",  "96k",   800_000L)
    );

    private ResolutionLadder() {}

    public static List<Tier> forSourceHeight(int sourceHeight) {
        List<Tier> result = new ArrayList<>();
        for (Tier tier : ALL_TIERS) {
            if (sourceHeight >= tier.height()) {
                result.add(tier);
            }
        }
        if (result.isEmpty()) {
            result.add(ALL_TIERS.get(ALL_TIERS.size() - 1));
        }
        return result;
    }

    public record Tier(
        String label,
        int width,
        int height,
        String videoBitrate,
        String audioBitrate,
        long bitrateValue
    ) {}
}
```

- [ ] **Step 4: Run ResolutionLadder tests — expect PASS**

```bash
./gradlew :media-worker:test --tests "*.ResolutionLadderTest" 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 5 tests passing

- [ ] **Step 5: Write failing test for MasterPlaylistGenerator**

```java
package com.tinniestudio.worker.ffmpeg;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MasterPlaylistGeneratorTest {

    @Test
    void generatesValidM3u8WithAllVariants() {
        List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(1080);
        String m3u8 = MasterPlaylistGenerator.generate(tiers);

        assertThat(m3u8).startsWith("#EXTM3U");
        assertThat(m3u8).contains("#EXT-X-VERSION:3");
        assertThat(m3u8).contains("BANDWIDTH=5000000,RESOLUTION=1920x1080");
        assertThat(m3u8).contains("1080p/playlist.m3u8");
        assertThat(m3u8).contains("BANDWIDTH=2800000,RESOLUTION=1280x720");
        assertThat(m3u8).contains("720p/playlist.m3u8");
        assertThat(m3u8).contains("360p/playlist.m3u8");
    }

    @Test
    void generatesM3u8ForSingleVariant() {
        List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(360);
        String m3u8 = MasterPlaylistGenerator.generate(tiers);

        assertThat(m3u8).contains("360p/playlist.m3u8");
        assertThat(m3u8).doesNotContain("720p/playlist.m3u8");
    }
}
```

- [ ] **Step 6: Run test — expect FAIL (class not found)**

```bash
./gradlew :media-worker:test --tests "*.MasterPlaylistGeneratorTest" 2>&1 | tail -10
```
Expected: compilation error

- [ ] **Step 7: Create MasterPlaylistGenerator**

```java
package com.tinniestudio.worker.ffmpeg;

import java.util.List;

public final class MasterPlaylistGenerator {

    private MasterPlaylistGenerator() {}

    public static String generate(List<ResolutionLadder.Tier> tiers) {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n\n");
        for (ResolutionLadder.Tier tier : tiers) {
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(tier.bitrateValue())
              .append(",RESOLUTION=").append(tier.width()).append("x").append(tier.height())
              .append("\n");
            sb.append(tier.label()).append("/playlist.m3u8\n");
        }
        return sb.toString();
    }
}
```

- [ ] **Step 8: Run all FFmpeg utility tests — expect PASS**

```bash
./gradlew :media-worker:test --tests "*.ResolutionLadderTest" --tests "*.MasterPlaylistGeneratorTest" 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/ResolutionLadder.java \
        media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/MasterPlaylistGenerator.java \
        media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/ResolutionLadderTest.java \
        media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/MasterPlaylistGeneratorTest.java
git commit -m "feat(worker): add ResolutionLadder and MasterPlaylistGenerator with tests"
```

---

## Task 8: FFmpegRunner with TDD

**Files:**
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFmpegRunner.java`
- Create: `media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/FFmpegRunnerTest.java`

- [ ] **Step 1: Write failing tests for FFmpegRunner**

```java
package com.tinniestudio.worker.ffmpeg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FFmpegRunnerTest {

    @Mock ProcessRunner processRunner;

    private FFmpegRunner runner;

    @BeforeEach
    void setUp() {
        runner = new FFmpegRunner(processRunner, "/usr/bin/ffmpeg", 6);
    }

    @Nested
    class transcode {
        @Test
        void buildsCorrectHlsCommandFor1080p() throws Exception {
            when(processRunner.run(anyList())).thenReturn("");

            runner.transcode("/tmp/input.mp4", "/tmp/jobs/abc/1080p",
                1920, 1080, "5000k", "192k");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processRunner).run(captor.capture());
            List<String> cmd = captor.getValue();

            assertThat(cmd).contains("/usr/bin/ffmpeg");
            assertThat(cmd).contains("-i", "/tmp/input.mp4");
            assertThat(cmd).contains("-b:v", "5000k");
            assertThat(cmd).contains("-b:a", "192k");
            assertThat(cmd).contains("-hls_time", "6");
            assertThat(cmd).contains("-hls_playlist_type", "vod");
            // output playlist path
            assertThat(cmd.get(cmd.size() - 1)).endsWith("playlist.m3u8");
        }

        @Test
        void buildsCorrectScaleFilterFor720p() throws Exception {
            when(processRunner.run(anyList())).thenReturn("");

            runner.transcode("/tmp/input.mp4", "/tmp/jobs/abc/720p",
                1280, 720, "2800k", "128k");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processRunner).run(captor.capture());
            List<String> cmd = captor.getValue();

            assertThat(cmd).contains("-vf", "scale=1280:720");
        }
    }

    @Nested
    class generateThumbnail {
        @Test
        void buildsCorrectThumbnailCommand() throws Exception {
            when(processRunner.run(anyList())).thenReturn("");

            runner.generateThumbnail("/tmp/input.mp4", "/tmp/jobs/abc/thumbnail.jpg");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processRunner).run(captor.capture());
            List<String> cmd = captor.getValue();

            assertThat(cmd).contains("/usr/bin/ffmpeg");
            assertThat(cmd).contains("-ss", "00:00:05");
            assertThat(cmd).contains("-vframes", "1");
            assertThat(cmd.get(cmd.size() - 1)).endsWith("thumbnail.jpg");
        }
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL (stub throws UnsupportedOperationException)**

```bash
./gradlew :media-worker:test --tests "*.FFmpegRunnerTest" 2>&1 | tail -10
```
Expected: FAILED

- [ ] **Step 3: Replace FFmpegRunner with real implementation**

```java
package com.tinniestudio.worker.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class FFmpegRunner {

    private final ProcessRunner processRunner;
    private final String ffmpegPath;
    private final int hlsSegmentDuration;

    public FFmpegRunner(ProcessRunner processRunner,
                        @Value("${worker.ffmpeg.path:/usr/bin/ffmpeg}") String ffmpegPath,
                        @Value("${worker.ffmpeg.hls-segment-duration:6}") int hlsSegmentDuration) {
        this.processRunner = processRunner;
        this.ffmpegPath = ffmpegPath;
        this.hlsSegmentDuration = hlsSegmentDuration;
    }

    public void transcode(String inputPath, String outputDir,
                          int width, int height,
                          String videoBitrate, String audioBitrate) throws Exception {
        String segmentPattern = outputDir + "/segment_%04d.ts";
        String playlistPath   = outputDir + "/playlist.m3u8";

        List<String> cmd = new ArrayList<>(List.of(
            ffmpegPath,
            "-i", inputPath,
            "-vf", "scale=" + width + ":" + height,
            "-c:v", "libx264",
            "-b:v", videoBitrate,
            "-maxrate", videoBitrate,
            "-bufsize", String.valueOf(parseBitrateKbps(videoBitrate) * 2) + "k",
            "-c:a", "aac",
            "-b:a", audioBitrate,
            "-hls_time", String.valueOf(hlsSegmentDuration),
            "-hls_playlist_type", "vod",
            "-hls_segment_filename", segmentPattern,
            playlistPath
        ));
        log.info("Transcoding → {} at {}x{} {}", outputDir, width, height, videoBitrate);
        processRunner.run(cmd);
    }

    public void generateThumbnail(String inputPath, String outputPath) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
            ffmpegPath,
            "-i", inputPath,
            "-ss", "00:00:05",
            "-vframes", "1",
            "-q:v", "2",
            outputPath
        ));
        log.info("Generating thumbnail → {}", outputPath);
        processRunner.run(cmd);
    }

    private int parseBitrateKbps(String bitrate) {
        return Integer.parseInt(bitrate.replace("k", ""));
    }
}
```

- [ ] **Step 4: Run FFmpegRunner tests — expect PASS**

```bash
./gradlew :media-worker:test --tests "*.FFmpegRunnerTest" 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 3 tests passing

- [ ] **Step 5: Commit**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFmpegRunner.java \
        media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/SystemProcessRunner.java \
        media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/ProcessRunner.java \
        media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/FFmpegRunnerTest.java
git commit -m "feat(worker): implement FFmpegRunner with HLS transcoding and thumbnail generation"
```

---

## Task 9: VideoProcessingService — Full Pipeline with TDD

**Files:**
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java`
- Create: `media-worker/src/test/java/com/tinniestudio/worker/processor/VideoProcessingServiceTest.java`

The `VideoProcessingService` orchestrates the 10-stage pipeline:
```
VALIDATING → DOWNLOADING → PROBING → TRANSCODING → THUMBNAIL → UPLOADING → FINALIZING → CLEANUP
```

- [ ] **Step 1: Write failing tests**

```java
package com.tinniestudio.worker.processor;

import com.tinniestudio.worker.config.WorkerProperties;
import com.tinniestudio.worker.dto.MediaProcessingJobPayload;
import com.tinniestudio.worker.entity.ProcessingJob;
import com.tinniestudio.worker.entity.VideoAsset;
import com.tinniestudio.worker.entity.VideoVariant;
import com.tinniestudio.worker.ffmpeg.*;
import com.tinniestudio.worker.repository.ProcessingJobRepository;
import com.tinniestudio.worker.repository.VideoAssetRepository;
import com.tinniestudio.worker.repository.VideoVariantRepository;
import com.tinniestudio.worker.storage.WorkerStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoProcessingServiceTest {

    @Mock VideoAssetRepository videoAssetRepo;
    @Mock VideoVariantRepository videoVariantRepo;
    @Mock ProcessingJobRepository processingJobRepo;
    @Mock FFprobeRunner ffprobeRunner;
    @Mock FFmpegRunner ffmpegRunner;
    @Mock WorkerStorageService storageService;
    @Mock org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    private WorkerProperties workerProperties;
    private VideoProcessingService service;

    @BeforeEach
    void setUp() {
        workerProperties = new WorkerProperties();
        workerProperties.getProcessing().setTempDir("/tmp/tinniestudio");
        workerProperties.getProcessing().setMaxDurationSeconds(14400);
        service = new VideoProcessingService(
            videoAssetRepo, videoVariantRepo, processingJobRepo,
            ffprobeRunner, ffmpegRunner, storageService, workerProperties, rabbitTemplate
        );
    }

    private MediaProcessingJobPayload buildPayload(UUID assetId) {
        MediaProcessingJobPayload p = new MediaProcessingJobPayload();
        p.setJobId("job-" + assetId);
        p.setVideoAssetId(assetId);
        p.setStorageKey("uploads/" + assetId + "/raw.mp4");
        return p;
    }

    private VideoAsset buildAsset(UUID id) {
        VideoAsset a = new VideoAsset();
        a.setId(id);
        a.setRawStorageKey("uploads/" + id + "/raw.mp4");
        a.setOriginalFilename("raw.mp4");
        a.setProcessingStatus("PENDING");
        a.setProcessingAttempts(0);
        return a;
    }

    @Nested
    class process {

        @Test
        void updatesVideoAssetToReadyOnSuccess() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE"))
                .thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                120, 1920, 1080, "h264", 5_000_000L, true);
            when(ffprobeRunner.probe(anyString())).thenReturn(meta);

            service.process(payload);

            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            verify(videoAssetRepo, atLeastOnce()).save(captor.capture());
            VideoAsset last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(last.getProcessingStatus()).isEqualTo("READY");
            assertThat(last.getManifestUrl()).contains("master.m3u8");
            assertThat(last.getDurationSeconds()).isEqualTo(120);
        }

        @Test
        void isIdempotentWhenJobAlreadyDone() throws Exception {
            UUID assetId = UUID.randomUUID();
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE"))
                .thenReturn(true);

            service.process(payload);

            verify(videoAssetRepo, never()).findById(any());
            verify(ffprobeRunner, never()).probe(any());
        }

        @Test
        void createsVideoVariantRecordsForEachResolution() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(anyString(), eq("DONE"))).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                60, 1280, 720, "h264", 2_800_000L, true);
            when(ffprobeRunner.probe(anyString())).thenReturn(meta);

            service.process(payload);

            // 720p source → 3 variants (720p, 480p, 360p)
            verify(videoVariantRepo, times(3)).save(any(VideoVariant.class));
        }

        @Test
        void setsFailedStatusOnNonRetryableError() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(anyString(), eq("DONE"))).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(ffprobeRunner.probe(anyString()))
                .thenThrow(new FFprobeRunner.ValidationException("no audio stream found"));

            service.process(payload);

            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            verify(videoAssetRepo, atLeastOnce()).save(captor.capture());
            VideoAsset last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(last.getProcessingStatus()).isEqualTo("FAILED");
            assertThat(last.getProcessingError()).contains("no audio stream");
        }
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL (stub throws UnsupportedOperationException)**

```bash
./gradlew :media-worker:test --tests "*.VideoProcessingServiceTest" 2>&1 | tail -20
```
Expected: FAILED

- [ ] **Step 3: Replace VideoProcessingService with full pipeline implementation**

```java
package com.tinniestudio.worker.processor;

import com.tinniestudio.worker.config.WorkerProperties;
import com.tinniestudio.worker.dto.MediaProcessingJobPayload;
import com.tinniestudio.worker.entity.ProcessingJob;
import com.tinniestudio.worker.entity.VideoAsset;
import com.tinniestudio.worker.entity.VideoVariant;
import com.tinniestudio.worker.ffmpeg.*;
import com.tinniestudio.worker.repository.ProcessingJobRepository;
import com.tinniestudio.worker.repository.VideoAssetRepository;
import com.tinniestudio.worker.repository.VideoVariantRepository;
import com.tinniestudio.worker.storage.WorkerStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoProcessingService {

    private final VideoAssetRepository videoAssetRepo;
    private final VideoVariantRepository videoVariantRepo;
    private final ProcessingJobRepository processingJobRepo;
    private final FFprobeRunner ffprobeRunner;
    private final FFmpegRunner ffmpegRunner;
    private final WorkerStorageService storageService;
    private final WorkerProperties workerProperties;
    private final RabbitTemplate rabbitTemplate;

    @Transactional
    public void process(MediaProcessingJobPayload payload) {
        String jobId = payload.getJobId();
        UUID videoAssetId = payload.getVideoAssetId();

        // Idempotency: skip if already completed
        if (processingJobRepo.existsByJobIdAndStatus(jobId, "DONE")) {
            log.info("Job {} already DONE, skipping", jobId);
            return;
        }

        VideoAsset asset = videoAssetRepo.findById(videoAssetId)
            .orElseThrow(() -> new IllegalStateException("VideoAsset not found: " + videoAssetId));

        ProcessingJob job = createJob(jobId, videoAssetId, asset.getProcessingAttempts() + 1);
        asset.setProcessingStatus("PROCESSING");
        asset.setProcessingAttempts(asset.getProcessingAttempts() + 1);
        videoAssetRepo.save(asset);

        Path jobDir = Path.of(workerProperties.getProcessing().getTempDir(), "jobs", jobId);

        try {
            // 2. DOWNLOADING
            updateJobStatus(job, "DOWNLOADING");
            Path inputFile = jobDir.resolve("input.mp4");
            Files.createDirectories(jobDir);
            storageService.download(payload.getStorageKey(), inputFile);

            // 3. PROBING
            updateJobStatus(job, "PROBING");
            FFprobeRunner.VideoMetadata meta = ffprobeRunner.probe(inputFile.toString());
            applyMetadataToAsset(asset, meta);

            // 4. RESOLUTION LADDER PLANNING
            List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(meta.height());

            // 5. TRANSCODING
            updateJobStatus(job, "TRANSCODING");
            for (ResolutionLadder.Tier tier : tiers) {
                Path tierDir = jobDir.resolve(tier.label());
                Files.createDirectories(tierDir);
                ffmpegRunner.transcode(inputFile.toString(), tierDir.toString(),
                    tier.width(), tier.height(), tier.videoBitrate(), tier.audioBitrate());
            }

            // 6. THUMBNAIL GENERATION
            updateJobStatus(job, "THUMBNAIL_GENERATION");
            Path thumbnailPath = jobDir.resolve("thumbnail.jpg");
            ffmpegRunner.generateThumbnail(inputFile.toString(), thumbnailPath.toString());

            // 7. UPLOADING_OUTPUT
            updateJobStatus(job, "UPLOADING_OUTPUT");
            String assetPrefix = "processed/" + videoAssetId;
            for (ResolutionLadder.Tier tier : tiers) {
                storageService.uploadDirectory(
                    assetPrefix + "/" + tier.label(),
                    jobDir.resolve(tier.label())
                );
            }
            String masterKey = assetPrefix + "/master.m3u8";
            String masterContent = MasterPlaylistGenerator.generate(tiers);
            Path masterPath = jobDir.resolve("master.m3u8");
            Files.writeString(masterPath, masterContent);
            storageService.upload(masterKey, masterPath);

            String thumbnailKey = "thumbnails/" + videoAssetId + "/poster.jpg";
            storageService.upload(thumbnailKey, thumbnailPath);

            // 8. FINALIZING
            updateJobStatus(job, "FINALIZING");
            for (ResolutionLadder.Tier tier : tiers) {
                VideoVariant variant = buildVariant(videoAssetId, tier, assetPrefix);
                videoVariantRepo.save(variant);
            }
            asset.setManifestUrl(masterKey);
            asset.setProcessingStatus("READY");
            videoAssetRepo.save(asset);

            job.setStatus("DONE");
            job.setCompletedAt(Instant.now());
            processingJobRepo.save(job);

            // Notify downstream (best-effort; notification service is built in Batch 12)
            rabbitTemplate.convertAndSend(
                com.tinniestudio.worker.config.RabbitConfig.EXCHANGE,
                com.tinniestudio.worker.config.RabbitConfig.QUEUE_NOTIFICATIONS,
                java.util.Map.of(
                    "type", "CONTENT_PROCESSED",
                    "videoAssetId", videoAssetId.toString(),
                    "contentId", asset.getContentId() != null ? asset.getContentId().toString() : null,
                    "status", "READY"
                )
            );

            log.info("VideoAsset {} processed successfully", videoAssetId);

        } catch (FFprobeRunner.ValidationException e) {
            // Non-retryable: corrupt file, no audio/video stream
            log.error("Non-retryable validation failure for asset {}: {}", videoAssetId, e.getMessage());
            markFailed(asset, job, e.getMessage());
        } catch (Exception e) {
            // Retryable error — caller (consumer) decides whether to retry or DLQ
            log.error("Retryable error processing asset {}: {}", videoAssetId, e.getMessage());
            markFailed(asset, job, e.getMessage());
            throw new RetryableProcessingException(e.getMessage(), e);
        } finally {
            cleanup(jobDir);
        }
    }

    private ProcessingJob createJob(String jobId, UUID videoAssetId, int attempt) {
        ProcessingJob job = new ProcessingJob();
        job.setJobId(jobId);
        job.setVideoAssetId(videoAssetId);
        job.setStatus("VALIDATING");
        job.setAttempt(attempt);
        job.setStageStartedAt(Instant.now());
        return processingJobRepo.save(job);
    }

    private void updateJobStatus(ProcessingJob job, String status) {
        job.setStatus(status);
        job.setStageStartedAt(Instant.now());
        processingJobRepo.save(job);
    }

    private void applyMetadataToAsset(VideoAsset asset, FFprobeRunner.VideoMetadata meta) {
        asset.setDurationSeconds(meta.durationSeconds());
        asset.setWidth(meta.width());
        asset.setHeight(meta.height());
        asset.setCodec(meta.codec());
        asset.setBitrate(meta.bitrate());
    }

    private VideoVariant buildVariant(UUID videoAssetId, ResolutionLadder.Tier tier, String assetPrefix) {
        VideoVariant v = new VideoVariant();
        v.setVideoAssetId(videoAssetId);
        v.setResolution(tier.label());
        v.setWidth(tier.width());
        v.setHeight(tier.height());
        v.setBitrate(tier.bitrateValue());
        v.setManifestKey(assetPrefix + "/" + tier.label() + "/playlist.m3u8");
        return v;
    }

    private void markFailed(VideoAsset asset, ProcessingJob job, String error) {
        asset.setProcessingStatus("FAILED");
        asset.setProcessingError(error);
        videoAssetRepo.save(asset);

        job.setStatus("FAILED");
        job.setErrorMessage(error);
        job.setCompletedAt(Instant.now());
        processingJobRepo.save(job);
    }

    private void cleanup(Path jobDir) {
        try {
            if (Files.exists(jobDir)) {
                try (var walk = Files.walk(jobDir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); }
                            catch (IOException ignored) {}
                        });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to clean up temp dir {}: {}", jobDir, e.getMessage());
        }
    }

    public static class RetryableProcessingException extends RuntimeException {
        public RetryableProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

- [ ] **Step 4: Run VideoProcessingService tests — expect PASS**

```bash
./gradlew :media-worker:test --tests "*.VideoProcessingServiceTest" 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL, 4 tests passing

- [ ] **Step 5: Commit**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java \
        media-worker/src/test/java/com/tinniestudio/worker/processor/VideoProcessingServiceTest.java
git commit -m "feat(worker): implement full HLS pipeline in VideoProcessingService with TDD"
```

---

## Task 10: RabbitConfig Update + RetryPublisher + VideoProcessingConsumer

**Files:**
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/config/RabbitConfig.java`
- Create: `media-worker/src/main/java/com/tinniestudio/worker/consumer/RetryPublisher.java`
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/consumer/VideoProcessingConsumer.java`

The existing `RabbitConfig` stub is missing:
- `media.video.retry` queue (DLX → process, for delayed retry via message TTL)
- Jackson message converter (so `@RabbitListener` auto-deserializes JSON to `ProcessingJobEnvelope`)

- [ ] **Step 1: Replace RabbitConfig with complete version**

```java
package com.tinniestudio.worker.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE             = "tinniestudio.direct";
    public static final String QUEUE_VIDEO_PROCESS  = "media.video.process";
    public static final String QUEUE_VIDEO_RETRY    = "media.video.retry";
    public static final String QUEUE_VIDEO_FAILED   = "media.video.failed";
    public static final String QUEUE_NOTIFICATIONS  = "notifications.send";

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

    /** TTL retry queue: message expires → dead-lettered back to process queue. */
    @Bean
    public Queue videoRetryQueue() {
        return QueueBuilder.durable(QUEUE_VIDEO_RETRY)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_VIDEO_PROCESS)
                .build();
    }

    @Bean
    public Queue videoFailedQueue() {
        return QueueBuilder.durable(QUEUE_VIDEO_FAILED).build();
    }

    @Bean
    public Queue notificationsQueue() {
        return QueueBuilder.durable(QUEUE_NOTIFICATIONS).build();
    }

    @Bean
    public Binding videoProcessBinding(Queue videoProcessQueue, DirectExchange exchange) {
        return BindingBuilder.bind(videoProcessQueue).to(exchange).with(QUEUE_VIDEO_PROCESS);
    }

    @Bean
    public Binding videoRetryBinding(Queue videoRetryQueue, DirectExchange exchange) {
        return BindingBuilder.bind(videoRetryQueue).to(exchange).with(QUEUE_VIDEO_RETRY);
    }

    @Bean
    public Binding videoFailedBinding(Queue videoFailedQueue, DirectExchange exchange) {
        return BindingBuilder.bind(videoFailedQueue).to(exchange).with(QUEUE_VIDEO_FAILED);
    }

    @Bean
    public Binding notificationsBinding(Queue notificationsQueue, DirectExchange exchange) {
        return BindingBuilder.bind(notificationsQueue).to(exchange).with(QUEUE_NOTIFICATIONS);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
```

- [ ] **Step 2: Create RetryPublisher**

The retry delay strategy:
- Attempt 1 failed → retry in 1 minute (attempt 2)
- Attempt 2 failed → retry in 5 minutes (attempt 3)
- Attempt 3 failed → throw to let the DLQ take it (routes to `media.video.failed`)

```java
package com.tinniestudio.worker.consumer;

import com.tinniestudio.worker.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetryPublisher {

    private static final long[] DELAY_MS = {
        60_000L,   // attempt 1 → retry after 1 min
        300_000L   // attempt 2 → retry after 5 min
    };
    public static final int MAX_ATTEMPTS = 3;

    private final RabbitTemplate rabbitTemplate;

    /**
     * Publishes message to retry queue with TTL delay based on current attempt count.
     * @param envelope the envelope to retry (attempt field determines delay)
     * @throws MaxAttemptsExceededException when attempt >= MAX_ATTEMPTS
     */
    public void retryOrFail(Object envelope, int currentAttempt) {
        if (currentAttempt >= MAX_ATTEMPTS) {
            throw new MaxAttemptsExceededException(
                "Max attempts (" + MAX_ATTEMPTS + ") exceeded, routing to DLQ");
        }
        long delayMs = DELAY_MS[currentAttempt - 1];
        log.info("Scheduling retry attempt {} in {}ms", currentAttempt + 1, delayMs);
        rabbitTemplate.convertAndSend(
            RabbitConfig.EXCHANGE,
            RabbitConfig.QUEUE_VIDEO_RETRY,
            envelope,
            msg -> {
                msg.getMessageProperties().setExpiration(String.valueOf(delayMs));
                return msg;
            }
        );
    }

    public static class MaxAttemptsExceededException extends RuntimeException {
        public MaxAttemptsExceededException(String message) { super(message); }
    }
}
```

- [ ] **Step 3: Replace VideoProcessingConsumer with full implementation**

```java
package com.tinniestudio.worker.consumer;

import com.tinniestudio.worker.config.RabbitConfig;
import com.tinniestudio.worker.dto.ProcessingJobEnvelope;
import com.tinniestudio.worker.processor.VideoProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoProcessingConsumer {

    private final VideoProcessingService processingService;
    private final RetryPublisher retryPublisher;

    @RabbitListener(queues = RabbitConfig.QUEUE_VIDEO_PROCESS, concurrency = "1-2")
    public void consume(ProcessingJobEnvelope envelope) {
        if (envelope.getPayload() == null) {
            log.error("Received malformed message with null payload, discarding");
            return;
        }
        String jobId = envelope.getPayload().getJobId();
        int attempt = Math.max(envelope.getAttempt(), 1);
        log.info("Consuming job={} attempt={}", jobId, attempt);

        try {
            processingService.process(envelope.getPayload());
        } catch (VideoProcessingService.RetryableProcessingException e) {
            log.warn("Retryable failure for job={} attempt={}: {}", jobId, attempt, e.getMessage());
            try {
                retryPublisher.retryOrFail(envelope, attempt);
            } catch (RetryPublisher.MaxAttemptsExceededException ex) {
                log.error("Job={} exhausted retries, routing to failed queue", jobId);
                // Re-throw so AMQP nacks the message → DLX routes to media.video.failed
                throw ex;
            }
        }
    }
}
```

- [ ] **Step 4: Compile**

```bash
./gradlew :media-worker:compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/config/RabbitConfig.java \
        media-worker/src/main/java/com/tinniestudio/worker/consumer/RetryPublisher.java \
        media-worker/src/main/java/com/tinniestudio/worker/consumer/VideoProcessingConsumer.java
git commit -m "feat(worker): implement RabbitConfig, RetryPublisher, VideoProcessingConsumer"
```

---

## Task 11: Startup Validation + Full Build Verification

**Files:**
- Create: `media-worker/src/main/java/com/tinniestudio/worker/config/StartupValidator.java`

- [ ] **Step 1: Create StartupValidator**

Validates that FFmpeg tools are available on startup, fails fast before accepting any RabbitMQ messages.

```java
package com.tinniestudio.worker.config;

import com.tinniestudio.worker.ffmpeg.ProcessRunner;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupValidator {

    private final ProcessRunner processRunner;
    private final WorkerProperties workerProperties;

    @PostConstruct
    public void validateTools() {
        validateCommand(workerProperties.getFfmpeg().getPath(), "-version");
        validateCommand(workerProperties.getFfmpeg().getFfprobePath(), "-version");
        log.info("FFmpeg tools validated successfully");
    }

    private void validateCommand(String binaryPath, String... args) {
        try {
            List<String> cmd = new java.util.ArrayList<>();
            cmd.add(binaryPath);
            cmd.addAll(List.of(args));
            processRunner.run(cmd);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Required tool not found: " + binaryPath + " — " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 2: Run all media-worker unit tests**

```bash
./gradlew :media-worker:test 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL (all unit tests pass; no integration tests requiring FFmpeg at runtime)

Note: `StartupValidator.@PostConstruct` only runs when the Spring context starts. Unit tests that mock `ProcessRunner` won't trigger it. Integration tests (not in this batch) would test the full startup with real FFmpeg.

- [ ] **Step 3: Run full media-worker build including JAR creation**

```bash
./gradlew :media-worker:build -x test 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL with JAR in `media-worker/build/libs/`

- [ ] **Step 4: Confirm api-service still compiles clean**

```bash
./gradlew :api-service:compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run Batch 6 unit tests one final time to confirm no regressions**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.upload.*" 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit StartupValidator**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/config/StartupValidator.java
git commit -m "feat(worker): add StartupValidator to verify FFmpeg tools on startup"
```

---

## Completion Gates

All gates from BATCH-PLAN.md §7 (adapted for unit-test context):

| Gate | Verified by |
|------|------------|
| FFprobeRunner parses metadata from JSON output | `FFprobeRunnerTest` (3 tests) |
| FFprobeRunner throws ValidationException for missing streams | `FFprobeRunnerTest` |
| FFmpegRunner builds correct HLS transcode command | `FFmpegRunnerTest` (2 tests) |
| FFmpegRunner builds correct thumbnail command | `FFmpegRunnerTest` (1 test) |
| ResolutionLadder selects correct tiers for source height | `ResolutionLadderTest` (5 tests) |
| MasterPlaylistGenerator produces valid m3u8 | `MasterPlaylistGeneratorTest` (2 tests) |
| VideoProcessingService sets status=READY on success | `VideoProcessingServiceTest` |
| VideoProcessingService is idempotent (DONE job skipped) | `VideoProcessingServiceTest` |
| VideoProcessingService creates correct VideoVariant count | `VideoProcessingServiceTest` |
| VideoProcessingService sets FAILED on ValidationException | `VideoProcessingServiceTest` |
| media-worker JAR builds clean | `./gradlew :media-worker:build -x test` |
| api-service Batch 6 tests unaffected | `./gradlew :api-service:test --tests "*.upload.*"` |
