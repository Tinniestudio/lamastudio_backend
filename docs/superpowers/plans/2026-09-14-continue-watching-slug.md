# Continue Watching Content Slug Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `contentSlug` to `ContinueWatchingItem` so continue-watching cards can build a working `/watch/{slug}` link, using entities `getContinueWatching()` already loads.

**Architecture:** One new field on an existing DTO, populated from `contentMap`/`episodeMap` (already loaded), same pattern as the earlier thumbnail-enrichment fix.

**Tech Stack:** Spring Boot, JUnit 5 + Mockito + AssertJ.

**Covers spec:** `docs/superpowers/specs/2026-09-14-continue-watching-slug-design.md`

**Important pre-existing-test hazard found during planning:** `PlaybackServiceTest`'s existing `populatesThumbnailUrlFromEpisode` test constructs an `Episode` with **no `Season` set at all**. If the implementation calls `ep.getSeason().getContent().getSlug()` unconditionally, this existing, currently-passing test will NPE the moment that code path runs, since `episode.getSeason()` is `null` in that fixture. Task 1 explicitly updates that fixture to set up a `Season`+`Content` chain — this isn't optional cleanup, it's required to avoid breaking a test that has nothing to do with slugs.

---

## File Structure

**Modify:**
- `src/main/java/com/tinniestudio/api/modules/playback/dto/ContinueWatchingItem.java`
- `src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java`
- `src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java`

---

### Task 1: Add `contentSlug`

**Files:** as listed above.

- [ ] **Step 1: Write the failing tests**

Add to `PlaybackServiceTest`'s `getContinueWatching` nested class:

```java
        @Test
        void populatesContentSlugForMovieItem() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();

            WatchProgress p = new WatchProgress();
            p.setContentId(contentId);
            p.setProgressSeconds(300);
            p.setDurationSeconds(3600);
            p.setCompletionPercentage(new java.math.BigDecimal("8.33"));
            p.setLastWatchedAt(java.time.Instant.now());

            Content content = new Content();
            content.setId(contentId);
            content.setTitle("My Movie");
            content.setSlug("my-movie");

            when(watchProgressRepo.findByUserIdAndCompletedFalseOrderByLastWatchedAtDesc(eq(userId), any()))
                .thenReturn(List.of(p));
            when(contentRepo.findAllById(any())).thenReturn(List.of(content));
            when(episodeRepo.findAllById(any())).thenReturn(List.of());

            List<ContinueWatchingItem> result = service.getContinueWatching(userId);

            assertThat(result.get(0).getContentSlug()).isEqualTo("my-movie");
        }

        @Test
        void populatesContentSlugForEpisodeItem_fromParentContent() {
            UUID userId = UUID.randomUUID();
            UUID episodeId = UUID.randomUUID();

            WatchProgress p = new WatchProgress();
            p.setEpisodeId(episodeId);
            p.setProgressSeconds(300);
            p.setDurationSeconds(1800);
            p.setCompletionPercentage(new java.math.BigDecimal("16.67"));
            p.setLastWatchedAt(java.time.Instant.now());

            Content parentContent = new Content();
            parentContent.setSlug("breaking-bad");
            Season season = new Season();
            season.setContent(parentContent);
            Episode episode = new Episode();
            episode.setId(episodeId);
            episode.setTitle("Pilot");
            episode.setSeason(season);

            when(watchProgressRepo.findByUserIdAndCompletedFalseOrderByLastWatchedAtDesc(eq(userId), any()))
                .thenReturn(List.of(p));
            when(contentRepo.findAllById(any())).thenReturn(List.of());
            when(episodeRepo.findAllById(any())).thenReturn(List.of(episode));

            List<ContinueWatchingItem> result = service.getContinueWatching(userId);

            assertThat(result.get(0).getContentSlug()).isEqualTo("breaking-bad");
        }
```

- [ ] **Step 2: Fix the pre-existing test fixture that would otherwise NPE**

Find, in the existing `populatesThumbnailUrlFromEpisode` test:
```java
            Episode episode = new Episode();
            episode.setTitle("Pilot");
            episode.setThumbnailUrl("posters/pilot-thumb.jpg");
            episode.setId(episodeId);
```

Replace with:
```java
            Season season = new Season();
            season.setContent(new Content());
            Episode episode = new Episode();
            episode.setTitle("Pilot");
            episode.setThumbnailUrl("posters/pilot-thumb.jpg");
            episode.setId(episodeId);
            episode.setSeason(season);
```

This is required, not optional — without it, this pre-existing test breaks once Step 4 makes `getContinueWatching()` unconditionally traverse `episode.getSeason()` for every episode-based item.

- [ ] **Step 3: Run to verify the new tests fail**

Run: `./gradlew :api-service:test --tests PlaybackServiceTest`
Expected: FAIL — `getContentSlug()` doesn't exist yet on `ContinueWatchingItem`.

- [ ] **Step 4: Add the field to the DTO**

Find:
```java
public class ContinueWatchingItem {
    private final UUID contentId;
    private final UUID episodeId;
    private final String title;
    private final String thumbnailUrl;
    private final int progressSeconds;
    private final int durationSeconds;
    private final BigDecimal completionPercentage;
    private final Instant lastWatchedAt;
}
```

Replace with:
```java
public class ContinueWatchingItem {
    private final UUID contentId;
    private final UUID episodeId;
    private final String title;
    private final String thumbnailUrl;
    private final String contentSlug;
    private final int progressSeconds;
    private final int durationSeconds;
    private final BigDecimal completionPercentage;
    private final Instant lastWatchedAt;
}
```

(`@AllArgsConstructor` regenerates the constructor to match — no manual constructor to update.)

- [ ] **Step 5: Populate it in `PlaybackServiceImpl.getContinueWatching()`**

Find:
```java
        return progresses.stream()
            .map(p -> {
                String title;
                String thumbnailUrl;
                if (p.getEpisodeId() != null) {
                    Episode ep = episodeMap.get(p.getEpisodeId());
                    title = ep != null ? ep.getTitle() : "Unknown Episode";
                    thumbnailUrl = ep != null ? ep.getThumbnailUrl() : null;
                } else {
                    Content c = contentMap.get(p.getContentId());
                    title = c != null ? c.getTitle() : "Unknown Content";
                    thumbnailUrl = c != null ? c.getThumbnailUrl() : null;
                }
                return new ContinueWatchingItem(
                    p.getContentId(),
                    p.getEpisodeId(),
                    title,
                    thumbnailUrl,
                    p.getProgressSeconds() != null ? p.getProgressSeconds() : 0,
                    p.getDurationSeconds() != null ? p.getDurationSeconds() : 0,
                    p.getCompletionPercentage(),
                    p.getLastWatchedAt()
                );
            })
            .toList();
```

Replace with:
```java
        return progresses.stream()
            .map(p -> {
                String title;
                String thumbnailUrl;
                String contentSlug;
                if (p.getEpisodeId() != null) {
                    Episode ep = episodeMap.get(p.getEpisodeId());
                    title = ep != null ? ep.getTitle() : "Unknown Episode";
                    thumbnailUrl = ep != null ? ep.getThumbnailUrl() : null;
                    contentSlug = ep != null ? ep.getSeason().getContent().getSlug() : null;
                } else {
                    Content c = contentMap.get(p.getContentId());
                    title = c != null ? c.getTitle() : "Unknown Content";
                    thumbnailUrl = c != null ? c.getThumbnailUrl() : null;
                    contentSlug = c != null ? c.getSlug() : null;
                }
                return new ContinueWatchingItem(
                    p.getContentId(),
                    p.getEpisodeId(),
                    title,
                    thumbnailUrl,
                    contentSlug,
                    p.getProgressSeconds() != null ? p.getProgressSeconds() : 0,
                    p.getDurationSeconds() != null ? p.getDurationSeconds() : 0,
                    p.getCompletionPercentage(),
                    p.getLastWatchedAt()
                );
            })
            .toList();
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :api-service:test --tests PlaybackServiceTest`
Expected: PASS — all tests in this class, including the pre-existing ones Step 2 touched.

- [ ] **Step 7: Full module test run**

Run: `./gradlew :api-service:test`
Expected: BUILD SUCCESSFUL modulo this repo's known pre-existing unrelated Testcontainers/Postgres-environment failures — distinguish those from anything newly introduced, the way every prior plan in this repo has done.

- [ ] **Step 8: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/playback/dto/ContinueWatchingItem.java api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java api-service/src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java
git commit -m "feat: add contentSlug to ContinueWatchingItem for working watch links"
```

---

## Self-Review Notes

- **Spec coverage:** single additive field, populated for both movie and episode items, matches the spec exactly.
- **Placeholder scan:** none — the pre-existing-test-breakage hazard (Step 2) is called out explicitly and fixed inline, not left as a TODO.
- **Type consistency:** `ContinueWatchingItem`'s constructor argument order in Step 5 matches the field declaration order from Step 4 exactly (`contentSlug` inserted right after `thumbnailUrl` in both).
