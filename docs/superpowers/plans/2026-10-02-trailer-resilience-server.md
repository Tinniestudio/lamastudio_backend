# Trailer Resilience (Server) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 500 on the trailer manifest endpoint and add season/episode trailer manifest endpoints, so every level (content, season, episode) can serve its own trailer without colliding with the others.

**Architecture:** `VideoAsset.content` is denormalized onto every asset regardless of its most specific target, so the existing `findByContent_IdAndAssetTypeAndIsActiveTrue` query can match a season- or episode-scoped trailer too, causing a two-row `IncorrectResultSizeDataAccessException` → 500. The fix adds level-scoped repository queries (content query additionally requires `season IS NULL AND episode IS NULL`) and two new public manifest endpoints mirroring the existing content trailer endpoint.

**Tech Stack:** Spring Boot, Spring Data JPA, JUnit 5, Mockito, AssertJ, Testcontainers (Postgres).

**Companion plans:** `tinniestudio-client-web/docs/superpowers/plans/2026-10-02-trailer-resilience-client-web.md` and `tinniestudio-partner-web/docs/superpowers/plans/2026-10-02-trailer-resilience-partner-web.md` depend on the endpoints built here. Do this plan first.

---

### Task 1: Scoped `VideoAssetRepository` queries + regression test for the 500

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/upload/repository/VideoAssetRepository.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/upload/repository/VideoAssetRepositoryTrailerScopeTest.java` (new)

- [ ] **Step 1: Write the failing regression test**

This hits a real Postgres instance (Testcontainers), not mocks — the bug is specifically about how a Spring Data derived query matches rows, which a Mockito-mocked repository can't exercise.

```java
package com.tinniestudio.api.modules.upload.repository;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.MainCategory;
import com.tinniestudio.api.shared.entity.DomainEnums.VideoAssetType;
import com.tinniestudio.api.shared.entity.Season;
import com.tinniestudio.api.shared.entity.VideoAsset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-DB regression test for the trailer-manifest 500: a content-level trailer and a
 * season-level trailer can both be isActive=true while sharing the same content_id (content is
 * denormalized onto every asset — see VideoActivationService javadoc). The original
 * findByContent_IdAndAssetTypeAndIsActiveTrue query matched both rows and threw
 * IncorrectResultSizeDataAccessException. This proves the new scoped queries don't.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureTestEntityManager
@Testcontainers
@ActiveProfiles("test")
class VideoAssetRepositoryTrailerScopeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_trailer_scope_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired private VideoAssetRepository videoAssetRepo;
    @Autowired private SeasonRepository seasonRepo;
    @Autowired private ContentRepository contentRepo;
    @Autowired private ContentTypeRepository contentTypeRepo;

    private Content newContent() {
        ContentType showType = contentTypeRepo.findBySlug("tv-show")
            .orElseGet(() -> contentTypeRepo.findBySlug("movie")
                .orElseThrow(() -> new IllegalStateException("No seeded ContentType found")));
        Content content = new Content();
        content.setTitle("Trailer Scope Test Show " + System.nanoTime());
        content.setContentType(showType);
        content.setStatus(ContentStatus.PUBLISHED);
        content.setMainCategory(MainCategory.TV_SHOWS);
        content.setCreatedBy(UUID.randomUUID());
        return contentRepo.saveAndFlush(content);
    }

    private VideoAsset newTrailerAsset(Content content, Season season, boolean active) {
        VideoAsset asset = new VideoAsset();
        asset.setContent(content);
        asset.setSeason(season);
        asset.setAssetType(VideoAssetType.TRAILER);
        asset.setOriginalFilename("trailer.mp4");
        asset.setStorageKey("raw/" + UUID.randomUUID() + "/trailer.mp4");
        asset.setUploadedBy(UUID.randomUUID());
        asset.setActive(active);
        return videoAssetRepo.saveAndFlush(asset);
    }

    @Test
    void contentLevelQueryIgnoresActiveSeasonTrailerSharingTheSameContentId() {
        Content content = newContent();
        Season season = new Season();
        season.setContent(content);
        season.setSeasonNumber(1);
        Season savedSeason = seasonRepo.saveAndFlush(season);

        // Content-level trailer (season = null) and a season-level trailer, both active, both
        // sharing content.getId() — this is exactly the state that caused the 500.
        VideoAsset contentTrailer = newTrailerAsset(content, null, true);
        newTrailerAsset(content, savedSeason, true);

        VideoAsset found = videoAssetRepo
            .findByContent_IdAndSeasonIsNullAndEpisodeIsNullAndAssetTypeAndIsActiveTrue(
                content.getId(), VideoAssetType.TRAILER)
            .orElseThrow();

        assertThat(found.getId()).isEqualTo(contentTrailer.getId());
    }

    @Test
    void seasonLevelQueryOnlyMatchesItsOwnSeason() {
        Content content = newContent();
        Season season = new Season();
        season.setContent(content);
        season.setSeasonNumber(1);
        Season savedSeason = seasonRepo.saveAndFlush(season);

        newTrailerAsset(content, null, true); // content-level trailer, same content_id
        VideoAsset seasonTrailer = newTrailerAsset(content, savedSeason, true);

        VideoAsset found = videoAssetRepo
            .findBySeason_IdAndAssetTypeAndIsActiveTrue(savedSeason.getId(), VideoAssetType.TRAILER)
            .orElseThrow();

        assertThat(found.getId()).isEqualTo(seasonTrailer.getId());
    }

    @Test
    void oldUnscopedQueryStillThrowsOnTheSameData_documentingTheBug() {
        Content content = newContent();
        Season season = new Season();
        season.setContent(content);
        season.setSeasonNumber(1);
        Season savedSeason = seasonRepo.saveAndFlush(season);

        newTrailerAsset(content, null, true);
        newTrailerAsset(content, savedSeason, true);

        assertThatThrownBy(() ->
            videoAssetRepo.findByContent_IdAndAssetTypeAndIsActiveTrue(content.getId(), VideoAssetType.TRAILER))
            .isInstanceOf(IncorrectResultSizeDataAccessException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile (methods don't exist yet)**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.upload.repository.VideoAssetRepositoryTrailerScopeTest"`
Expected: compilation failure — `findByContent_IdAndSeasonIsNullAndEpisodeIsNullAndAssetTypeAndIsActiveTrue` and `findBySeason_IdAndAssetTypeAndIsActiveTrue` are not defined on `VideoAssetRepository`.

- [ ] **Step 3: Add the two scoped query methods to `VideoAssetRepository`**

Add directly below the existing `findByEpisode_IdAndAssetTypeAndIsActiveTrue` declaration (`VideoAssetRepository.java:31`):

```java
    Optional<VideoAsset> findByEpisode_IdAndAssetTypeAndIsActiveTrue(UUID episodeId, VideoAssetType assetType);

    /**
     * Scoped to a TRUE content-level asset (season and episode both null) — content is
     * denormalized onto every season/episode-linked asset too, so the unscoped
     * findByContent_IdAndAssetTypeAndIsActiveTrue can match a season- or episode-level asset
     * sharing the same content_id and throw IncorrectResultSizeDataAccessException when more
     * than one is active. See VideoAssetRepositoryTrailerScopeTest.
     */
    Optional<VideoAsset> findByContent_IdAndSeasonIsNullAndEpisodeIsNullAndAssetTypeAndIsActiveTrue(
            UUID contentId, VideoAssetType assetType);

    Optional<VideoAsset> findBySeason_IdAndAssetTypeAndIsActiveTrue(UUID seasonId, VideoAssetType assetType);
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.upload.repository.VideoAssetRepositoryTrailerScopeTest"`
Expected: 3 tests pass (requires Docker available for Testcontainers).

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/upload/repository/VideoAssetRepository.java api-service/src/test/java/com/tinniestudio/api/modules/upload/repository/VideoAssetRepositoryTrailerScopeTest.java
git commit -m "fix: scope trailer queries by level so content/season trailers can't collide"
```

---

### Task 2: Switch `getTrailerManifest` to the scoped query + defense-in-depth catch

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java:119-131`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java` (existing `getTrailerManifest` nested class)

- [ ] **Step 1: Write the failing unit test**

Add to the existing `getTrailerManifest` nested class in `PlaybackServiceTest.java` (after `throws404WhenNoTrailerAsset`, before `returnsManifestWithNullResumeAt`):

```java
        @Test
        void usesScopedQuery_notTheUnscopedOne() {
            UUID contentId = UUID.randomUUID();
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);

            VideoAsset asset = new VideoAsset();
            asset.setManifestUrl("processed/trailer/master.m3u8");
            asset.setDurationSeconds(90);
            asset.setSubtitles(List.of());

            when(contentRepo.findById(contentId)).thenReturn(Optional.of(content));
            when(videoAssetRepo.findByContent_IdAndSeasonIsNullAndEpisodeIsNullAndAssetTypeAndIsActiveTrue(
                eq(contentId), eq(VideoAssetType.TRAILER)))
                .thenReturn(Optional.of(asset));

            PlaybackManifestResponse resp = service.getTrailerManifest(contentId);

            assertThat(resp.getManifestUrl()).isEqualTo("http://cdn.test/processed/trailer/master.m3u8");
            verify(videoAssetRepo, never()).findByContent_IdAndAssetTypeAndIsActiveTrue(any(), any());
        }

        @Test
        void returns404_notThrows500_whenMultipleActiveTrailersExistAcrossLevels() {
            UUID contentId = UUID.randomUUID();
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);

            when(contentRepo.findById(contentId)).thenReturn(Optional.of(content));
            when(videoAssetRepo.findByContent_IdAndSeasonIsNullAndEpisodeIsNullAndAssetTypeAndIsActiveTrue(
                eq(contentId), eq(VideoAssetType.TRAILER)))
                .thenThrow(new org.springframework.dao.IncorrectResultSizeDataAccessException(1, 2));

            assertThatThrownBy(() -> service.getTrailerManifest(contentId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
        }
```

The two existing tests `throws404WhenNoTrailerAsset` and `returnsManifestWithNullResumeAt` in the same file currently stub `findByContent_IdAndAssetTypeAndIsActiveTrue` — update both to stub `findByContent_IdAndSeasonIsNullAndEpisodeIsNullAndAssetTypeAndIsActiveTrue` instead (same method body otherwise), since the implementation is switching queries in Step 3.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.service.PlaybackServiceTest\$getTrailerManifest"`
Expected: FAIL — `usesScopedQuery_notTheUnscopedOne` and `returns404_notThrows500_whenMultipleActiveTrailersExistAcrossLevels` fail because the service still calls the old unscoped method; `throws404WhenNoTrailerAsset`/`returnsManifestWithNullResumeAt` fail because their stubs no longer match what the service calls.

- [ ] **Step 3: Update `getTrailerManifest` in `PlaybackServiceImpl`**

Replace lines 119-131:

```java
    @Override
    @Transactional(readOnly = true)
    public PlaybackManifestResponse getTrailerManifest(UUID contentId) {
        Content content = contentRepo.findById(contentId)
            .filter(c -> c.getStatus() == ContentStatus.PUBLISHED)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + contentId));

        VideoAsset asset;
        try {
            asset = videoAssetRepo
                .findByContent_IdAndSeasonIsNullAndEpisodeIsNullAndAssetTypeAndIsActiveTrue(contentId, VideoAssetType.TRAILER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No trailer available"));
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException e) {
            // Defense in depth: scoped queries should make this impossible, but if data drift
            // ever produces more than one active row at a level, fail closed to the same
            // "no trailer" 404 a viewer already expects, never a 500.
            log.warn("Multiple active TRAILER assets found for content {} — treating as unavailable", contentId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No trailer available");
        }

        return buildManifestResponse(asset, null);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.service.PlaybackServiceTest\$getTrailerManifest"`
Expected: PASS (6 tests: the 4 existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java api-service/src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java
git commit -m "fix: getTrailerManifest uses scoped query and fails closed to 404 instead of 500"
```

---

### Task 3: Add `getSeasonTrailerManifest`

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackService.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/playback/controller/PlaybackController.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/playback/controller/PlaybackControllerTest.java`

- [ ] **Step 1: Write the failing service test**

Add a new nested class to `PlaybackServiceTest.java`, after the `getTrailerManifest` class:

```java
    @Nested
    class getSeasonTrailerManifest {

        @Test
        void throws404WhenSeasonNotFound() {
            when(seasonRepo.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getSeasonTrailerManifest(UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void throws404WhenParentContentNotPublished() {
            Content content = new Content();
            content.setStatus(ContentStatus.DRAFT);
            Season season = new Season();
            season.setContent(content);
            when(seasonRepo.findById(any())).thenReturn(Optional.of(season));

            assertThatThrownBy(() -> service.getSeasonTrailerManifest(UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void throws404WhenNoActiveSeasonTrailer() {
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);
            Season season = new Season();
            season.setContent(content);
            when(seasonRepo.findById(any())).thenReturn(Optional.of(season));
            when(videoAssetRepo.findBySeason_IdAndAssetTypeAndIsActiveTrue(any(), eq(VideoAssetType.TRAILER)))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getSeasonTrailerManifest(UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void returnsManifestWithNullResumeAt() {
            UUID seasonId = UUID.randomUUID();
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);
            Season season = new Season();
            season.setContent(content);

            VideoAsset asset = new VideoAsset();
            asset.setManifestUrl("processed/season-trailer/master.m3u8");
            asset.setDurationSeconds(60);
            asset.setSubtitles(List.of());

            when(seasonRepo.findById(seasonId)).thenReturn(Optional.of(season));
            when(videoAssetRepo.findBySeason_IdAndAssetTypeAndIsActiveTrue(eq(seasonId), eq(VideoAssetType.TRAILER)))
                .thenReturn(Optional.of(asset));

            PlaybackManifestResponse resp = service.getSeasonTrailerManifest(seasonId);

            assertThat(resp.getManifestUrl()).isEqualTo("http://cdn.test/processed/season-trailer/master.m3u8");
            assertThat(resp.getResumeAt()).isNull();
        }
    }
```

Add the import `import com.tinniestudio.api.modules.season.repository.SeasonRepository;` and the field `@Mock SeasonRepository seasonRepo;` to `PlaybackServiceTest.java` (next to the existing `@Mock EpisodeRepository episodeRepo;`), and pass it into the `new PlaybackServiceImpl(...)` constructor call in `setUp()` — the exact constructor argument position is decided in Step 3 below, so come back and wire it in once that signature is final.

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.service.PlaybackServiceTest"`
Expected: compilation failure — `service.getSeasonTrailerManifest(...)` doesn't exist, `SeasonRepository seasonRepo` field/import missing.

- [ ] **Step 3: Add `getSeasonTrailerManifest` to the `PlaybackService` interface**

In `PlaybackService.java`, add after `getTrailerManifest`:

```java
    PlaybackManifestResponse getTrailerManifest(UUID contentId);
    PlaybackManifestResponse getSeasonTrailerManifest(UUID seasonId);
```

- [ ] **Step 4: Implement it in `PlaybackServiceImpl`**

Add the `SeasonRepository` dependency and the new method. In the field list (after `private final EpisodeRepository episodeRepo;`):

```java
    private final EpisodeRepository episodeRepo;
    private final com.tinniestudio.api.modules.season.repository.SeasonRepository seasonRepo;
```

Lombok's `@RequiredArgsConstructor` generates the constructor from field order, so `seasonRepo` becomes a new constructor parameter right after `episodeRepo` — update `PlaybackServiceTest.setUp()`'s `new PlaybackServiceImpl(...)` call to pass `seasonRepo` in that same position (after `episodeRepo`, before `rabbitTemplate`).

Add the method, directly after `getTrailerManifest` (after the closing brace from Task 2's edit):

```java
    @Override
    @Transactional(readOnly = true)
    public PlaybackManifestResponse getSeasonTrailerManifest(UUID seasonId) {
        Season season = seasonRepo.findById(seasonId)
            .filter(s -> s.getContent().getStatus() == ContentStatus.PUBLISHED)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + seasonId));

        VideoAsset asset;
        try {
            asset = videoAssetRepo
                .findBySeason_IdAndAssetTypeAndIsActiveTrue(seasonId, VideoAssetType.TRAILER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No trailer available"));
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException e) {
            log.warn("Multiple active TRAILER assets found for season {} — treating as unavailable", seasonId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No trailer available");
        }

        return buildManifestResponse(asset, null);
    }
```

- [ ] **Step 5: Add the controller endpoint**

In `PlaybackController.java`, add after `getTrailerManifest`:

```java
    @Operation(summary = "Get HLS manifest for a season's trailer — public, no auth or subscription required")
    @GetMapping("/manifest/season/{seasonId}/trailer")
    public ResponseEntity<PlaybackManifestResponse> getSeasonTrailerManifest(@PathVariable UUID seasonId) {
        return ResponseEntity.ok(playbackService.getSeasonTrailerManifest(seasonId));
    }
```

- [ ] **Step 6: Add the controller test**

Add to `PlaybackControllerTest.java`, after `getTrailerManifest_returnsManifestWithoutAuth`:

```java
    @Test
    @DisplayName("GET /playback/manifest/season/{seasonId}/trailer returns 200 with no auth required")
    void getSeasonTrailerManifest_returnsManifestWithoutAuth() throws Exception {
        UUID seasonId = UUID.randomUUID();
        PlaybackManifestResponse manifest = new PlaybackManifestResponse(
            "http://cdn.test/season-trailer.m3u8", List.of(), null, 60);
        when(playbackService.getSeasonTrailerManifest(any(UUID.class)))
            .thenReturn(manifest);

        mockMvc.perform(getWithContext("/playback/manifest/season/" + seasonId + "/trailer"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.manifestUrl").value("http://cdn.test/season-trailer.m3u8"));
    }
```

- [ ] **Step 7: Run all playback tests to verify they pass**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.*"`
Expected: PASS — all `PlaybackServiceTest` and `PlaybackControllerTest` tests green.

- [ ] **Step 8: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/playback/ api-service/src/test/java/com/tinniestudio/api/modules/playback/
git commit -m "feat: add season trailer manifest endpoint"
```

---

### Task 4: Add `getEpisodeTrailerManifest`

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackService.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/playback/controller/PlaybackController.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/playback/controller/PlaybackControllerTest.java`

- [ ] **Step 1: Write the failing service test**

Add a new nested class to `PlaybackServiceTest.java`, after `getSeasonTrailerManifest`:

```java
    @Nested
    class getEpisodeTrailerManifest {

        @Test
        void throws404WhenEpisodeNotFound() {
            when(episodeRepo.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getEpisodeTrailerManifest(UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void throws404WhenParentContentNotPublished() {
            Content content = new Content();
            content.setStatus(ContentStatus.DRAFT);
            Season season = new Season();
            season.setContent(content);
            Episode episode = new Episode();
            episode.setSeason(season);
            when(episodeRepo.findById(any())).thenReturn(Optional.of(episode));

            assertThatThrownBy(() -> service.getEpisodeTrailerManifest(UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void throws404WhenNoActiveEpisodeTrailer() {
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);
            Season season = new Season();
            season.setContent(content);
            Episode episode = new Episode();
            episode.setSeason(season);
            when(episodeRepo.findById(any())).thenReturn(Optional.of(episode));
            when(videoAssetRepo.findByEpisode_IdAndAssetTypeAndIsActiveTrue(any(), eq(VideoAssetType.TRAILER)))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getEpisodeTrailerManifest(UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void returnsManifestWithNullResumeAt() {
            UUID episodeId = UUID.randomUUID();
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);
            Season season = new Season();
            season.setContent(content);
            Episode episode = new Episode();
            episode.setSeason(season);

            VideoAsset asset = new VideoAsset();
            asset.setManifestUrl("processed/episode-trailer/master.m3u8");
            asset.setDurationSeconds(45);
            asset.setSubtitles(List.of());

            when(episodeRepo.findById(episodeId)).thenReturn(Optional.of(episode));
            when(videoAssetRepo.findByEpisode_IdAndAssetTypeAndIsActiveTrue(eq(episodeId), eq(VideoAssetType.TRAILER)))
                .thenReturn(Optional.of(asset));

            PlaybackManifestResponse resp = service.getEpisodeTrailerManifest(episodeId);

            assertThat(resp.getManifestUrl()).isEqualTo("http://cdn.test/processed/episode-trailer/master.m3u8");
            assertThat(resp.getResumeAt()).isNull();
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.service.PlaybackServiceTest"`
Expected: compilation failure — `getEpisodeTrailerManifest` doesn't exist on the service.

- [ ] **Step 3: Add `getEpisodeTrailerManifest` to the `PlaybackService` interface**

```java
    PlaybackManifestResponse getSeasonTrailerManifest(UUID seasonId);
    PlaybackManifestResponse getEpisodeTrailerManifest(UUID episodeId);
```

- [ ] **Step 4: Implement it in `PlaybackServiceImpl`**

Add directly after `getSeasonTrailerManifest`:

```java
    @Override
    @Transactional(readOnly = true)
    public PlaybackManifestResponse getEpisodeTrailerManifest(UUID episodeId) {
        Episode episode = episodeRepo.findById(episodeId)
            .filter(e -> e.getSeason().getContent().getStatus() == ContentStatus.PUBLISHED)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + episodeId));

        VideoAsset asset;
        try {
            asset = videoAssetRepo
                .findByEpisode_IdAndAssetTypeAndIsActiveTrue(episodeId, VideoAssetType.TRAILER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No trailer available"));
        } catch (org.springframework.dao.IncorrectResultSizeDataAccessException e) {
            log.warn("Multiple active TRAILER assets found for episode {} — treating as unavailable", episodeId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No trailer available");
        }

        return buildManifestResponse(asset, null);
    }
```

- [ ] **Step 5: Add the controller endpoint**

In `PlaybackController.java`, add after `getSeasonTrailerManifest`:

```java
    @Operation(summary = "Get HLS manifest for an episode's trailer — public, no auth or subscription required")
    @GetMapping("/manifest/episode/{episodeId}/trailer")
    public ResponseEntity<PlaybackManifestResponse> getEpisodeTrailerManifest(@PathVariable UUID episodeId) {
        return ResponseEntity.ok(playbackService.getEpisodeTrailerManifest(episodeId));
    }
```

- [ ] **Step 6: Add the controller test**

Add to `PlaybackControllerTest.java`, after `getSeasonTrailerManifest_returnsManifestWithoutAuth`:

```java
    @Test
    @DisplayName("GET /playback/manifest/episode/{episodeId}/trailer returns 200 with no auth required")
    void getEpisodeTrailerManifest_returnsManifestWithoutAuth() throws Exception {
        UUID episodeId = UUID.randomUUID();
        PlaybackManifestResponse manifest = new PlaybackManifestResponse(
            "http://cdn.test/episode-trailer.m3u8", List.of(), null, 45);
        when(playbackService.getEpisodeTrailerManifest(any(UUID.class)))
            .thenReturn(manifest);

        mockMvc.perform(getWithContext("/playback/manifest/episode/" + episodeId + "/trailer"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.manifestUrl").value("http://cdn.test/episode-trailer.m3u8"));
    }
```

- [ ] **Step 7: Run the full playback test suite**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.*"`
Expected: PASS.

- [ ] **Step 8: Run the full api-service test suite to confirm nothing else broke**

Run: `./gradlew :api-service:test`
Expected: PASS (all existing tests still green — this task only added new code paths, no existing behavior changed except Task 1/2's query scoping).

- [ ] **Step 9: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/playback/ api-service/src/test/java/com/tinniestudio/api/modules/playback/
git commit -m "feat: add episode trailer manifest endpoint"
```

---

## Summary

After this plan: `VideoAssetRepository` has level-scoped trailer queries, `getTrailerManifest` no longer 500s when a content-level and season-level trailer coexist, and `PlaybackController` exposes `/playback/manifest/season/{seasonId}/trailer` and `/playback/manifest/episode/{episodeId}/trailer`, both public and both failing closed to 404 (never 500) when no trailer or a data-drift duplicate is found.
