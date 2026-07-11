# Batch 6 — Upload Session System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the secure direct-to-bucket upload pipeline: presigned PUT URL generation, session tracking, upload completion verification, VideoAsset creation, and RabbitMQ job dispatch.

**Architecture:** Partners request a presigned PUT URL from the API, upload directly to MinIO/S3 (no backend involvement during transfer), then call `/complete` to verify the object landed and trigger downstream processing. The API never handles raw video bytes.

**Tech Stack:** Spring Boot 3.3.5, JPA + Flyway, MinIO (AWS SDK v2 via existing `StorageService`), RabbitMQ (`QueuePublisher`), Redis (session state cache), Java 21 records for DTOs.

---

## Context

**What already exists — do NOT rebuild:**
- `StorageService` interface with `generateUploadUrl(key, mimeType, maxBytes, ttl)`, `objectExists(key)`, `deleteObject(key)`, `uploadFile(key, bytes, contentType)` — all fully implemented in `MinioStorageService`
- `PresignedUploadResult(uploadUrl, storageKey, expiresAt)` record
- `UploadSession.java` entity (`upload_sessions` table — but NO migration yet, entity only)
- `VideoAsset.java` entity (`video_assets` table — but NO migration yet)
- `VideoVariant.java`, `Subtitle.java` entities (no migrations yet)
- All enums in `DomainEnums`: `UploadType` (RAW_VIDEO, TRAILER, THUMBNAIL, SUBTITLE, VIDEO), `TargetEntityType` (CONTENT, SEASON, EPISODE), `UploadStatus` (PENDING, UPLOADING, COMPLETED, EXPIRED, FAILED), `ProcessingStatus` (PENDING, PROCESSING, READY, FAILED), `VideoAssetType` (MAIN_VIDEO, TRAILER)
- `QueuePublisher` interface with `publish(queue, type, payload)` — wire to `media.video.process`
- `MediaFile.java` entity does NOT exist yet — must be created
- Next Flyway migration: **V25**

**Upload type allowlists (per BATCH-PLAN spec):**
| UploadType | Allowed MIME types | Max bytes |
|------------|-------------------|-----------|
| RAW_VIDEO | video/mp4, video/quicktime, video/x-matroska | 10 GB |
| TRAILER | video/mp4, video/quicktime | 2 GB |
| THUMBNAIL | image/jpeg, image/png, image/webp | 10 MB |
| SUBTITLE | text/vtt, application/x-subrip | 5 MB |

**Storage key patterns:**
- RAW_VIDEO: `raw/{newUUID}/original.{ext}`
- TRAILER: `raw/trailers/{newUUID}/original.{ext}`
- THUMBNAIL: `thumbnails/{targetEntityId}/{newUUID}.{ext}`
- SUBTITLE: `subtitles/{targetEntityId}/{newUUID}.{ext}`

**Queue:** `media.video.process` — publish after RAW_VIDEO or TRAILER upload completes.

---

## File Map

**Create:**
- `api-service/src/main/resources/db/migration/V25__add_upload_sessions.sql`
- `api-service/src/main/resources/db/migration/V26__add_media_files.sql`
- `api-service/src/main/resources/db/migration/V27__add_video_assets.sql`
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/MediaFile.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/upload/config/UploadConfig.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/CreateUploadSessionRequest.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/UploadSessionResponse.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/UploadStatusResponse.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/MediaProcessingJobPayload.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/upload/repository/UploadSessionRepository.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/upload/repository/MediaFileRepository.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/upload/repository/VideoAssetRepository.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/upload/service/UploadService.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/upload/controller/UploadController.java`
- `api-service/src/test/java/com/tinniestudio/api/modules/upload/service/UploadServiceTest.java`

**Modify:**
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/UploadSession.java` — add `presignedUrl` field + `uploadSessionId` missing field
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/VideoAsset.java` — add `uploadSessionId UUID` field

---

## Task 1: DB Migrations V25–V27

**Files:**
- Create: `api-service/src/main/resources/db/migration/V25__add_upload_sessions.sql`
- Create: `api-service/src/main/resources/db/migration/V26__add_media_files.sql`
- Create: `api-service/src/main/resources/db/migration/V27__add_video_assets.sql`

- [ ] **Step 1: Create V25__add_upload_sessions.sql**

```sql
CREATE TABLE upload_sessions (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL REFERENCES users(id),
    upload_type           VARCHAR(50) NOT NULL,
    target_entity_type    VARCHAR(50),
    target_entity_id      UUID,
    storage_key           VARCHAR(500) NOT NULL,
    original_filename     VARCHAR(255),
    mime_type             VARCHAR(100),
    expected_max_size_bytes BIGINT,
    upload_status         VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    presigned_url         TEXT,
    expires_at            TIMESTAMPTZ NOT NULL,
    completed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_upload_sessions_user_id ON upload_sessions(user_id);
CREATE INDEX idx_upload_sessions_status  ON upload_sessions(upload_status);
```

- [ ] **Step 2: Create V26__add_media_files.sql**

```sql
CREATE TABLE media_files (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    upload_session_id    UUID REFERENCES upload_sessions(id),
    user_id              UUID NOT NULL REFERENCES users(id),
    file_type            VARCHAR(50),
    storage_key          VARCHAR(500) NOT NULL,
    original_filename    VARCHAR(255),
    mime_type            VARCHAR(100),
    file_size_bytes      BIGINT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_media_files_upload_session_id ON media_files(upload_session_id);
CREATE INDEX idx_media_files_user_id ON media_files(user_id);
```

- [ ] **Step 3: Create V27__add_video_assets.sql**

```sql
CREATE TABLE video_assets (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content_id           UUID REFERENCES contents(id) ON DELETE CASCADE,
    season_id            UUID REFERENCES seasons(id) ON DELETE CASCADE,
    episode_id           UUID REFERENCES episodes(id) ON DELETE CASCADE,
    upload_session_id    UUID REFERENCES upload_sessions(id),
    asset_type           VARCHAR(50) NOT NULL,
    original_filename    VARCHAR(255),
    source_format        VARCHAR(50),
    raw_storage_key      VARCHAR(500) NOT NULL,
    manifest_url         VARCHAR(500),
    duration_seconds     INTEGER,
    width                INTEGER,
    height               INTEGER,
    bitrate              BIGINT,
    codec                VARCHAR(100),
    file_size_bytes      BIGINT,
    processing_status    VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    processing_error     TEXT,
    uploaded_by          UUID NOT NULL REFERENCES users(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE video_variants (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_asset_id   UUID NOT NULL REFERENCES video_assets(id) ON DELETE CASCADE,
    resolution       VARCHAR(20) NOT NULL,
    width            INTEGER,
    height           INTEGER,
    bitrate          BIGINT,
    manifest_key     VARCHAR(500),
    segment_count    INTEGER,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_video_assets_content_id  ON video_assets(content_id);
CREATE INDEX idx_video_assets_episode_id  ON video_assets(episode_id);
CREATE INDEX idx_video_assets_status      ON video_assets(processing_status);
CREATE INDEX idx_video_variants_asset_id  ON video_variants(video_asset_id);
```

- [ ] **Step 4: Verify migrations compile with Flyway (no DB needed — check file syntax)**

Compile: `./gradlew :api-service:compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/resources/db/migration/
git commit -m "feat(upload): add V25-V27 migrations for upload_sessions, media_files, video_assets"
```

---

## Task 2: Entity Updates + MediaFile Entity

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/UploadSession.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/VideoAsset.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/shared/entity/MediaFile.java`

- [ ] **Step 1: Add `presignedUrl` field to UploadSession.java**

Read the file first. Add this field (stored for debugging, not in response):
```java
@Column(columnDefinition = "TEXT")
private String presignedUrl;
```

Also verify the `storageKey` column mapping — the DB column is `raw_storage_key` in video_assets but `storage_key` in upload_sessions. UploadSession already maps correctly to `storage_key`.

- [ ] **Step 2: Add `uploadSessionId` to VideoAsset.java**

Read VideoAsset.java. Add:
```java
private UUID uploadSessionId;
```
Also verify `storageKey` maps to `raw_storage_key` column (add `@Column(name = "raw_storage_key")` if not present).

- [ ] **Step 3: Create MediaFile.java**

```java
package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;

@Entity
@Table(name = "media_files")
@Getter
@Setter
@NoArgsConstructor
public class MediaFile extends BaseEntity {

    @Column(nullable = false)
    private UUID userId;

    private UUID uploadSessionId;

    private String fileType;

    @Column(nullable = false)
    private String storageKey;

    private String originalFilename;

    private String mimeType;

    private Long fileSizeBytes;
}
```

- [ ] **Step 4: Compile check**

`./gradlew :api-service:compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/
git commit -m "feat(upload): add MediaFile entity, add presignedUrl to UploadSession, add uploadSessionId to VideoAsset"
```

---

## Task 3: UploadConfig + Repositories

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/upload/config/UploadConfig.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/upload/repository/UploadSessionRepository.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/upload/repository/MediaFileRepository.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/upload/repository/VideoAssetRepository.java`

- [ ] **Step 1: Create UploadConfig.java**

```java
package com.tinniestudio.api.modules.upload.config;

import com.tinniestudio.api.shared.entity.DomainEnums.UploadType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Component
public class UploadConfig {

    private static final Map<UploadType, Set<String>> ALLOWED_MIME_TYPES = Map.of(
        UploadType.RAW_VIDEO, Set.of("video/mp4", "video/quicktime", "video/x-matroska"),
        UploadType.TRAILER,   Set.of("video/mp4", "video/quicktime"),
        UploadType.THUMBNAIL, Set.of("image/jpeg", "image/png", "image/webp"),
        UploadType.SUBTITLE,  Set.of("text/vtt", "application/x-subrip")
    );

    // bytes
    private static final Map<UploadType, Long> MAX_BYTES = Map.of(
        UploadType.RAW_VIDEO, 10L * 1024 * 1024 * 1024,  // 10 GB
        UploadType.TRAILER,    2L * 1024 * 1024 * 1024,   // 2 GB
        UploadType.THUMBNAIL,        10L * 1024 * 1024,   // 10 MB
        UploadType.SUBTITLE,          5L * 1024 * 1024    // 5 MB
    );

    public static final Duration PRESIGNED_URL_TTL = Duration.ofMinutes(30);
    public static final String VIDEO_PROCESS_QUEUE = "media.video.process";

    public boolean isMimeTypeAllowed(UploadType type, String mimeType) {
        Set<String> allowed = ALLOWED_MIME_TYPES.get(type);
        return allowed != null && allowed.contains(mimeType);
    }

    public long getMaxBytes(UploadType type) {
        return MAX_BYTES.getOrDefault(type, 0L);
    }
}
```

- [ ] **Step 2: Create UploadSessionRepository.java**

```java
package com.tinniestudio.api.modules.upload.repository;

import com.tinniestudio.api.shared.entity.UploadSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {}
```

- [ ] **Step 3: Create MediaFileRepository.java**

```java
package com.tinniestudio.api.modules.upload.repository;

import com.tinniestudio.api.shared.entity.MediaFile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {}
```

- [ ] **Step 4: Create VideoAssetRepository.java**

```java
package com.tinniestudio.api.modules.upload.repository;

import com.tinniestudio.api.shared.entity.VideoAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface VideoAssetRepository extends JpaRepository<VideoAsset, UUID> {
    Optional<VideoAsset> findByUploadSessionId(UUID uploadSessionId);
}
```

- [ ] **Step 5: Compile check**

`./gradlew :api-service:compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/upload/
git commit -m "feat(upload): add UploadConfig, repositories (UploadSession, MediaFile, VideoAsset)"
```

---

## Task 4: DTOs + UploadService

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/CreateUploadSessionRequest.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/UploadSessionResponse.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/UploadStatusResponse.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/MediaProcessingJobPayload.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/upload/service/UploadService.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/upload/service/UploadServiceTest.java`

- [ ] **Step 1: Write the failing tests first**

```java
package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.upload.config.UploadConfig;
import com.tinniestudio.api.modules.upload.dto.CreateUploadSessionRequest;
import com.tinniestudio.api.modules.upload.dto.UploadSessionResponse;
import com.tinniestudio.api.modules.upload.dto.UploadStatusResponse;
import com.tinniestudio.api.modules.upload.repository.MediaFileRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.entity.UploadSession;
import com.tinniestudio.api.shared.queue.QueuePublisher;
import com.tinniestudio.api.shared.storage.PresignedUploadResult;
import com.tinniestudio.api.shared.storage.StorageService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UploadService")
class UploadServiceTest {

    @Mock UploadSessionRepository uploadSessionRepository;
    @Mock MediaFileRepository mediaFileRepository;
    @Mock VideoAssetRepository videoAssetRepository;
    @Mock StorageService storageService;
    @Mock QueuePublisher queuePublisher;
    @Mock UploadConfig uploadConfig;

    @InjectMocks UploadService uploadService;

    private final UUID userId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    @Nested @DisplayName("createSession()")
    class CreateSessionTests {

        @Test @DisplayName("returns presigned URL response for valid RAW_VIDEO request")
        void createsSessionForRawVideo() {
            CreateUploadSessionRequest req = new CreateUploadSessionRequest(
                UploadType.RAW_VIDEO, TargetEntityType.EPISODE, targetId,
                "video.mp4", "video/mp4", 1_000_000L
            );
            when(uploadConfig.isMimeTypeAllowed(UploadType.RAW_VIDEO, "video/mp4")).thenReturn(true);
            when(uploadConfig.getMaxBytes(UploadType.RAW_VIDEO)).thenReturn(10_737_418_240L);
            Instant expiry = Instant.now().plusSeconds(1800);
            when(storageService.generateUploadUrl(any(), eq("video/mp4"), anyLong(), any()))
                .thenReturn(new PresignedUploadResult("https://minio/upload", "raw/uuid/original.mp4", expiry));
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> {
                UploadSession s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            UploadSessionResponse result = uploadService.createSession(userId, req);

            assertThat(result.uploadUrl()).isEqualTo("https://minio/upload");
            assertThat(result.storageKey()).isEqualTo("raw/uuid/original.mp4");
            assertThat(result.expiresAt()).isEqualTo(expiry);
            assertThat(result.sessionId()).isNotNull();
        }

        @Test @DisplayName("throws 400 when MIME type not allowed for upload type")
        void throws400OnDisallowedMimeType() {
            CreateUploadSessionRequest req = new CreateUploadSessionRequest(
                UploadType.THUMBNAIL, null, null, "video.mp4", "video/mp4", 1_000_000L
            );
            when(uploadConfig.isMimeTypeAllowed(UploadType.THUMBNAIL, "video/mp4")).thenReturn(false);

            assertThatThrownBy(() -> uploadService.createSession(userId, req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test @DisplayName("throws 400 when file size exceeds maximum for upload type")
        void throws400OnFileSizeExceeded() {
            CreateUploadSessionRequest req = new CreateUploadSessionRequest(
                UploadType.THUMBNAIL, null, null, "large.jpg", "image/jpeg", 100_000_000L
            );
            when(uploadConfig.isMimeTypeAllowed(UploadType.THUMBNAIL, "image/jpeg")).thenReturn(true);
            when(uploadConfig.getMaxBytes(UploadType.THUMBNAIL)).thenReturn(10_485_760L); // 10 MB

            assertThatThrownBy(() -> uploadService.createSession(userId, req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested @DisplayName("completeSession()")
    class CompleteSessionTests {

        private UploadSession pendingSession() {
            UploadSession s = new UploadSession();
            s.setId(UUID.randomUUID());
            s.setUserId(userId);
            s.setUploadType(UploadType.THUMBNAIL);
            s.setStorageKey("thumbnails/uuid/thumb.jpg");
            s.setUploadStatus(UploadStatus.PENDING);
            s.setExpiresAt(Instant.now().plusSeconds(300));
            return s;
        }

        @Test @DisplayName("completes THUMBNAIL session without creating VideoAsset")
        void completesThumbnailSession() {
            UploadSession session = pendingSession();
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("thumbnails/uuid/thumb.jpg")).thenReturn(true);
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = uploadService.completeSession(userId, session.getId());

            assertThat(result.mediaFileId()).isNotNull();
            assertThat(result.videoAssetId()).isNull();
            verify(queuePublisher, never()).publish(any(), any(), any());
        }

        @Test @DisplayName("completes RAW_VIDEO session and publishes to queue")
        void completesRawVideoAndPublishesToQueue() {
            UploadSession session = pendingSession();
            session.setUploadType(UploadType.RAW_VIDEO);
            session.setStorageKey("raw/uuid/original.mp4");
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("raw/uuid/original.mp4")).thenReturn(true);
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            when(videoAssetRepository.save(any())).thenAnswer(inv -> {
                VideoAsset a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = uploadService.completeSession(userId, session.getId());

            assertThat(result.videoAssetId()).isNotNull();
            verify(queuePublisher).publish(eq("media.video.process"), eq("VIDEO_PROCESSING_JOB"), any());
        }

        @Test @DisplayName("throws 404 when session not found")
        void throws404WhenSessionNotFound() {
            UUID id = UUID.randomUUID();
            when(uploadSessionRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> uploadService.completeSession(userId, id))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test @DisplayName("throws 403 when session belongs to different user")
        void throws403WhenWrongUser() {
            UploadSession session = pendingSession();
            session.setUserId(UUID.randomUUID()); // different user
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test @DisplayName("throws 422 when session already completed")
        void throws422WhenAlreadyCompleted() {
            UploadSession session = pendingSession();
            session.setUploadStatus(UploadStatus.COMPLETED);
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test @DisplayName("throws 422 when session expired")
        void throws422WhenExpired() {
            UploadSession session = pendingSession();
            session.setExpiresAt(Instant.now().minusSeconds(60)); // expired
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test @DisplayName("throws 422 when object not found in storage")
        void throws422WhenObjectMissingFromStorage() {
            UploadSession session = pendingSession();
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("thumbnails/uuid/thumb.jpg")).thenReturn(false);

            assertThatThrownBy(() -> uploadService.completeSession(userId, session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Nested @DisplayName("getStatus()")
    class GetStatusTests {

        @Test @DisplayName("returns session status with no videoAsset when THUMBNAIL")
        void returnsStatusForThumbnailSession() {
            UploadSession session = new UploadSession();
            session.setId(UUID.randomUUID());
            session.setUserId(userId);
            session.setUploadType(UploadType.THUMBNAIL);
            session.setUploadStatus(UploadStatus.COMPLETED);
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(videoAssetRepository.findByUploadSessionId(session.getId())).thenReturn(Optional.empty());

            UploadStatusResponse result = uploadService.getStatus(userId, session.getId());

            assertThat(result.uploadStatus()).isEqualTo("COMPLETED");
            assertThat(result.processingStatus()).isNull();
        }

        @Test @DisplayName("throws 403 when session belongs to different user")
        void throws403WhenWrongUser() {
            UploadSession session = new UploadSession();
            session.setId(UUID.randomUUID());
            session.setUserId(UUID.randomUUID()); // different user
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> uploadService.getStatus(userId, session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL (UploadService doesn't exist yet)**

`./gradlew :api-service:test --tests "*.UploadServiceTest"` → FAIL is expected

- [ ] **Step 3: Create UploadSessionResponse.java**

```java
package com.tinniestudio.api.modules.upload.dto;

import java.time.Instant;
import java.util.UUID;

public record UploadSessionResponse(UUID sessionId, String uploadUrl, String storageKey, Instant expiresAt) {}
```

- [ ] **Step 4: Create CompleteUploadResponse.java**

```java
package com.tinniestudio.api.modules.upload.dto;

import java.util.UUID;

public record CompleteUploadResponse(UUID mediaFileId, UUID videoAssetId) {}
```

- [ ] **Step 5: Create UploadStatusResponse.java**

```java
package com.tinniestudio.api.modules.upload.dto;

import java.util.UUID;

public record UploadStatusResponse(UUID sessionId, String uploadStatus, String processingStatus) {}
```

- [ ] **Step 6: Create CreateUploadSessionRequest.java**

```java
package com.tinniestudio.api.modules.upload.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.UploadType;
import com.tinniestudio.api.shared.entity.DomainEnums.TargetEntityType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record CreateUploadSessionRequest(
    @NotNull UploadType uploadType,
    TargetEntityType targetEntityType,
    UUID targetEntityId,
    String originalFilename,
    @NotNull String mimeType,
    @NotNull @Positive Long fileSizeBytes
) {}
```

- [ ] **Step 7: Create MediaProcessingJobPayload.java**

```java
package com.tinniestudio.api.modules.upload.dto;

import java.util.UUID;

public record MediaProcessingJobPayload(
    String jobId,
    UUID videoAssetId,
    String storageKey,
    UUID uploadSessionId
) {}
```

- [ ] **Step 8: Create UploadService.java**

```java
package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.upload.config.UploadConfig;
import com.tinniestudio.api.modules.upload.dto.*;
import com.tinniestudio.api.modules.upload.repository.*;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.queue.QueuePublisher;
import com.tinniestudio.api.shared.storage.PresignedUploadResult;
import com.tinniestudio.api.shared.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UploadService {

    private final UploadSessionRepository uploadSessionRepository;
    private final MediaFileRepository mediaFileRepository;
    private final VideoAssetRepository videoAssetRepository;
    private final StorageService storageService;
    private final QueuePublisher queuePublisher;
    private final UploadConfig uploadConfig;

    @Transactional
    public UploadSessionResponse createSession(UUID userId, CreateUploadSessionRequest req) {
        // Validate MIME type
        if (!uploadConfig.isMimeTypeAllowed(req.uploadType(), req.mimeType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "MIME type " + req.mimeType() + " is not allowed for upload type " + req.uploadType());
        }
        // Validate file size
        long maxBytes = uploadConfig.getMaxBytes(req.uploadType());
        if (req.fileSizeBytes() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "File size " + req.fileSizeBytes() + " bytes exceeds maximum " + maxBytes + " for " + req.uploadType());
        }

        String ext = extractExtension(req.originalFilename());
        String storageKey = buildStorageKey(req.uploadType(), req.targetEntityId(), ext);

        PresignedUploadResult presigned = storageService.generateUploadUrl(
            storageKey, req.mimeType(), maxBytes, UploadConfig.PRESIGNED_URL_TTL);

        UploadSession session = new UploadSession();
        session.setUserId(userId);
        session.setUploadType(req.uploadType());
        session.setTargetEntityType(req.targetEntityType());
        session.setTargetEntityId(req.targetEntityId());
        session.setStorageKey(storageKey);
        session.setOriginalFilename(req.originalFilename());
        session.setMimeType(req.mimeType());
        session.setExpectedMaxSizeBytes(req.fileSizeBytes());
        session.setUploadStatus(UploadStatus.PENDING);
        session.setPresignedUrl(presigned.uploadUrl()); // stored for debugging only
        session.setExpiresAt(presigned.expiresAt());

        UploadSession saved = uploadSessionRepository.save(session);
        return new UploadSessionResponse(saved.getId(), presigned.uploadUrl(), storageKey, presigned.expiresAt());
    }

    @Transactional
    public CompleteUploadResponse completeSession(UUID userId, UUID sessionId) {
        UploadSession session = uploadSessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload session not found: " + sessionId));

        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Upload session does not belong to this user");
        }
        if (session.getUploadStatus() != UploadStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Upload session is not in PENDING state (current: " + session.getUploadStatus() + ")");
        }
        if (Instant.now().isAfter(session.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Upload session has expired");
        }
        if (!storageService.objectExists(session.getStorageKey())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Object not found in storage. Upload the file before calling complete.");
        }

        // Create MediaFile record
        MediaFile mediaFile = new MediaFile();
        mediaFile.setUserId(userId);
        mediaFile.setUploadSessionId(sessionId);
        mediaFile.setFileType(session.getUploadType().name());
        mediaFile.setStorageKey(session.getStorageKey());
        mediaFile.setOriginalFilename(session.getOriginalFilename());
        mediaFile.setMimeType(session.getMimeType());
        MediaFile savedFile = mediaFileRepository.save(mediaFile);

        // Mark session completed
        session.setUploadStatus(UploadStatus.COMPLETED);
        session.setCompletedAt(Instant.now());
        uploadSessionRepository.save(session);

        // Handle post-completion by upload type
        UUID videoAssetId = null;
        UploadType type = session.getUploadType();
        if (type == UploadType.RAW_VIDEO || type == UploadType.TRAILER) {
            VideoAsset asset = new VideoAsset();
            asset.setUploadSessionId(sessionId);
            asset.setUploadedBy(userId);
            asset.setAssetType(type == UploadType.TRAILER ? VideoAssetType.TRAILER : VideoAssetType.MAIN_VIDEO);
            asset.setStorageKey(session.getStorageKey());
            asset.setOriginalFilename(session.getOriginalFilename());
            asset.setProcessingStatus(ProcessingStatus.PENDING);
            VideoAsset savedAsset = videoAssetRepository.save(asset);
            videoAssetId = savedAsset.getId();

            // Publish to media processing queue
            MediaProcessingJobPayload payload = new MediaProcessingJobPayload(
                UUID.randomUUID().toString(), savedAsset.getId(), session.getStorageKey(), sessionId);
            queuePublisher.publish(UploadConfig.VIDEO_PROCESS_QUEUE, "VIDEO_PROCESSING_JOB", payload);
        }

        return new CompleteUploadResponse(savedFile.getId(), videoAssetId);
    }

    @Transactional(readOnly = true)
    public UploadStatusResponse getStatus(UUID userId, UUID sessionId) {
        UploadSession session = uploadSessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload session not found: " + sessionId));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Upload session does not belong to this user");
        }
        String processingStatus = videoAssetRepository.findByUploadSessionId(sessionId)
            .map(a -> a.getProcessingStatus().name())
            .orElse(null);
        return new UploadStatusResponse(sessionId, session.getUploadStatus().name(), processingStatus);
    }

    private String buildStorageKey(UploadType type, UUID targetEntityId, String ext) {
        String uuid = UUID.randomUUID().toString();
        return switch (type) {
            case RAW_VIDEO -> "raw/" + uuid + "/original." + ext;
            case TRAILER   -> "raw/trailers/" + uuid + "/original." + ext;
            case THUMBNAIL -> "thumbnails/" + (targetEntityId != null ? targetEntityId : uuid) + "/" + uuid + "." + ext;
            case SUBTITLE  -> "subtitles/" + (targetEntityId != null ? targetEntityId : uuid) + "/" + uuid + "." + ext;
            default        -> "uploads/" + uuid + "." + ext;
        };
    }

    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "bin";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
```

- [ ] **Step 9: Run tests — all must pass**

`./gradlew :api-service:test --tests "*.UploadServiceTest"`
Expected: `BUILD SUCCESSFUL` — all tests green

- [ ] **Step 10: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/ \
        api-service/src/main/java/com/tinniestudio/api/modules/upload/service/ \
        api-service/src/test/java/com/tinniestudio/api/modules/upload/
git commit -m "feat(upload): add DTOs and UploadService with presigned URL, complete, and status endpoints"
```

---

## Task 5: UploadController + SecurityConfig

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/upload/controller/UploadController.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java`

- [ ] **Step 1: Create UploadController.java**

Upload endpoints require authentication (PARTNER or USER with subscription). Extract `userId` from the JWT principal.

```java
package com.tinniestudio.api.modules.upload.controller;

import com.tinniestudio.api.modules.upload.dto.*;
import com.tinniestudio.api.modules.upload.service.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Uploads", description = "Presigned upload session management")
@RestController
@RequestMapping("/uploads")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasRole('PARTNER') or hasRole('USER')")
public class UploadController {

    private final UploadService uploadService;

    @Operation(summary = "Create a presigned upload session")
    @PostMapping("/sessions")
    public ResponseEntity<UploadSessionResponse> createSession(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody CreateUploadSessionRequest req) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(uploadService.createSession(userId, req));
    }

    @Operation(summary = "Complete an upload session — verifies object in storage")
    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<CompleteUploadResponse> complete(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID sessionId) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(uploadService.completeSession(userId, sessionId));
    }

    @Operation(summary = "Get upload session status and processing progress")
    @GetMapping("/{sessionId}/status")
    public ResponseEntity<UploadStatusResponse> status(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID sessionId) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(uploadService.getStatus(userId, sessionId));
    }
}
```

**Note on `principal.getUsername()`:** Check how `UserDetails` stores the user ID in this project. In some projects the username is the email, not the UUID. Read `UserDetailsServiceImpl` before implementing to confirm. If `getUsername()` returns email rather than UUID, inject the User entity directly or use a custom `@AuthenticationPrincipal` annotation. Adjust the `UUID.fromString(principal.getUsername())` call accordingly.

- [ ] **Step 2: Check UserDetailsServiceImpl to find how userId is stored**

Read `api-service/src/main/java/com/tinniestudio/api/modules/user/service/UserDetailsServiceImpl.java` and confirm what `getUsername()` returns. If it's an email, look for a `UserRepository.findByEmail()` call to convert to UUID, or check if a custom UserDetails stores the UUID separately. Update the controller to use whatever pattern the rest of the codebase uses.

- [ ] **Step 3: Compile check**

`./gradlew :api-service:compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/upload/controller/
git commit -m "feat(upload): add UploadController with create, complete, and status endpoints"
```

---

## Task 6: Full test run + final compile

- [ ] **Step 1: Run all unit tests**

`./gradlew :api-service:test`
Expected: `BUILD SUCCESSFUL` — all existing tests green, no regressions

- [ ] **Step 2: Compile check**

`./gradlew :api-service:compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Final commit if any fixes were needed**

```bash
git add -p
git commit -m "fix(upload): address compile/test issues from full test run"
```

---

## Self-Review Checklist

### 1. Spec coverage:
- [x] V25: upload_sessions migration
- [x] V26: media_files migration
- [x] V27: video_assets + video_variants migrations
- [x] UploadSession entity: presignedUrl field added
- [x] MediaFile entity created
- [x] VideoAsset entity: uploadSessionId field added
- [x] UploadConfig: mime allowlists + max bytes per upload type
- [x] createSession: validates mimeType + fileSizeBytes, generates presigned URL, stores session
- [x] completeSession: verifies ownership, status==PENDING, not expired, objectExists → 422 if missing
- [x] completeSession: creates MediaFile, updates session status=COMPLETED
- [x] completeSession: creates VideoAsset + publishes to queue for RAW_VIDEO/TRAILER
- [x] getStatus: ownership check, returns session status + processingStatus from VideoAsset
- [x] UploadController: three endpoints, authenticated users only
- [x] Tests: valid creation, invalid MIME, size exceeded, 404, 403, 422 (completed), 422 (expired), 422 (missing object), RAW_VIDEO queue publish, THUMBNAIL no queue

### 2. Placeholder scan:
- Task 5, Step 2 has a NOTE about `principal.getUsername()` pattern — this requires checking UserDetailsServiceImpl before finalizing the controller. This is an intentional investigation step, not a placeholder.

### 3. Type consistency:
- `CompleteUploadResponse(mediaFileId, videoAssetId)` — used in tests and service: ✅
- `UploadSessionResponse(sessionId, uploadUrl, storageKey, expiresAt)` — consistent: ✅
- `MediaProcessingJobPayload(jobId, videoAssetId, storageKey, uploadSessionId)` — used in service: ✅
