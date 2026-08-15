# Batch 8 — Playback System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement secure adaptive streaming with subscription enforcement — manifest delivery, access validation, progress tracking, and continue-watching.

**Architecture:** A new `playback` module in `api-service` owns all five endpoints. `PlaybackService` centralises access validation (content published + subscription active), manifest URL construction (CDN base URL + stored manifest key), and watch-progress upsert. Progress recording publishes a best-effort analytics event to RabbitMQ.

**Tech Stack:** Spring Boot 3 / JPA / PostgreSQL (Testcontainers for tests) / RabbitMQ (best-effort analytics) / existing security pattern (`@AuthenticationPrincipal UserDetails`)

---

## What Already Exists (Do NOT re-create)

| Asset | Location |
|-------|----------|
| `watch_progress` table | `V22__add_watch_progress.sql` |
| `WatchProgress` entity | `shared/entity/WatchProgress.java` |
| `VideoAsset` entity | `shared/entity/VideoAsset.java` — has `content`, `episode`, `assetType`, `manifestUrl`, `durationSeconds`, `processingStatus`, `subtitles` |
| `Subtitle` entity | `shared/entity/Subtitle.java` — has `languageCode`, `label`, `fileUrl`, `isDefault` |
| `UserSubscription` entity | `shared/entity/UserSubscription.java` — has `userId`, `plan`, `status` |
| `UserSubscriptionRepository` | `modules/billing/repository/UserSubscriptionRepository.java` — `findByUserIdAndStatus(UUID, SubscriptionStatus)` |
| `VideoAssetRepository` | `modules/upload/repository/VideoAssetRepository.java` — `findByUploadSessionId` |
| `ContentRepository` | `modules/content/repository/ContentRepository.java` |
| `EpisodeRepository` | `modules/episode/repository/EpisodeRepository.java` |
| `AppProperties` | `shared/config/AppProperties.java` |
| Enum `ProcessingStatus.READY` | `shared/entity/DomainEnums.java` |
| Enum `ContentStatus.PUBLISHED` | `shared/entity/DomainEnums.java` |
| Enum `VideoAssetType.MAIN_VIDEO` | `shared/entity/DomainEnums.java` |
| Enum `SubscriptionStatus.ACTIVE` | `shared/entity/DomainEnums.java` |
| Controller user-ID pattern | `@AuthenticationPrincipal UserDetails p` → `UUID.fromString(p.getUsername())` |

No new Flyway migration is needed — `watch_progress` schema is complete in V22.

---

## File Map

### New files
| File | Responsibility |
|------|---------------|
| `modules/playback/repository/WatchProgressRepository.java` | JPA queries for upsert and continue-watching |
| `modules/playback/service/PlaybackService.java` | Interface |
| `modules/playback/service/PlaybackServiceImpl.java` | Business logic — access check, manifest, progress, continue-watching |
| `modules/playback/controller/PlaybackController.java` | 5 REST endpoints |
| `modules/playback/dto/AccessCheckResponse.java` | `{ hasAccess, reason }` |
| `modules/playback/dto/PlaybackManifestResponse.java` | `{ manifestUrl, subtitles, resumeAt, duration }` |
| `modules/playback/dto/SubtitleDto.java` | `{ languageCode, label, url, isDefault }` |
| `modules/playback/dto/ProgressRequest.java` | `{ contentId?, episodeId?, progressSeconds, durationSeconds, deviceType }` |
| `modules/playback/dto/ContinueWatchingItem.java` | per-item response |

### Modified files
| File | Change |
|------|--------|
| `modules/upload/repository/VideoAssetRepository.java` | Add `findByContentIdAndAssetTypeAndProcessingStatus`, `findByEpisodeIdAndAssetTypeAndProcessingStatus` |
| `shared/config/AppProperties.java` | Add nested `Cdn` class with `baseUrl` field |
| `src/main/resources/application.yml` | Add `app.cdn.base-url` |
| `src/test/resources/application-test.yml` | Add `app.cdn.base-url` |
| `shared/security/SecurityConfig.java` | Keep `/api/v1/playback/**` authenticated (default — no change needed if `anyRequest().authenticated()` already covers it, but verify) |

### Test files
| File | Responsibility |
|------|---------------|
| `test/.../playback/service/PlaybackServiceTest.java` | Unit tests — mocked repos, covers all service methods |
| `test/.../playback/controller/PlaybackControllerTest.java` | Testcontainers integration — all 5 endpoints |

---

## Task 1: Config + Repository Foundation

**Files:**
- Modify: `shared/config/AppProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`
- Modify: `modules/upload/repository/VideoAssetRepository.java`
- Create: `modules/playback/repository/WatchProgressRepository.java`

- [ ] **Step 1: Add CDN base URL to AppProperties**

Append a `Cdn` nested class to `AppProperties.java`:

```java
private Cdn cdn = new Cdn();

@Getter
@Setter
public static class Cdn {
    private String baseUrl = "http://localhost:3000";
}
```

- [ ] **Step 2: Add config to application.yml**

Under the existing `app:` block in `src/main/resources/application.yml`:

```yaml
app:
  cdn:
    base-url: ${CDN_BASE_URL:http://localhost:3000}
```

- [ ] **Step 3: Add config to application-test.yml**

Under the existing `app:` block in `src/test/resources/application-test.yml`:

```yaml
app:
  cdn:
    base-url: http://localhost:3000
```

- [ ] **Step 4: Extend VideoAssetRepository**

Add two methods to `modules/upload/repository/VideoAssetRepository.java`:

```java
import com.tinniestudio.api.shared.entity.DomainEnums.VideoAssetType;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;

Optional<VideoAsset> findTopByContentIdAndAssetTypeAndProcessingStatus(
    UUID contentId, VideoAssetType assetType, ProcessingStatus processingStatus);

Optional<VideoAsset> findTopByEpisodeIdAndAssetTypeAndProcessingStatus(
    UUID episodeId, VideoAssetType assetType, ProcessingStatus processingStatus);
```

Note: `VideoAsset.content` is a `@ManyToOne Content`, so Spring Data derives the query from `contentId` via `content.id`. The field name on the entity is `content` (a relation), so the correct derived name is `findTopByContent_IdAndAssetType...` — verify by reading the entity field names and adjust accordingly. Similarly for `episode`.

- [ ] **Step 5: Create WatchProgressRepository**

```java
package com.tinniestudio.api.modules.playback.repository;

import com.tinniestudio.api.shared.entity.WatchProgress;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatchProgressRepository extends JpaRepository<WatchProgress, UUID> {

    // Movie progress — episode_id IS NULL
    @Query("SELECT w FROM WatchProgress w WHERE w.userId = :userId AND w.contentId = :contentId AND w.episodeId IS NULL")
    Optional<WatchProgress> findMovieProgress(@Param("userId") UUID userId, @Param("contentId") UUID contentId);

    // Episode progress
    Optional<WatchProgress> findByUserIdAndEpisodeId(UUID userId, UUID episodeId);

    // Continue watching — incomplete, most-recent first, limit applied via Pageable
    List<WatchProgress> findByUserIdAndCompletedFalseOrderByLastWatchedAtDesc(UUID userId, Pageable pageable);
}
```

- [ ] **Step 6: Compile**

```bash
./gradlew :api-service:compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/config/AppProperties.java \
  api-service/src/main/resources/application.yml \
  api-service/src/test/resources/application-test.yml \
  api-service/src/main/java/com/tinniestudio/api/modules/upload/repository/VideoAssetRepository.java \
  api-service/src/main/java/com/tinniestudio/api/modules/playback/repository/WatchProgressRepository.java
git commit -m "feat(playback): add WatchProgressRepository, extend VideoAssetRepository, add CDN base-url config"
```

---

## Task 2: DTOs

**Files:**
- Create: `modules/playback/dto/AccessCheckResponse.java`
- Create: `modules/playback/dto/PlaybackManifestResponse.java`
- Create: `modules/playback/dto/SubtitleDto.java`
- Create: `modules/playback/dto/ProgressRequest.java`
- Create: `modules/playback/dto/ContinueWatchingItem.java`

- [ ] **Step 1: AccessCheckResponse**

```java
package com.tinniestudio.api.modules.playback.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AccessCheckResponse {
    private final boolean hasAccess;
    private final String reason;

    public static AccessCheckResponse granted() {
        return new AccessCheckResponse(true, null);
    }

    public static AccessCheckResponse denied(String reason) {
        return new AccessCheckResponse(false, reason);
    }
}
```

- [ ] **Step 2: SubtitleDto**

```java
package com.tinniestudio.api.modules.playback.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SubtitleDto {
    private final String languageCode;
    private final String label;
    private final String url;
    private final boolean isDefault;
}
```

- [ ] **Step 3: PlaybackManifestResponse**

```java
package com.tinniestudio.api.modules.playback.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

@Getter
@AllArgsConstructor
public class PlaybackManifestResponse {
    private final String manifestUrl;
    private final List<SubtitleDto> subtitles;
    private final Integer resumeAt;   // seconds, null if never watched
    private final Integer duration;   // seconds
}
```

- [ ] **Step 4: ProgressRequest**

```java
package com.tinniestudio.api.modules.playback.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ProgressRequest {
    private UUID contentId;
    private UUID episodeId;

    @NotNull @Min(0)
    private Integer progressSeconds;

    @NotNull @Min(1)
    private Integer durationSeconds;

    private String deviceType;
}
```

- [ ] **Step 5: ContinueWatchingItem**

```java
package com.tinniestudio.api.modules.playback.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class ContinueWatchingItem {
    private final UUID contentId;
    private final UUID episodeId;       // null for movies
    private final String title;
    private final String thumbnailUrl;  // null until Batch 12 enriches it
    private final int progressSeconds;
    private final int durationSeconds;
    private final BigDecimal completionPercentage;
    private final Instant lastWatchedAt;
}
```

- [ ] **Step 6: Compile**

```bash
./gradlew :api-service:compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/playback/dto/
git commit -m "feat(playback): add playback DTOs (AccessCheckResponse, PlaybackManifestResponse, ProgressRequest, ContinueWatchingItem)"
```

---

## Task 3: PlaybackService — access check + manifest delivery (TDD)

**Files:**
- Create: `modules/playback/service/PlaybackService.java`
- Create: `modules/playback/service/PlaybackServiceImpl.java`
- Create: `test/.../playback/service/PlaybackServiceTest.java`

- [ ] **Step 1: Write failing tests**

Create `api-service/src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java`:

```java
package com.tinniestudio.api.modules.playback.service;

import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.playback.dto.*;
import com.tinniestudio.api.modules.playback.repository.WatchProgressRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaybackServiceTest {

    @Mock ContentRepository contentRepo;
    @Mock UserSubscriptionRepository subscriptionRepo;
    @Mock VideoAssetRepository videoAssetRepo;
    @Mock WatchProgressRepository watchProgressRepo;
    @Mock EpisodeRepository episodeRepo;
    @Mock RabbitTemplate rabbitTemplate;

    private PlaybackServiceImpl service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getCdn().setBaseUrl("http://cdn.test");
        service = new PlaybackServiceImpl(
            contentRepo, subscriptionRepo, videoAssetRepo,
            watchProgressRepo, episodeRepo, rabbitTemplate, props
        );
    }

    @Nested
    class checkAccess {

        @Test
        void deniesWhenContentNotPublished() {
            UUID userId = UUID.randomUUID();
            Content content = new Content();
            content.setStatus(ContentStatus.DRAFT);
            when(contentRepo.findById(any())).thenReturn(Optional.of(content));

            AccessCheckResponse resp = service.checkAccess(userId, UUID.randomUUID());

            assertThat(resp.isHasAccess()).isFalse();
            assertThat(resp.getReason()).isEqualTo("CONTENT_NOT_PUBLISHED");
        }

        @Test
        void deniesWhenNoActiveSubscription() {
            UUID userId = UUID.randomUUID();
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);
            when(contentRepo.findById(any())).thenReturn(Optional.of(content));
            when(subscriptionRepo.findByUserIdAndStatus(eq(userId), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.empty());

            AccessCheckResponse resp = service.checkAccess(userId, UUID.randomUUID());

            assertThat(resp.isHasAccess()).isFalse();
            assertThat(resp.getReason()).isEqualTo("NO_ACTIVE_SUBSCRIPTION");
        }

        @Test
        void grantsWhenPublishedAndSubscriptionActive() {
            UUID userId = UUID.randomUUID();
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);
            UserSubscription sub = new UserSubscription();
            sub.setStatus(SubscriptionStatus.ACTIVE);
            when(contentRepo.findById(any())).thenReturn(Optional.of(content));
            when(subscriptionRepo.findByUserIdAndStatus(eq(userId), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.of(sub));

            AccessCheckResponse resp = service.checkAccess(userId, UUID.randomUUID());

            assertThat(resp.isHasAccess()).isTrue();
        }
    }

    @Nested
    class getContentManifest {

        @Test
        void throwsWhenAccessDenied() {
            UUID userId = UUID.randomUUID();
            Content content = new Content();
            content.setStatus(ContentStatus.DRAFT);
            when(contentRepo.findById(any())).thenReturn(Optional.of(content));

            assertThatThrownBy(() -> service.getContentManifest(userId, UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }

        @Test
        void throwsWhenNoReadyVideoAsset() {
            UUID userId = UUID.randomUUID();
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);
            UserSubscription sub = new UserSubscription();
            sub.setStatus(SubscriptionStatus.ACTIVE);
            when(contentRepo.findById(any())).thenReturn(Optional.of(content));
            when(subscriptionRepo.findByUserIdAndStatus(any(), any())).thenReturn(Optional.of(sub));
            when(videoAssetRepo.findTopByContent_IdAndAssetTypeAndProcessingStatus(
                any(), eq(VideoAssetType.MAIN_VIDEO), eq(ProcessingStatus.READY)))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getContentManifest(userId, UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }

        @Test
        void returnsManifestWithSubtitlesAndResumeAt() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();

            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);

            UserSubscription sub = new UserSubscription();
            sub.setStatus(SubscriptionStatus.ACTIVE);

            Subtitle sub1 = new Subtitle();
            sub1.setLanguageCode("en");
            sub1.setLabel("English");
            sub1.setFileUrl("subs/en.vtt");
            sub1.setDefault(false);

            VideoAsset asset = new VideoAsset();
            asset.setManifestUrl("processed/abc/master.m3u8");
            asset.setDurationSeconds(3600);
            asset.setSubtitles(List.of(sub1));

            WatchProgress progress = new WatchProgress();
            progress.setProgressSeconds(120);

            when(contentRepo.findById(contentId)).thenReturn(Optional.of(content));
            when(subscriptionRepo.findByUserIdAndStatus(eq(userId), eq(SubscriptionStatus.ACTIVE)))
                .thenReturn(Optional.of(sub));
            when(videoAssetRepo.findTopByContent_IdAndAssetTypeAndProcessingStatus(
                eq(contentId), eq(VideoAssetType.MAIN_VIDEO), eq(ProcessingStatus.READY)))
                .thenReturn(Optional.of(asset));
            when(watchProgressRepo.findMovieProgress(userId, contentId))
                .thenReturn(Optional.of(progress));

            PlaybackManifestResponse resp = service.getContentManifest(userId, contentId);

            assertThat(resp.getManifestUrl()).isEqualTo("http://cdn.test/processed/abc/master.m3u8");
            assertThat(resp.getDuration()).isEqualTo(3600);
            assertThat(resp.getResumeAt()).isEqualTo(120);
            assertThat(resp.getSubtitles()).hasSize(1);
            assertThat(resp.getSubtitles().get(0).getLanguageCode()).isEqualTo("en");
        }
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.service.PlaybackServiceTest"
```

Expected: compilation error (PlaybackServiceImpl doesn't exist yet)

- [ ] **Step 3: Create PlaybackService interface**

```java
package com.tinniestudio.api.modules.playback.service;

import com.tinniestudio.api.modules.playback.dto.*;
import java.util.List;
import java.util.UUID;

public interface PlaybackService {
    AccessCheckResponse checkAccess(UUID userId, UUID contentId);
    PlaybackManifestResponse getContentManifest(UUID userId, UUID contentId);
    PlaybackManifestResponse getEpisodeManifest(UUID userId, UUID episodeId);
    void recordProgress(UUID userId, ProgressRequest request);
    List<ContinueWatchingItem> getContinueWatching(UUID userId);
}
```

- [ ] **Step 4: Implement PlaybackServiceImpl**

```java
package com.tinniestudio.api.modules.playback.service;

import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.playback.dto.*;
import com.tinniestudio.api.modules.playback.repository.WatchProgressRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybackServiceImpl implements PlaybackService {

    private final ContentRepository contentRepo;
    private final UserSubscriptionRepository subscriptionRepo;
    private final VideoAssetRepository videoAssetRepo;
    private final WatchProgressRepository watchProgressRepo;
    private final EpisodeRepository episodeRepo;
    private final RabbitTemplate rabbitTemplate;
    private final AppProperties appProperties;

    @Override
    public AccessCheckResponse checkAccess(UUID userId, UUID contentId) {
        Content content = contentRepo.findById(contentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found"));

        if (content.getStatus() != ContentStatus.PUBLISHED) {
            return AccessCheckResponse.denied("CONTENT_NOT_PUBLISHED");
        }

        boolean hasActiveSub = subscriptionRepo
            .findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
            .isPresent();

        if (!hasActiveSub) {
            return AccessCheckResponse.denied("NO_ACTIVE_SUBSCRIPTION");
        }

        return AccessCheckResponse.granted();
    }

    @Override
    public PlaybackManifestResponse getContentManifest(UUID userId, UUID contentId) {
        AccessCheckResponse access = checkAccess(userId, contentId);
        if (!access.isHasAccess()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, access.getReason());
        }

        VideoAsset asset = videoAssetRepo
            .findTopByContent_IdAndAssetTypeAndProcessingStatus(
                contentId, VideoAssetType.MAIN_VIDEO, ProcessingStatus.READY)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No ready video for this content"));

        Integer resumeAt = watchProgressRepo.findMovieProgress(userId, contentId)
            .map(WatchProgress::getProgressSeconds)
            .orElse(null);

        return buildManifestResponse(asset, resumeAt);
    }

    @Override
    public PlaybackManifestResponse getEpisodeManifest(UUID userId, UUID episodeId) {
        Episode episode = episodeRepo.findById(episodeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found"));

        // Access is checked against the parent content
        UUID contentId = episode.getSeason().getContent().getId();
        AccessCheckResponse access = checkAccess(userId, contentId);
        if (!access.isHasAccess()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, access.getReason());
        }

        VideoAsset asset = videoAssetRepo
            .findTopByEpisode_IdAndAssetTypeAndProcessingStatus(
                episodeId, VideoAssetType.MAIN_VIDEO, ProcessingStatus.READY)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No ready video for this episode"));

        Integer resumeAt = watchProgressRepo.findByUserIdAndEpisodeId(userId, episodeId)
            .map(WatchProgress::getProgressSeconds)
            .orElse(null);

        return buildManifestResponse(asset, resumeAt);
    }

    @Override
    @Transactional
    public void recordProgress(UUID userId, ProgressRequest req) {
        if (req.getContentId() == null && req.getEpisodeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentId or episodeId required");
        }

        WatchProgress progress;
        if (req.getEpisodeId() != null) {
            progress = watchProgressRepo.findByUserIdAndEpisodeId(userId, req.getEpisodeId())
                .orElseGet(() -> {
                    WatchProgress w = new WatchProgress();
                    w.setUserId(userId);
                    w.setEpisodeId(req.getEpisodeId());
                    return w;
                });
        } else {
            progress = watchProgressRepo.findMovieProgress(userId, req.getContentId())
                .orElseGet(() -> {
                    WatchProgress w = new WatchProgress();
                    w.setUserId(userId);
                    w.setContentId(req.getContentId());
                    return w;
                });
        }

        progress.setProgressSeconds(req.getProgressSeconds());
        progress.setDurationSeconds(req.getDurationSeconds());
        progress.setDeviceType(req.getDeviceType());
        progress.setLastWatchedAt(Instant.now());

        BigDecimal percentage = BigDecimal.valueOf(req.getProgressSeconds())
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(req.getDurationSeconds()), 2, RoundingMode.HALF_UP);
        progress.setCompletionPercentage(percentage);
        progress.setCompleted(percentage.compareTo(BigDecimal.valueOf(90)) >= 0);

        watchProgressRepo.save(progress);

        // Best-effort analytics publish (analytics.ingest queue built in Batch 16)
        try {
            rabbitTemplate.convertAndSend("analytics.ingest", Map.of(
                "type", "PROGRESS_TRACKED",
                "userId", userId.toString(),
                "contentId", req.getContentId() != null ? req.getContentId().toString() : "",
                "episodeId", req.getEpisodeId() != null ? req.getEpisodeId().toString() : "",
                "progressSeconds", req.getProgressSeconds(),
                "durationSeconds", req.getDurationSeconds()
            ));
        } catch (Exception e) {
            log.warn("Analytics publish failed (non-critical): {}", e.getMessage());
        }
    }

    @Override
    public List<ContinueWatchingItem> getContinueWatching(UUID userId) {
        List<WatchProgress> progresses = watchProgressRepo
            .findByUserIdAndCompletedFalseOrderByLastWatchedAtDesc(userId, PageRequest.of(0, 20));

        Set<UUID> contentIds = progresses.stream()
            .filter(p -> p.getContentId() != null && p.getEpisodeId() == null)
            .map(WatchProgress::getContentId)
            .collect(Collectors.toSet());

        Set<UUID> episodeIds = progresses.stream()
            .filter(p -> p.getEpisodeId() != null)
            .map(WatchProgress::getEpisodeId)
            .collect(Collectors.toSet());

        Map<UUID, Content> contentMap = contentRepo.findAllById(contentIds).stream()
            .collect(Collectors.toMap(Content::getId, c -> c));
        Map<UUID, Episode> episodeMap = episodeRepo.findAllById(episodeIds).stream()
            .collect(Collectors.toMap(Episode::getId, e -> e));

        return progresses.stream()
            .map(p -> {
                String title;
                if (p.getEpisodeId() != null) {
                    Episode ep = episodeMap.get(p.getEpisodeId());
                    title = ep != null ? ep.getTitle() : "Unknown Episode";
                } else {
                    Content c = contentMap.get(p.getContentId());
                    title = c != null ? c.getTitle() : "Unknown Content";
                }
                return new ContinueWatchingItem(
                    p.getContentId(),
                    p.getEpisodeId(),
                    title,
                    null,  // thumbnailUrl — enriched in Batch 12
                    p.getProgressSeconds() != null ? p.getProgressSeconds() : 0,
                    p.getDurationSeconds() != null ? p.getDurationSeconds() : 0,
                    p.getCompletionPercentage(),
                    p.getLastWatchedAt()
                );
            })
            .collect(Collectors.toList());
    }

    private PlaybackManifestResponse buildManifestResponse(VideoAsset asset, Integer resumeAt) {
        String cdnBase = appProperties.getCdn().getBaseUrl();
        String manifestUrl = cdnBase + "/" + asset.getManifestUrl();

        List<SubtitleDto> subtitles = asset.getSubtitles().stream()
            .map(s -> new SubtitleDto(s.getLanguageCode(), s.getLabel(), s.getFileUrl(), Boolean.TRUE.equals(s.getDefault())))
            .collect(Collectors.toList());

        return new PlaybackManifestResponse(manifestUrl, subtitles, resumeAt, asset.getDurationSeconds());
    }
}
```

- [ ] **Step 5: Run tests — verify they pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.service.PlaybackServiceTest"
```

Expected: all tests PASS

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/playback/service/ \
  api-service/src/test/java/com/tinniestudio/api/modules/playback/service/
git commit -m "feat(playback): implement PlaybackService with access check and manifest delivery (TDD)"
```

---

## Task 4: PlaybackService — progress tracking + continue-watching (TDD)

**Files:**
- Modify: `test/.../playback/service/PlaybackServiceTest.java` (add new test cases)

The `recordProgress` and `getContinueWatching` methods were already implemented in Task 3. Add tests to cover them.

- [ ] **Step 1: Add recordProgress tests to PlaybackServiceTest**

Add a `@Nested class recordProgress` block:

```java
@Nested
class recordProgress {

    @Test
    void throwsWhenNeitherContentIdNorEpisodeIdProvided() {
        ProgressRequest req = new ProgressRequest();
        req.setProgressSeconds(60);
        req.setDurationSeconds(3600);

        assertThatThrownBy(() -> service.recordProgress(UUID.randomUUID(), req))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void upsertsNewMovieProgressRecord() {
        UUID userId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        ProgressRequest req = new ProgressRequest();
        req.setContentId(contentId);
        req.setProgressSeconds(900);
        req.setDurationSeconds(3600);

        when(watchProgressRepo.findMovieProgress(userId, contentId)).thenReturn(Optional.empty());
        when(watchProgressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordProgress(userId, req);

        ArgumentCaptor<WatchProgress> captor = ArgumentCaptor.forClass(WatchProgress.class);
        verify(watchProgressRepo).save(captor.capture());
        WatchProgress saved = captor.getValue();
        assertThat(saved.getProgressSeconds()).isEqualTo(900);
        assertThat(saved.getCompletionPercentage()).isEqualByComparingTo("25.00");
        assertThat(saved.getCompleted()).isFalse();
    }

    @Test
    void setsCompletedTrueWhenProgressExceeds90Percent() {
        UUID userId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        ProgressRequest req = new ProgressRequest();
        req.setContentId(contentId);
        req.setProgressSeconds(3300);
        req.setDurationSeconds(3600);  // 91.67%

        when(watchProgressRepo.findMovieProgress(userId, contentId)).thenReturn(Optional.empty());
        when(watchProgressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordProgress(userId, req);

        ArgumentCaptor<WatchProgress> captor = ArgumentCaptor.forClass(WatchProgress.class);
        verify(watchProgressRepo).save(captor.capture());
        assertThat(captor.getValue().getCompleted()).isTrue();
    }
}
```

Also add an `@Nested class getContinueWatching` block:

```java
@Nested
class getContinueWatching {

    @Test
    void returnsEmptyListWhenNoProgress() {
        UUID userId = UUID.randomUUID();
        when(watchProgressRepo.findByUserIdAndCompletedFalseOrderByLastWatchedAtDesc(eq(userId), any()))
            .thenReturn(List.of());

        List<ContinueWatchingItem> result = service.getContinueWatching(userId);

        assertThat(result).isEmpty();
    }

    @Test
    void mapsMovieProgressToItem() {
        UUID userId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        WatchProgress p = new WatchProgress();
        p.setContentId(contentId);
        p.setProgressSeconds(300);
        p.setDurationSeconds(3600);
        p.setCompletionPercentage(new java.math.BigDecimal("8.33"));
        p.setLastWatchedAt(Instant.now());

        Content content = new Content();
        content.setTitle("My Movie");

        when(watchProgressRepo.findByUserIdAndCompletedFalseOrderByLastWatchedAtDesc(eq(userId), any()))
            .thenReturn(List.of(p));
        when(contentRepo.findAllById(any())).thenReturn(List.of(content));
        when(episodeRepo.findAllById(any())).thenReturn(List.of());

        // Set content id via reflection or direct setter
        // Content uses BaseEntity which has getId() — need to mock or use setId if available
        // If Content.id is set by BaseEntity and not directly settable, use a spy
        // Alternative: verify title fallback with known IDs
        List<ContinueWatchingItem> result = service.getContinueWatching(userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProgressSeconds()).isEqualTo(300);
    }
}
```

Note: If `Content` and `Episode` IDs are set internally (UUID generated by JPA), you may need to use `ReflectionTestUtils.setField(content, "id", contentId)` to inject the ID for the map lookup. Use this pattern:

```java
org.springframework.test.util.ReflectionTestUtils.setField(content, "id", contentId);
```

- [ ] **Step 2: Run tests**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.service.PlaybackServiceTest"
```

Expected: all tests PASS

- [ ] **Step 3: Commit**

```bash
git add api-service/src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java
git commit -m "test(playback): add progress tracking and continue-watching unit tests"
```

---

## Task 5: PlaybackController (TDD)

**Files:**
- Create: `modules/playback/controller/PlaybackController.java`
- Create: `test/.../playback/controller/PlaybackControllerTest.java`

- [ ] **Step 1: Write failing controller test**

Create `api-service/src/test/java/com/tinniestudio/api/modules/playback/controller/PlaybackControllerTest.java`:

```java
package com.tinniestudio.api.modules.playback.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.playback.dto.*;
import com.tinniestudio.api.modules.playback.service.PlaybackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PlaybackController.class)
class PlaybackControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean PlaybackService playbackService;

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void getAccessReturns200WithAccessResponse() throws Exception {
        UUID contentId = UUID.randomUUID();
        when(playbackService.checkAccess(any(), any()))
            .thenReturn(AccessCheckResponse.granted());

        mockMvc.perform(get("/playback/{contentId}/access", contentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasAccess").value(true));
    }

    @Test
    void getAccessRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/playback/{contentId}/access", UUID.randomUUID()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void getManifestReturns200() throws Exception {
        UUID contentId = UUID.randomUUID();
        PlaybackManifestResponse resp = new PlaybackManifestResponse(
            "http://cdn.test/processed/abc/master.m3u8", List.of(), 0, 3600);
        when(playbackService.getContentManifest(any(), any())).thenReturn(resp);

        mockMvc.perform(get("/playback/{contentId}/manifest", contentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.manifestUrl").value("http://cdn.test/processed/abc/master.m3u8"));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void postProgressReturns204() throws Exception {
        ProgressRequest req = new ProgressRequest();
        req.setContentId(UUID.randomUUID());
        req.setProgressSeconds(300);
        req.setDurationSeconds(3600);

        doNothing().when(playbackService).recordProgress(any(), any());

        mockMvc.perform(post("/playback/progress")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void getContinueWatchingReturns200() throws Exception {
        when(playbackService.getContinueWatching(any())).thenReturn(List.of());

        mockMvc.perform(get("/playback/continue-watching"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }
}
```

- [ ] **Step 2: Run — verify compile failure**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.controller.PlaybackControllerTest"
```

Expected: compilation error (PlaybackController doesn't exist)

- [ ] **Step 3: Implement PlaybackController**

```java
package com.tinniestudio.api.modules.playback.controller;

import com.tinniestudio.api.modules.playback.dto.*;
import com.tinniestudio.api.modules.playback.service.PlaybackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/playback")
@RequiredArgsConstructor
public class PlaybackController {

    private final PlaybackService playbackService;

    @GetMapping("/{contentId}/access")
    public ResponseEntity<AccessCheckResponse> checkAccess(
            @PathVariable UUID contentId,
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(playbackService.checkAccess(userId, contentId));
    }

    @GetMapping("/{contentId}/manifest")
    public ResponseEntity<PlaybackManifestResponse> getContentManifest(
            @PathVariable UUID contentId,
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(playbackService.getContentManifest(userId, contentId));
    }

    @GetMapping("/episode/{episodeId}/manifest")
    public ResponseEntity<PlaybackManifestResponse> getEpisodeManifest(
            @PathVariable UUID episodeId,
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(playbackService.getEpisodeManifest(userId, episodeId));
    }

    @PostMapping("/progress")
    public ResponseEntity<Void> recordProgress(
            @Valid @RequestBody ProgressRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = UUID.fromString(principal.getUsername());
        playbackService.recordProgress(userId, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/continue-watching")
    public ResponseEntity<List<ContinueWatchingItem>> getContinueWatching(
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(playbackService.getContinueWatching(userId));
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.controller.PlaybackControllerTest"
```

Expected: all tests PASS

- [ ] **Step 5: Run full test suite**

```bash
./gradlew :api-service:test
```

Expected: `BUILD SUCCESSFUL`, 0 failures (requires Docker for Testcontainers)

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/playback/controller/ \
  api-service/src/test/java/com/tinniestudio/api/modules/playback/controller/
git commit -m "feat(playback): add PlaybackController with all 5 endpoints (TDD)"
```

---

## Self-Review Checklist

### Spec coverage

| Spec requirement | Covered by |
|-----------------|-----------|
| `GET /playback/{contentId}/access` | Task 5 |
| `GET /playback/{contentId}/manifest` | Task 5 |
| `GET /playback/episode/{episodeId}/manifest` | Task 5 |
| `POST /playback/progress` | Task 5 |
| `GET /playback/continue-watching` | Task 5 |
| Content must be PUBLISHED | Task 3 (`checkAccess`) |
| Subscription must be ACTIVE | Task 3 (`checkAccess`) |
| Manifest URL = CDN base + manifest key | Task 3 (`buildManifestResponse`) |
| Subtitles returned in manifest | Task 3 |
| `resumeAt` from watch progress | Task 3 |
| Progress upsert | Task 4 |
| `completion_percentage` computed | Task 4 |
| `completed=true` when ≥90% | Task 4 |
| Analytics publish (best-effort) | Task 4 |
| Continue-watching: incomplete, limit 20 | Task 4 |
| All endpoints require authentication | Task 5 (controller test) |

### Skipped from spec (deferred)
- Geo restriction check — `Content` has no `countryRestrictions` field; add in Batch 9
- `plan.videoQuality` content gate — `Content` has no `requiredQuality` field; deferred
- `watch_progress.video_asset_id` not set in `recordProgress` — add when needed

### Known dependency
- `Episode.getSeason().getContent()` in `getEpisodeManifest` will trigger lazy-loading. Annotate the `getEpisodeManifest` call site with `@Transactional` in the service, or use `@EntityGraph` on the episode fetch. The simplest fix: mark `PlaybackServiceImpl.getEpisodeManifest` with `@Transactional(readOnly = true)`.
