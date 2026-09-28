# Video Processing Actions (Server) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give partners the ability to cancel an in-flight video and delete a finished/failed/cancelled one — the `api-service` + `media-worker` half of the cross-repo cancel/delete feature. Adds `CANCELLED` to `api-service`'s `DomainEnums.ProcessingStatus`, two new `PartnerVideoController` endpoints (`POST /partners/videos/{id}/cancel`, `DELETE /partners/videos/{id}`) delegating to new `PartnerVideoService` methods with the same ownership-check precedent as `activate()`, and best-effort cancellation checks inside `media-worker`'s `VideoProcessingService.process()` stage loop. partner-web's consumption of these endpoints is out of scope — a separate plan covers that repo.

**Architecture:** `cancel()` sets `processingStatus = CANCELLED` synchronously in the DB, independent of whatever stage the worker is currently in — the worker discovers this asynchronously by re-reading the row at the next stage boundary. `delete()` is only allowed from a terminal state (`READY`/`FAILED`/`CANCELLED`) and removes the `VideoAsset` row (cascading to its `VideoVariant`/`Subtitle` children via existing `CascadeType.ALL`) plus its raw storage object — mirroring the exact deletion pattern already established by `FailedVideoAssetCleanupJob` (`storageService.deleteObject(asset.getStorageKey())`, best-effort/non-fatal on storage failure), not inventing a new one.

**Tech Stack:** Spring Boot, JUnit 5 + Mockito + AssertJ, Gradle multi-module (`api-service`, `media-worker`).

**Covers spec:** `docs/superpowers/specs/2026-09-21-video-processing-actions-design.md` — server-repo portions only: §1 (`ProcessingStatus` gains `CANCELLED`), §2 (the two new `PartnerVideoController` endpoints), §3 (worker best-effort cancellation). §4 (partner-web polling/UI changes) is covered by a separate plan in the `tinniestudio-partner-web` repo.

**Depends on:** none. **Blocks:** the partner-web plan (needs `CANCELLED` + the two endpoints to exist first, per the spec's own "Blocks" line).

**Key finding from investigation that corrects an assumption in the spec (don't re-litigate — see Self-Review Notes for detail):** the spec states `ProcessingStatus` is "duplicated between `api-service` and `media-worker` — both need the addition." This is only half true. `api-service`'s `DomainEnums.ProcessingStatus` is a real Java enum (`PENDING | PROCESSING | READY | FAILED`) stored via `@Enumerated(EnumType.STRING)`. `media-worker`'s `VideoAsset.processingStatus`, by contrast, is a plain `String` field (`media-worker/src/main/java/com/tinniestudio/worker/entity/VideoAsset.java:38`) — `VideoProcessingService` writes to it using raw string literals (`"PROCESSING"`, `"READY"`, `"FAILED"`) with no enum type anywhere in `media-worker`. There is nothing to "add `CANCELLED` to" on the worker side; Task 4 below uses the literal string `"CANCELLED"` directly, which the existing `String` column already accepts with zero type or schema changes.

**Also confirmed:** `video_assets.processing_status` is `VARCHAR(50)` with no `CHECK` constraint (`api-service/src/main/resources/db/migration/V27__add_video_assets.sql:18`) — adding `CANCELLED` to the Java enum requires no Flyway migration.

---

## File Structure

**Modify:**
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java` — add `CANCELLED` to `ProcessingStatus`
- `api-service/src/test/java/com/tinniestudio/api/shared/entity/DomainEnumsTest.java` — new `ProcessingStatusTests` nested class
- `api-service/src/main/java/com/tinniestudio/api/modules/upload/controller/PartnerVideoController.java` — two new endpoints
- `api-service/src/main/java/com/tinniestudio/api/modules/upload/service/PartnerVideoService.java` — `cancel()` and `delete()` methods
- `api-service/src/test/java/com/tinniestudio/api/modules/upload/service/PartnerVideoServiceTest.java` — new `CancelTests`/`DeleteTests` nested classes
- `media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java` — cancellation checks + `isCancelled()` helper
- `media-worker/src/test/java/com/tinniestudio/worker/processor/VideoProcessingServiceTest.java` — new cancellation tests

**Create:** none (no new files, no migration).

---

### Task 1: `ProcessingStatus` gains `CANCELLED`

**Files:** `DomainEnums.java`, `DomainEnumsTest.java`

- [ ] **Step 1: Write the failing test in `DomainEnumsTest.java`**

Add a new nested class alongside the existing `MainCategoryTests` (find the closing of that class and insert after it, inside the outer `DomainEnumsTest` class):

Find:
```java
        @Test
        @DisplayName("fromSlug throws IllegalArgumentException for an unknown slug")
        void fromSlugThrowsForUnknownSlug() {
            assertThatThrownBy(() -> MainCategory.fromSlug("not-a-real-slug"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown mainCategory");
        }
    }
}
```

Replace with:
```java
        @Test
        @DisplayName("fromSlug throws IllegalArgumentException for an unknown slug")
        void fromSlugThrowsForUnknownSlug() {
            assertThatThrownBy(() -> MainCategory.fromSlug("not-a-real-slug"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown mainCategory");
        }
    }

    @Nested
    @DisplayName("ProcessingStatus")
    class ProcessingStatusTests {

        @Test
        @DisplayName("has exactly 5 values including CANCELLED")
        void hasExactlyFiveValuesIncludingCancelled() {
            assertThat(ProcessingStatus.values()).hasSize(5);
            assertThat(ProcessingStatus.valueOf("CANCELLED")).isEqualTo(ProcessingStatus.CANCELLED);
        }
    }
}
```

Add the import (this file currently only imports `MainCategory` directly):

Find:
```java
import com.tinniestudio.api.shared.entity.DomainEnums.MainCategory;
```

Replace with:
```java
import com.tinniestudio.api.shared.entity.DomainEnums.MainCategory;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
```

- [ ] **Step 2: Run to verify the new test fails**

Run: `./gradlew :api-service:test --tests DomainEnumsTest`
Expected: FAIL — `ProcessingStatus.values()` currently has 4 entries, not 5, and `valueOf("CANCELLED")` throws `IllegalArgumentException`.

- [ ] **Step 3: Add `CANCELLED` to the enum**

Find:
```java
    public enum ProcessingStatus {
        PENDING,
        PROCESSING,
        READY,
        FAILED
    }
```

Replace with:
```java
    public enum ProcessingStatus {
        PENDING,
        PROCESSING,
        READY,
        FAILED,
        // Set synchronously by PartnerVideoService.cancel() the instant a partner cancels — not
        // by media-worker, which only ever reads this value (see VideoProcessingService.isCancelled)
        // to decide whether to stop before its next stage. Never reachable via
        // VideoActivationService or any admin-moderation transition.
        CANCELLED
    }
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :api-service:test --tests DomainEnumsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java api-service/src/test/java/com/tinniestudio/api/shared/entity/DomainEnumsTest.java
git commit -m "feat: add CANCELLED to ProcessingStatus"
```

---

### Task 2: `POST /partners/videos/{id}/cancel`

**Files:** `PartnerVideoService.java`, `PartnerVideoController.java`, `PartnerVideoServiceTest.java`

- [ ] **Step 1: Write the failing tests in `PartnerVideoServiceTest.java`**

Add a new nested class after the existing `ActivateTests` class (find its closing brace — the one right before the final closing brace of `PartnerVideoServiceTest`):

Find:
```java
        @Test @DisplayName("throws 404 when the video doesn't exist")
        void throws404WhenMissing() {
            UUID assetId = UUID.randomUUID();
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> partnerVideoService.activate(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }
}
```

Replace with:
```java
        @Test @DisplayName("throws 404 when the video doesn't exist")
        void throws404WhenMissing() {
            UUID assetId = UUID.randomUUID();
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> partnerVideoService.activate(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested @DisplayName("cancel()")
    class CancelTests {

        @Test @DisplayName("sets CANCELLED on a PENDING video owned by the caller")
        void cancelsOwnedPendingAsset() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.PENDING);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));
            when(videoAssetRepository.save(any(VideoAsset.class))).thenAnswer(inv -> inv.getArgument(0));

            partnerVideoService.cancel(ownerId, assetId);

            assertThat(asset.getProcessingStatus()).isEqualTo(ProcessingStatus.CANCELLED);
            verify(videoAssetRepository).save(asset);
        }

        @Test @DisplayName("sets CANCELLED on a PROCESSING video owned by the caller")
        void cancelsOwnedProcessingAsset() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.PROCESSING);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));
            when(videoAssetRepository.save(any(VideoAsset.class))).thenAnswer(inv -> inv.getArgument(0));

            partnerVideoService.cancel(ownerId, assetId);

            assertThat(asset.getProcessingStatus()).isEqualTo(ProcessingStatus.CANCELLED);
        }

        @Test @DisplayName("throws 404 when the video doesn't belong to the caller")
        void throws404WhenNotOwner() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(UUID.randomUUID());
            asset.setProcessingStatus(ProcessingStatus.PENDING);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.cancel(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
            verify(videoAssetRepository, never()).save(any());
        }

        @Test @DisplayName("throws 404 when the video doesn't exist")
        void throws404WhenMissing() {
            UUID assetId = UUID.randomUUID();
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> partnerVideoService.cancel(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test @DisplayName("throws 400 when the video is already READY")
        void throws400WhenAlreadyReady() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.READY);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.cancel(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
            verify(videoAssetRepository, never()).save(any());
        }

        @Test @DisplayName("throws 400 when the video is already FAILED")
        void throws400WhenAlreadyFailed() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.FAILED);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.cancel(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        }

        @Test @DisplayName("throws 400 when the video is already CANCELLED")
        void throws400WhenAlreadyCancelled() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.CANCELLED);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.cancel(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        }
    }
}
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./gradlew :api-service:test --tests PartnerVideoServiceTest`
Expected: FAIL to compile — `PartnerVideoService.cancel(...)` doesn't exist yet.

- [ ] **Step 3: Add `cancel()` to `PartnerVideoService.java`**

Find:
```java
    @Transactional
    public void activate(UUID userId, UUID videoAssetId) {
        VideoAsset asset = videoAssetRepository.findById(videoAssetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId));
        // Ownership by uploader, not by re-resolving content.getCreatedBy() — matches the
        // existing IDOR-guard precedent in UploadService.attachSubtitle(), avoids a null check
        // for the content-link case (checked separately, with its own clearer error, below).
        if (!userId.equals(asset.getUploadedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId);
        }
        if (asset.getProcessingStatus() != ProcessingStatus.READY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a READY video can be set as active");
        }
        if (asset.getContent() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "This video has no linked content and cannot be activated");
        }
        videoActivationService.activateAndRetireSiblings(asset);
    }
```

Replace with:
```java
    @Transactional
    public void activate(UUID userId, UUID videoAssetId) {
        VideoAsset asset = videoAssetRepository.findById(videoAssetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId));
        // Ownership by uploader, not by re-resolving content.getCreatedBy() — matches the
        // existing IDOR-guard precedent in UploadService.attachSubtitle(), avoids a null check
        // for the content-link case (checked separately, with its own clearer error, below).
        if (!userId.equals(asset.getUploadedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId);
        }
        if (asset.getProcessingStatus() != ProcessingStatus.READY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a READY video can be set as active");
        }
        if (asset.getContent() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "This video has no linked content and cannot be activated");
        }
        videoActivationService.activateAndRetireSiblings(asset);
    }

    /**
     * Sets processingStatus=CANCELLED immediately, regardless of what stage media-worker is
     * currently in — the worker discovers this asynchronously via its own best-effort
     * stage-boundary checks (VideoProcessingService.isCancelled) and never overwrites this value
     * once set. Only PENDING/PROCESSING assets can be cancelled; anything already terminal
     * (READY/FAILED/CANCELLED) must go through delete() instead.
     */
    @Transactional
    public void cancel(UUID userId, UUID videoAssetId) {
        VideoAsset asset = videoAssetRepository.findById(videoAssetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId));
        if (!userId.equals(asset.getUploadedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId);
        }
        if (asset.getProcessingStatus() != ProcessingStatus.PENDING
                && asset.getProcessingStatus() != ProcessingStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only a PENDING or PROCESSING video can be cancelled");
        }
        asset.setProcessingStatus(ProcessingStatus.CANCELLED);
        videoAssetRepository.save(asset);
    }
```

- [ ] **Step 4: Wire the endpoint into `PartnerVideoController.java`**

Find:
```java
    @Operation(summary = "Set a READY video as the active one for its target, retiring any previously-active sibling")
    @PatchMapping("/{id}/activate")
    public ResponseEntity<Object> activate(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id) {
        UUID userId = CurrentUser.id(principal);
        partnerVideoService.activate(userId, id);
        return ResponseEntity.ok(Map.of("message", "Video activated"));
    }
}
```

Replace with:
```java
    @Operation(summary = "Set a READY video as the active one for its target, retiring any previously-active sibling")
    @PatchMapping("/{id}/activate")
    public ResponseEntity<Object> activate(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id) {
        UUID userId = CurrentUser.id(principal);
        partnerVideoService.activate(userId, id);
        return ResponseEntity.ok(Map.of("message", "Video activated"));
    }

    @Operation(summary = "Cancel a PENDING or PROCESSING video; media-worker stops at its next stage boundary")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Object> cancel(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id) {
        UUID userId = CurrentUser.id(principal);
        partnerVideoService.cancel(userId, id);
        return ResponseEntity.ok(Map.of("message", "Video cancelled"));
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :api-service:test --tests PartnerVideoServiceTest`
Expected: PASS — all tests in this class, including the 6 new ones.

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/upload/service/PartnerVideoService.java api-service/src/main/java/com/tinniestudio/api/modules/upload/controller/PartnerVideoController.java api-service/src/test/java/com/tinniestudio/api/modules/upload/service/PartnerVideoServiceTest.java
git commit -m "feat: add POST /partners/videos/{id}/cancel"
```

---

### Task 3: `DELETE /partners/videos/{id}`

**Files:** `PartnerVideoService.java`, `PartnerVideoController.java`, `PartnerVideoServiceTest.java`

- [ ] **Step 1: Write the failing tests in `PartnerVideoServiceTest.java`**

Add a `StorageService` mock and a new `DeleteTests` nested class. First, find the existing mock declarations:

Find:
```java
    @Mock VideoAssetRepository videoAssetRepository;
    @Mock ContentRepository contentRepository;
    @Mock SeasonRepository seasonRepository;
    @Mock EpisodeRepository episodeRepository;
    @Mock VideoActivationService videoActivationService;
```

Replace with:
```java
    @Mock VideoAssetRepository videoAssetRepository;
    @Mock ContentRepository contentRepository;
    @Mock SeasonRepository seasonRepository;
    @Mock EpisodeRepository episodeRepository;
    @Mock VideoActivationService videoActivationService;
    @Mock com.tinniestudio.api.shared.storage.StorageService storageService;
```

Then add `DeleteTests` after the `CancelTests` class added in Task 2 (find its closing, right before the final closing brace of the outer test class):

Find:
```java
        @Test @DisplayName("throws 400 when the video is already CANCELLED")
        void throws400WhenAlreadyCancelled() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.CANCELLED);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.cancel(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        }
    }
}
```

Replace with:
```java
        @Test @DisplayName("throws 400 when the video is already CANCELLED")
        void throws400WhenAlreadyCancelled() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.CANCELLED);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.cancel(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        }
    }

    @Nested @DisplayName("delete()")
    class DeleteTests {

        @Test @DisplayName("deletes a READY video owned by the caller, including its raw storage object")
        void deletesOwnedReadyAsset() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.READY);
            asset.setStorageKey("uploads/" + assetId + "/raw.mp4");
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            partnerVideoService.delete(ownerId, assetId);

            verify(storageService).deleteObject("uploads/" + assetId + "/raw.mp4");
            verify(videoAssetRepository).delete(asset);
        }

        @Test @DisplayName("deletes a FAILED video owned by the caller")
        void deletesOwnedFailedAsset() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.FAILED);
            asset.setStorageKey("uploads/" + assetId + "/raw.mp4");
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            partnerVideoService.delete(ownerId, assetId);

            verify(videoAssetRepository).delete(asset);
        }

        @Test @DisplayName("deletes a CANCELLED video owned by the caller")
        void deletesOwnedCancelledAsset() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.CANCELLED);
            asset.setStorageKey("uploads/" + assetId + "/raw.mp4");
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            partnerVideoService.delete(ownerId, assetId);

            verify(videoAssetRepository).delete(asset);
        }

        @Test @DisplayName("does not fail the request when storage deletion throws")
        void survivesStorageDeletionFailure() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.READY);
            asset.setStorageKey("uploads/" + assetId + "/raw.mp4");
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));
            doThrow(new RuntimeException("S3 unreachable")).when(storageService).deleteObject(anyString());

            partnerVideoService.delete(ownerId, assetId);

            verify(videoAssetRepository).delete(asset);
        }

        @Test @DisplayName("throws 404 when the video doesn't belong to the caller")
        void throws404WhenNotOwner() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(UUID.randomUUID());
            asset.setProcessingStatus(ProcessingStatus.READY);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.delete(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
            verify(videoAssetRepository, never()).delete(any());
        }

        @Test @DisplayName("throws 404 when the video doesn't exist")
        void throws404WhenMissing() {
            UUID assetId = UUID.randomUUID();
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> partnerVideoService.delete(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test @DisplayName("throws 400 when the video is still PENDING")
        void throws400WhenPending() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.PENDING);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.delete(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
            verify(videoAssetRepository, never()).delete(any());
            verifyNoInteractions(storageService);
        }

        @Test @DisplayName("throws 400 when the video is still PROCESSING")
        void throws400WhenProcessing() {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = new VideoAsset();
            asset.setId(assetId);
            asset.setUploadedBy(ownerId);
            asset.setProcessingStatus(ProcessingStatus.PROCESSING);
            when(videoAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> partnerVideoService.delete(ownerId, assetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        }
    }
}
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./gradlew :api-service:test --tests PartnerVideoServiceTest`
Expected: FAIL to compile — `PartnerVideoService.delete(...)` doesn't exist yet, and its constructor doesn't take a `StorageService` yet.

- [ ] **Step 3: Add `StorageService` + `delete()` to `PartnerVideoService.java`**

Find:
```java
package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.modules.upload.dto.PartnerVideoAssetResponse;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.Episode;
import com.tinniestudio.api.shared.entity.Season;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.TargetEntityType;
import com.tinniestudio.api.shared.entity.DomainEnums.VideoAssetType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PartnerVideoService {

    private final VideoAssetRepository videoAssetRepository;
    private final ContentRepository contentRepository;
    private final SeasonRepository seasonRepository;
    private final EpisodeRepository episodeRepository;
    private final VideoActivationService videoActivationService;
```

Replace with:
```java
package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.modules.upload.dto.PartnerVideoAssetResponse;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.Episode;
import com.tinniestudio.api.shared.entity.Season;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.TargetEntityType;
import com.tinniestudio.api.shared.entity.DomainEnums.VideoAssetType;
import com.tinniestudio.api.shared.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerVideoService {

    private final VideoAssetRepository videoAssetRepository;
    private final ContentRepository contentRepository;
    private final SeasonRepository seasonRepository;
    private final EpisodeRepository episodeRepository;
    private final VideoActivationService videoActivationService;
    private final StorageService storageService;
```

Now add the `delete()` method itself. Find:
```java
        asset.setProcessingStatus(ProcessingStatus.CANCELLED);
        videoAssetRepository.save(asset);
    }

    private void assertOwnsContent(UUID userId, Content content) {
```

Replace with:
```java
        asset.setProcessingStatus(ProcessingStatus.CANCELLED);
        videoAssetRepository.save(asset);
    }

    /**
     * Permanently removes the VideoAsset row (cascading to its VideoVariant/Subtitle children via
     * the entity's existing CascadeType.ALL) and its raw storage object. Only reachable from a
     * terminal status — PENDING/PROCESSING must be cancel()'d first. Mirrors the exact deletion
     * pattern FailedVideoAssetCleanupJob already uses for FAILED assets: storage deletion is
     * best-effort (a storage-layer failure must not block the DB row from being removed, since an
     * orphaned object with no DB row is a much smaller problem than a stuck row the partner can
     * never clear from their history).
     */
    @Transactional
    public void delete(UUID userId, UUID videoAssetId) {
        VideoAsset asset = videoAssetRepository.findById(videoAssetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId));
        if (!userId.equals(asset.getUploadedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId);
        }
        if (asset.getProcessingStatus() == ProcessingStatus.PENDING
                || asset.getProcessingStatus() == ProcessingStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cancel the video before deleting it, or wait for it to reach a final status");
        }
        if (asset.getStorageKey() != null) {
            try {
                storageService.deleteObject(asset.getStorageKey());
            } catch (Exception e) {
                log.warn("Failed to delete storage object {} for asset {}: {}",
                    asset.getStorageKey(), asset.getId(), e.getMessage());
            }
        }
        videoAssetRepository.delete(asset);
    }

    private void assertOwnsContent(UUID userId, Content content) {
```

- [ ] **Step 4: Wire the endpoint into `PartnerVideoController.java`**

Find:
```java
    @Operation(summary = "Cancel a PENDING or PROCESSING video; media-worker stops at its next stage boundary")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Object> cancel(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id) {
        UUID userId = CurrentUser.id(principal);
        partnerVideoService.cancel(userId, id);
        return ResponseEntity.ok(Map.of("message", "Video cancelled"));
    }
}
```

Replace with:
```java
    @Operation(summary = "Cancel a PENDING or PROCESSING video; media-worker stops at its next stage boundary")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Object> cancel(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id) {
        UUID userId = CurrentUser.id(principal);
        partnerVideoService.cancel(userId, id);
        return ResponseEntity.ok(Map.of("message", "Video cancelled"));
    }

    @Operation(summary = "Permanently delete a READY/FAILED/CANCELLED video and its raw storage object")
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id) {
        UUID userId = CurrentUser.id(principal);
        partnerVideoService.delete(userId, id);
        return ResponseEntity.ok(Map.of("message", "Video deleted"));
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :api-service:test --tests PartnerVideoServiceTest`
Expected: PASS — all tests in this class, including the 8 new `DeleteTests`.

- [ ] **Step 6: Run the full `api-service` suite**

Run: `./gradlew :api-service:test`
Expected: `BUILD SUCCESSFUL`. This also confirms nothing else in `api-service` constructed a `PartnerVideoService` directly (bypassing Spring's DI) in a way that would break from the new constructor parameter — `@RequiredArgsConstructor` means any such call site would fail to compile, and this run would catch it.

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/upload/service/PartnerVideoService.java api-service/src/main/java/com/tinniestudio/api/modules/upload/controller/PartnerVideoController.java api-service/src/test/java/com/tinniestudio/api/modules/upload/service/PartnerVideoServiceTest.java
git commit -m "feat: add DELETE /partners/videos/{id}"
```

---

### Task 4: media-worker best-effort cancellation checks

**Files:** `VideoProcessingService.java`, `VideoProcessingServiceTest.java`

- [ ] **Step 1: Write the failing tests in `VideoProcessingServiceTest.java`**

Add two new tests inside the existing `process` nested class, after `setsFailedStatusOnNonRetryableError`:

Find:
```java
        @Test
        void setsFailedStatusOnNonRetryableError() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(anyString(), eq("DONE"))).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);
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

Replace with:
```java
        @Test
        void setsFailedStatusOnNonRetryableError() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(anyString(), eq("DONE"))).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);
            when(ffprobeRunner.probe(anyString()))
                .thenThrow(new FFprobeRunner.ValidationException("no audio stream found"));

            service.process(payload);

            ArgumentCaptor<VideoAsset> captor = ArgumentCaptor.forClass(VideoAsset.class);
            verify(videoAssetRepo, atLeastOnce()).save(captor.capture());
            VideoAsset last = captor.getAllValues().get(captor.getAllValues().size() - 1);
            assertThat(last.getProcessingStatus()).isEqualTo("FAILED");
            assertThat(last.getProcessingError()).contains("no audio stream");
        }

        @Test
        void stopsBeforeTranscodingWhenCancelledDuringEarlierStages() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            VideoAsset cancelledAsset = buildAsset(assetId);
            cancelledAsset.setProcessingStatus("CANCELLED");
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            // 1st findById: initial load at the top of process(). 2nd: the cancellation check
            // before the first (and, at 1080p source, only-first-of-four) transcode tier.
            when(videoAssetRepo.findById(assetId))
                .thenReturn(Optional.of(asset))
                .thenReturn(Optional.of(cancelledAsset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);

            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                60, 1920, 1080, "h264", 5_000_000L, true);
            when(ffprobeRunner.probe(anyString())).thenReturn(meta);

            service.process(payload);

            verify(ffmpegRunner, never()).transcode(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString());
            verify(storageService, never()).uploadDirectory(anyString(), any(Path.class));
            verify(videoAssetRepo, never()).save(argThat(a -> "READY".equals(a.getProcessingStatus())));
        }

        @Test
        void stopsBeforeUploadingWhenCancelledAfterTranscoding() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            VideoAsset cancelledAsset = buildAsset(assetId);
            cancelledAsset.setProcessingStatus("CANCELLED");
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            // 360p source -> exactly 1 tier (see ResolutionLadder), so the in-loop check only
            // fires once (not cancelled yet); the pre-upload check is what catches it.
            // 1st findById: initial load. 2nd: in-loop check before the single 360p tier
            // (not cancelled). 3rd: the pre-UPLOADING_OUTPUT check (cancelled).
            when(videoAssetRepo.findById(assetId))
                .thenReturn(Optional.of(asset))
                .thenReturn(Optional.of(asset))
                .thenReturn(Optional.of(cancelledAsset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);

            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                30, 640, 360, "h264", 800_000L, true);
            when(ffprobeRunner.probe(anyString())).thenReturn(meta);

            service.process(payload);

            verify(ffmpegRunner, times(1)).transcode(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString());
            verify(storageService, never()).uploadDirectory(anyString(), any(Path.class));
            verify(videoVariantRepo, never()).save(any(VideoVariant.class));
            verify(videoAssetRepo, never()).save(argThat(a -> "READY".equals(a.getProcessingStatus())));
        }
    }
}
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./gradlew :media-worker:test --tests VideoProcessingServiceTest`
Expected: FAIL — with no cancellation check yet, `process()` runs straight through: `stopsBeforeTranscodingWhenCancelledDuringEarlierStages` fails because `ffmpegRunner.transcode(...)` IS called (4 times, once per 1080p-ladder tier), and `stopsBeforeUploadingWhenCancelledAfterTranscoding` fails because `storageService.uploadDirectory(...)` IS called.

- [ ] **Step 3: Add the cancellation checks to `VideoProcessingService.java`**

Find:
```java
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
```

Replace with:
```java
            // 5. TRANSCODING
            updateJobStatus(job, "TRANSCODING");
            for (ResolutionLadder.Tier tier : tiers) {
                if (isCancelled(videoAssetId)) {
                    log.info("VideoAsset {} was cancelled; stopping before transcoding {}", videoAssetId, tier.label());
                    return;
                }
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
            if (isCancelled(videoAssetId)) {
                log.info("VideoAsset {} was cancelled; stopping before uploading output", videoAssetId);
                return;
            }
            updateJobStatus(job, "UPLOADING_OUTPUT");
```

Now add the `isCancelled()` helper. Find:
```java
    private void updateJobStatus(ProcessingJob job, String status) {
        job.setStatus(status);
        job.setStageStartedAt(Instant.now());
        processingJobRepo.save(job);
    }
```

Replace with:
```java
    private void updateJobStatus(ProcessingJob job, String status) {
        job.setStatus(status);
        job.setStageStartedAt(Instant.now());
        processingJobRepo.save(job);
    }

    /**
     * Best-effort cancellation check (design spec §3): re-reads the VideoAsset from the DB on
     * every call — never trusts the in-memory `asset` held by process(), since
     * PartnerVideoService.cancel() (api-service) sets processingStatus="CANCELLED" directly in
     * the DB from a separate process, independent of and concurrent with this worker's progress.
     * A resolution already mid-transcode when cancellation lands still finishes that one
     * ffmpeg shell-out — only the next loop iteration (or the final upload stage) is skipped.
     * Never writes processingStatus itself; that value was already set by the API layer.
     * Returns false if the asset row is gone (defensive — nothing left to act on).
     */
    private boolean isCancelled(UUID videoAssetId) {
        return videoAssetRepo.findById(videoAssetId)
            .map(a -> "CANCELLED".equals(a.getProcessingStatus()))
            .orElse(false);
    }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :media-worker:test --tests VideoProcessingServiceTest`
Expected: PASS — all tests in this class, including the 2 new ones.

- [ ] **Step 5: Run the full `media-worker` suite**

Run: `./gradlew :media-worker:test`
Expected: `BUILD SUCCESSFUL`. In particular, confirm `VideoProcessingServiceTransactionBoundaryTest` (a sibling test file, not modified by this plan) still passes — it exercises the same `process()` method and its mocks may share the same `videoAssetRepo.findById` stubbing pattern that this task adds extra calls to.

- [ ] **Step 6: Commit**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java media-worker/src/test/java/com/tinniestudio/worker/processor/VideoProcessingServiceTest.java
git commit -m "feat: stop processing at the next stage boundary when a VideoAsset is cancelled"
```

---

### Task 5: Full cross-module verification

**Files:** none (verification only)

- [ ] **Step 1: Run both modules' full suites**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL` across both `api-service` and `media-worker`, modulo this repo's known pre-existing unrelated Testcontainers/environment failures (distinguish those from anything newly introduced — compare against a baseline run on the pre-Task-1 commit if there's any doubt about which failures are pre-existing).

- [ ] **Step 2: Spot-check `VideoProcessingServiceTransactionBoundaryTest`**

Run: `./gradlew :media-worker:test --tests VideoProcessingServiceTransactionBoundaryTest`
Expected: PASS. This file's name suggests it specifically asserts on the "no `@Transactional`, each save commits independently" invariant documented in `process()`'s own header comment — the two new early `return` statements added in Task 4 must not accidentally wrap themselves in a transaction boundary or skip a save that this test depends on. If it fails, read it in full before changing anything (do not adjust Task 4's code to "make it pass" without understanding what invariant broke).

- [ ] **Step 3: No commit for this task** — verification only, nothing to stage.

---

## Self-Review Notes

- **Spec coverage:** §1 (`CANCELLED` addition) → Task 1, with the correction that `media-worker` has no enum to modify (see below). §2 (both new endpoints, same ownership-check precedent as `activate()`, correct allowed-status gating per endpoint) → Tasks 2–3. §3 (best-effort cancellation at the transcode-loop and pre-upload stage boundaries, re-reading the DB rather than trusting the in-memory asset, no `processingStatus` write from the worker side) → Task 4. §4 (partner-web polling/UI) is explicitly out of scope for this plan per the task assignment and is not included here.
- **Deviation from the spec, and why it's correct:** the spec's Context section states `ProcessingStatus` is "duplicated between `api-service` and `media-worker` — both need the addition." Direct inspection of `media-worker/src/main/java/com/tinniestudio/worker/entity/VideoAsset.java` and `VideoProcessingService.java` shows `processingStatus` is a plain `String` column with string-literal writes (`"PROCESSING"`, `"READY"`, `"FAILED"`) — there is no `ProcessingStatus` enum type anywhere in `media-worker` to add a constant to (confirmed via `grep -rn "enum ProcessingStatus" media-worker/` returning nothing, and via reading every entity file in `media-worker/src/main/java/com/tinniestudio/worker/entity/`). Task 1 therefore only touches `api-service`'s real enum; Task 4 uses the literal string `"CANCELLED"` directly against the existing `String` field, which requires no type change and no migration (the column is `VARCHAR(50)` with no `CHECK` constraint, per `V27__add_video_assets.sql`). This produces the exact same externally-visible behavior the spec calls for — media-worker correctly recognizes and reacts to `CANCELLED` — without a fictitious enum-file edit that doesn't correspond to any real file.
- **Ownership-check precedent match:** both `cancel()` and `delete()` in `PartnerVideoService` copy `activate()`'s exact pattern verbatim — `findById` → 404 if absent → `!userId.equals(asset.getUploadedBy())` → 404 (not 403) if mismatched — confirmed against the real current `activate()` method body before writing Tasks 2–3, not paraphrased from the spec.
- **Storage-deletion scope decision:** `delete()` only deletes `asset.getStorageKey()` (the raw upload), not the processed HLS variants (`processed/{id}/...`), master manifest, or thumbnail (`thumbnails/{id}/poster.jpg`) that `media-worker` separately uploads during processing. This exactly matches the only existing precedent in this codebase for deleting a `VideoAsset`'s storage — `FailedVideoAssetCleanupJob` (`api-service/src/main/java/com/tinniestudio/api/modules/jobs/FailedVideoAssetCleanupJob.java`) — which has the identical limitation already accepted for FAILED assets older than 7 days (Batch 17 item 5). `StorageService` has no prefix/bulk-delete method (only single-key `deleteObject`), so cleaning up every processed variant would require iterating `asset.getVariants()` and separately tracking the thumbnail/manifest keys — a materially bigger change than the spec's one-line "and its storage object(s)" asks for, and not something any existing code in this repo already does. Flagging this explicitly rather than silently under-scoping: if broader storage cleanup is wanted, it should be a follow-up, not silently bundled here.
- **Cascade correctness:** `delete()` uses `videoAssetRepository.delete(asset)` (not a bulk JPQL delete like `FailedVideoAssetCleanupJob`'s `deleteAllByIdInBatch`) specifically so JPA's `CascadeType.ALL` on `VideoAsset.variants`/`VideoAsset.subtitles` actually fires — a READY asset (the main terminal state partners will delete) has real `VideoVariant` rows that a bulk delete would orphan.
- **Placeholder scan:** every find/replace block in Tasks 1–4 is copied verbatim from the actual file contents read during investigation (`DomainEnums.java`, `PartnerVideoController.java`, `PartnerVideoService.java`, `PartnerVideoServiceTest.java`, `VideoProcessingService.java`, `VideoProcessingServiceTest.java`, `DomainEnumsTest.java`) — no pseudocode, no "add appropriate handling" placeholders. The one deliberately-flagged deviation from the spec's literal text (the media-worker enum) is called out above, not silently glossed over.
- **Type consistency:** `ProcessingStatus.CANCELLED` is the real Java enum constant everywhere in `api-service` (entity field, service comparisons, test assertions); in `media-worker` the same concept is consistently the literal string `"CANCELLED"` against the existing `String processingStatus` field — no task conflates the two or assumes an enum exists on the worker side.
- **Test-style consistency:** `PartnerVideoServiceTest` additions use the same `@Nested`/`@DisplayName`/Mockito/AssertJ style as the existing `ActivateTests` class (same mock set, same `ResponseStatusException` + `hasMessageContaining("404"/"400")` assertion idiom). `VideoProcessingServiceTest` additions use the same `stubDownloadCreatesFile`/`buildAsset`/`buildPayload` helpers and sequential-`thenReturn` mocking idiom already used by the file's other tests — no new test infrastructure introduced.
- **Ordering dependency respected:** Task 3 depends on Task 2's `CancelTests` block existing in the test file (it finds/replaces the block immediately after it) and depends on Task 1's `CANCELLED` constant existing (used directly in `DeleteTests`' `deletesOwnedCancelledAsset`). Task 4 is independent of Tasks 2–3 (different module) but depends on Task 1 conceptually (though not on any Task-1-authored code, since media-worker never references the api-service enum).
