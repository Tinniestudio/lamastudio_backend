# Playback Manifest & Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a public trailer-manifest endpoint, let admins bypass the subscription check in playback access, and stop hardcoding `thumbnailUrl: null` in Continue Watching.

**Architecture:** All three changes live in `PlaybackService`/`PlaybackServiceImpl`/`PlaybackController`. The admin-bypass fix requires widening `checkAccess`'s signature from `(UUID userId, UUID contentId)` to `(UserDetails principal, UUID contentId)` — and because `getContentManifest`/`getEpisodeManifest` call `checkAccess` internally, their own signatures widen the same way, threading `principal` from the controller all the way through. This is a larger ripple than the spec doc implied; it's the correct one given the actual call graph (verified by reading the real code, not assumed).

**Tech Stack:** Spring Boot, Spring Security (`UserDetails`, `@AuthenticationPrincipal`), JUnit 5 + Mockito + AssertJ, `@WebMvcTest` + MockMvc.

**Covers specs:**
- `docs/superpowers/specs/2026-09-01-trailer-playback-manifest-design.md`
- `docs/superpowers/specs/2026-09-01-playback-access-admin-bypass-design.md`
- `docs/superpowers/specs/2026-09-01-continue-watching-thumbnail-design.md`

**Note on the admin-bypass spec's text:** it describes `checkAccess` alone changing signature, with `getContentManifest`/`getEpisodeManifest` "already having principal available" to pass through. In the real code, those two methods currently receive `UUID userId` (already extracted by the controller) — not `principal` — so making them pass `principal` into the new `checkAccess` requires widening their own signatures too, all the way from the controller. This plan implements that correctly; treat this note as superseding the spec's phrasing, not the intent.

---

## File Structure

**Modify:**
- `src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackService.java` — interface: `checkAccess`/`getContentManifest`/`getEpisodeManifest` take `UserDetails`; add `getTrailerManifest`
- `src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java` — all of the above, plus admin bypass, plus thumbnail enrichment
- `src/main/java/com/tinniestudio/api/modules/playback/controller/PlaybackController.java` — pass `principal` through instead of pre-extracting `CurrentUser.id(principal)`; add the trailer endpoint
- `src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java`
- `src/test/java/com/tinniestudio/api/modules/playback/controller/PlaybackControllerTest.java`

No new files — everything here is inside the existing `playback` module.

---

### Task 1: Widen `checkAccess`, add admin bypass

**Files:**
- Modify: `src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackService.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java`

- [ ] **Step 1: Add a `UserDetails` principal-builder helper to the test file**

Mirrors the established pattern in `SeasonServiceTest.java` (`principalFor`/`adminPrincipal`). Add near the top of the test class, after the `@BeforeEach setUp()`:

```java
    /** Non-admin principal, username = the given user id (matches JwtAuthenticationFilter's convention). */
    private org.springframework.security.core.userdetails.UserDetails principalFor(UUID userId) {
        org.springframework.security.core.userdetails.UserDetails principal =
            org.mockito.Mockito.mock(org.springframework.security.core.userdetails.UserDetails.class);
        org.mockito.Mockito.lenient().when(principal.getUsername()).thenReturn(userId.toString());
        java.util.List<org.springframework.security.core.GrantedAuthority> authorities =
            java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        org.mockito.Mockito.lenient().doReturn(authorities).when(principal).getAuthorities();
        return principal;
    }

    private org.springframework.security.core.userdetails.UserDetails adminPrincipal() {
        org.springframework.security.core.userdetails.UserDetails principal =
            org.mockito.Mockito.mock(org.springframework.security.core.userdetails.UserDetails.class);
        java.util.List<org.springframework.security.core.GrantedAuthority> authorities =
            java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        org.mockito.Mockito.lenient().doReturn(authorities).when(principal).getAuthorities();
        return principal;
    }
```

- [ ] **Step 2: Write the failing test for admin bypass**

Add to the `checkAccess` nested test class:

```java
        @Test
        void grantsAdminEvenWithoutActiveSubscription() {
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);
            when(contentRepo.findById(any())).thenReturn(Optional.of(content));

            AccessCheckResponse resp = service.checkAccess(adminPrincipal(), UUID.randomUUID());

            assertThat(resp.isHasAccess()).isTrue();
            verify(subscriptionRepo, never()).findByUserIdAndStatus(any(), any());
        }
```

- [ ] **Step 3: Update the three existing `checkAccess` tests to pass a principal instead of a raw `UUID`**

Find each of the existing three tests in the `checkAccess` nested class (`deniesWhenContentNotPublished`, `deniesWhenNoActiveSubscription`, `grantsWhenPublishedAndSubscriptionActive`) and replace their call:

```java
            AccessCheckResponse resp = service.checkAccess(userId, UUID.randomUUID());
```
with:
```java
            AccessCheckResponse resp = service.checkAccess(principalFor(userId), UUID.randomUUID());
```

(All three still declare `UUID userId = UUID.randomUUID();` at the top and use it to stub `subscriptionRepo.findByUserIdAndStatus(eq(userId), ...)` — that part is unchanged, only the final `checkAccess` call site changes.)

- [ ] **Step 4: Run to verify these fail to compile**

Run: `cd api-service && ./gradlew test --tests PlaybackServiceTest`
Expected: FAIL to compile — `PlaybackService.checkAccess` still takes `(UUID, UUID)`.

- [ ] **Step 5: Widen the interface**

Find in `PlaybackService.java`:
```java
    AccessCheckResponse checkAccess(UUID userId, UUID contentId);
    PlaybackManifestResponse getContentManifest(UUID userId, UUID contentId);
    PlaybackManifestResponse getEpisodeManifest(UUID userId, UUID episodeId);
```

Replace with:
```java
    AccessCheckResponse checkAccess(org.springframework.security.core.userdetails.UserDetails principal, UUID contentId);
    PlaybackManifestResponse getContentManifest(org.springframework.security.core.userdetails.UserDetails principal, UUID contentId);
    PlaybackManifestResponse getEpisodeManifest(org.springframework.security.core.userdetails.UserDetails principal, UUID episodeId);
    PlaybackManifestResponse getTrailerManifest(UUID contentId);
```

(`getTrailerManifest` is declared here now; implemented in Task 2.)

- [ ] **Step 6: Update `PlaybackServiceImpl.checkAccess`**

Find:
```java
    @Override
    public AccessCheckResponse checkAccess(UUID userId, UUID contentId) {
        Content content = contentRepo.findById(contentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found"));

        if (content.getStatus() != ContentStatus.PUBLISHED) {
            return AccessCheckResponse.denied("CONTENT_NOT_PUBLISHED");
        }

        boolean hasSubscription = subscriptionRepo
            .findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
            .isPresent();

        if (!hasSubscription) {
            return AccessCheckResponse.denied("NO_ACTIVE_SUBSCRIPTION");
        }

        return AccessCheckResponse.granted();
    }
```

Replace with:
```java
    @Override
    public AccessCheckResponse checkAccess(UserDetails principal, UUID contentId) {
        Content content = contentRepo.findById(contentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found"));

        if (content.getStatus() != ContentStatus.PUBLISHED) {
            return AccessCheckResponse.denied("CONTENT_NOT_PUBLISHED");
        }

        // Admins bypass the subscription requirement (routine QA/ops — verifying a newly
        // published title actually plays) but not the PUBLISHED check above; unpublished
        // preview goes through the admin content endpoints, not this public playback path.
        if (com.tinniestudio.api.shared.security.CurrentUser.isAdmin(principal)) {
            return AccessCheckResponse.granted();
        }

        UUID userId = com.tinniestudio.api.shared.security.CurrentUser.id(principal);
        boolean hasSubscription = subscriptionRepo
            .findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
            .isPresent();

        if (!hasSubscription) {
            return AccessCheckResponse.denied("NO_ACTIVE_SUBSCRIPTION");
        }

        return AccessCheckResponse.granted();
    }
```

Add the import (if not already present via a wildcard):
```java
import org.springframework.security.core.userdetails.UserDetails;
```

- [ ] **Step 7: Update `getContentManifest` and `getEpisodeManifest` to accept and thread `principal`**

Find:
```java
    @Override
    @Transactional(readOnly = true)
    public PlaybackManifestResponse getContentManifest(UUID userId, UUID contentId) {
        AccessCheckResponse access = checkAccess(userId, contentId);
        if (!access.isHasAccess()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, access.getReason());
        }

        VideoAsset asset = videoAssetRepo
            .findByContent_IdAndAssetTypeAndIsActiveTrue(contentId, VideoAssetType.MAIN_VIDEO)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No ready video asset"));

        Integer resumeAt = watchProgressRepo.findMovieProgress(userId, contentId)
            .map(WatchProgress::getProgressSeconds)
            .orElse(null);
```

Replace with:
```java
    @Override
    @Transactional(readOnly = true)
    public PlaybackManifestResponse getContentManifest(UserDetails principal, UUID contentId) {
        AccessCheckResponse access = checkAccess(principal, contentId);
        if (!access.isHasAccess()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, access.getReason());
        }
        UUID userId = com.tinniestudio.api.shared.security.CurrentUser.id(principal);

        VideoAsset asset = videoAssetRepo
            .findByContent_IdAndAssetTypeAndIsActiveTrue(contentId, VideoAssetType.MAIN_VIDEO)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No ready video asset"));

        Integer resumeAt = watchProgressRepo.findMovieProgress(userId, contentId)
            .map(WatchProgress::getProgressSeconds)
            .orElse(null);
```

The rest of the method (the analytics publish block and `buildManifestResponse(asset, resumeAt)` return) is unchanged — it already only references `userId`/`contentId`, both of which are still in scope.

Apply the equivalent change to `getEpisodeManifest`: find

```java
    @Override
    @Transactional(readOnly = true)
    public PlaybackManifestResponse getEpisodeManifest(UUID userId, UUID episodeId) {
        Episode episode = episodeRepo.findById(episodeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found"));

        UUID contentId = episode.getSeason().getContent().getId();

        AccessCheckResponse access = checkAccess(userId, contentId);
```

Replace with:

```java
    @Override
    @Transactional(readOnly = true)
    public PlaybackManifestResponse getEpisodeManifest(UserDetails principal, UUID episodeId) {
        Episode episode = episodeRepo.findById(episodeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found"));

        UUID contentId = episode.getSeason().getContent().getId();

        AccessCheckResponse access = checkAccess(principal, contentId);
```

And immediately after the access-denied check in the same method, add the same `userId` derivation used in `getContentManifest` (the rest of the method already uses a local `userId` for `watchProgressRepo.findByUserIdAndEpisodeId(userId, episodeId)` and the analytics publish):

Find:
```java
        AccessCheckResponse access = checkAccess(principal, contentId);
        if (!access.isHasAccess()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, access.getReason());
        }

        VideoAsset asset = videoAssetRepo
            .findByEpisode_IdAndAssetTypeAndIsActiveTrue(episodeId, VideoAssetType.MAIN_VIDEO)
```

Replace with:
```java
        AccessCheckResponse access = checkAccess(principal, contentId);
        if (!access.isHasAccess()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, access.getReason());
        }
        UUID userId = com.tinniestudio.api.shared.security.CurrentUser.id(principal);

        VideoAsset asset = videoAssetRepo
            .findByEpisode_IdAndAssetTypeAndIsActiveTrue(episodeId, VideoAssetType.MAIN_VIDEO)
```

- [ ] **Step 8: Update `PlaybackController`'s three call sites to pass `principal` directly**

Find:
```java
    @GetMapping("/access/{contentId}")
    public ResponseEntity<AccessCheckResponse> checkAccess(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId) {
        return ResponseEntity.ok(playbackService.checkAccess(CurrentUser.id(principal), contentId));
    }
```

Replace with:
```java
    @GetMapping("/access/{contentId}")
    public ResponseEntity<AccessCheckResponse> checkAccess(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId) {
        return ResponseEntity.ok(playbackService.checkAccess(principal, contentId));
    }
```

Find:
```java
    @GetMapping("/manifest/content/{contentId}")
    public ResponseEntity<PlaybackManifestResponse> getContentManifest(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId) {
        return ResponseEntity.ok(playbackService.getContentManifest(CurrentUser.id(principal), contentId));
    }
```

Replace with:
```java
    @GetMapping("/manifest/content/{contentId}")
    public ResponseEntity<PlaybackManifestResponse> getContentManifest(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId) {
        return ResponseEntity.ok(playbackService.getContentManifest(principal, contentId));
    }
```

Find:
```java
    @GetMapping("/manifest/episode/{episodeId}")
    public ResponseEntity<PlaybackManifestResponse> getEpisodeManifest(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID episodeId) {
        return ResponseEntity.ok(playbackService.getEpisodeManifest(CurrentUser.id(principal), episodeId));
    }
```

Replace with:
```java
    @GetMapping("/manifest/episode/{episodeId}")
    public ResponseEntity<PlaybackManifestResponse> getEpisodeManifest(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID episodeId) {
        return ResponseEntity.ok(playbackService.getEpisodeManifest(principal, episodeId));
    }
```

Note: `CurrentUser` is still used elsewhere in this controller? Check — if `CurrentUser.id(...)` is no longer called anywhere in `PlaybackController.java` after this change, remove its now-unused import; if `getContinueWatching`/`recordProgress` still use it (they do — those two methods are untouched by this plan), keep the import.

- [ ] **Step 9: Run the service tests**

Run: `cd api-service && ./gradlew test --tests PlaybackServiceTest`
Expected: PASS (all existing `getContentManifest`/`getEpisodeManifest` tests in this file also call `service.getContentManifest(userId, contentId)` today with a raw `UUID` — update those call sites the same way as Step 3, using `principalFor(userId)` in place of the bare `userId`, before this step; if any were missed the compiler will point to them).

- [ ] **Step 10: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackService.java api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java api-service/src/main/java/com/tinniestudio/api/modules/playback/controller/PlaybackController.java api-service/src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java
git commit -m "feat: admin bypass for playback subscription check"
```

---

### Task 2: Trailer manifest endpoint

**Files:**
- Modify: `src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/playback/controller/PlaybackController.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/playback/controller/PlaybackControllerTest.java`

- [ ] **Step 1: Write the failing service tests**

Add a new nested test class in `PlaybackServiceTest`:

```java
    @Nested
    class getTrailerManifest {

        @Test
        void throws404WhenContentNotFound() {
            when(contentRepo.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTrailerManifest(UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void throws404WhenContentNotPublished() {
            Content content = new Content();
            content.setStatus(ContentStatus.DRAFT);
            when(contentRepo.findById(any())).thenReturn(Optional.of(content));

            assertThatThrownBy(() -> service.getTrailerManifest(UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void throws404WhenNoTrailerAsset() {
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);
            when(contentRepo.findById(any())).thenReturn(Optional.of(content));
            when(videoAssetRepo.findByContent_IdAndAssetTypeAndIsActiveTrue(any(), eq(VideoAssetType.TRAILER)))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTrailerManifest(UUID.randomUUID()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void returnsManifestWithNullResumeAt() {
            UUID contentId = UUID.randomUUID();
            Content content = new Content();
            content.setStatus(ContentStatus.PUBLISHED);

            VideoAsset asset = new VideoAsset();
            asset.setManifestUrl("processed/trailer/master.m3u8");
            asset.setDurationSeconds(90);
            asset.setSubtitles(List.of());

            when(contentRepo.findById(contentId)).thenReturn(Optional.of(content));
            when(videoAssetRepo.findByContent_IdAndAssetTypeAndIsActiveTrue(eq(contentId), eq(VideoAssetType.TRAILER)))
                .thenReturn(Optional.of(asset));

            PlaybackManifestResponse resp = service.getTrailerManifest(contentId);

            assertThat(resp.getManifestUrl()).isEqualTo("http://cdn.test/processed/trailer/master.m3u8");
            assertThat(resp.getResumeAt()).isNull();
            assertThat(resp.getDuration()).isEqualTo(90);
        }
    }
```

- [ ] **Step 2: Run to verify these fail**

Run: `cd api-service && ./gradlew test --tests PlaybackServiceTest`
Expected: FAIL to compile — `getTrailerManifest` doesn't exist on `PlaybackServiceImpl` yet (it's already declared on the interface from Task 1, Step 5).

- [ ] **Step 3: Implement `getTrailerManifest`**

Add to `PlaybackServiceImpl`, near `getContentManifest`:

```java
    // -------------------------------------------------------------------------
    // Trailer manifest — deliberately public: no auth, no checkAccess() call.
    // Trailers are promotional and must stay reachable by anonymous/free visitors.
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PlaybackManifestResponse getTrailerManifest(UUID contentId) {
        Content content = contentRepo.findById(contentId)
            .filter(c -> c.getStatus() == ContentStatus.PUBLISHED)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + contentId));

        VideoAsset asset = videoAssetRepo
            .findByContent_IdAndAssetTypeAndIsActiveTrue(contentId, VideoAssetType.TRAILER)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No trailer available"));

        return buildManifestResponse(asset, null);
    }
```

- [ ] **Step 4: Run the service tests**

Run: `cd api-service && ./gradlew test --tests PlaybackServiceTest`
Expected: PASS

- [ ] **Step 5: Add the controller endpoint**

Add to `PlaybackController`, near `getContentManifest`:

```java
    @Operation(summary = "Get HLS manifest for a content item's trailer — public, no auth or subscription required")
    @GetMapping("/manifest/content/{contentId}/trailer")
    public ResponseEntity<PlaybackManifestResponse> getTrailerManifest(@PathVariable UUID contentId) {
        return ResponseEntity.ok(playbackService.getTrailerManifest(contentId));
    }
```

- [ ] **Step 6: Write the failing controller test**

Add to `PlaybackControllerTest`:

```java
    @Test
    @DisplayName("GET /playback/manifest/content/{contentId}/trailer returns 200 with no auth required")
    void getTrailerManifest_returnsManifestWithoutAuth() throws Exception {
        UUID contentId = UUID.randomUUID();
        PlaybackManifestResponse manifest = new PlaybackManifestResponse(
            "http://cdn.test/trailer.m3u8", List.of(), null, 90);
        when(playbackService.getTrailerManifest(any(UUID.class)))
            .thenReturn(manifest);

        // Deliberately no @WithMockUser — proves this endpoint needs no authentication.
        mockMvc.perform(getWithContext("/playback/manifest/content/" + contentId + "/trailer"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.manifestUrl").value("http://cdn.test/trailer.m3u8"))
            .andExpect(jsonPath("$.data.resumeAt").doesNotExist());
    }
```

(`resumeAt` uses `@JsonInclude(JsonInclude.Include.NON_NULL)` on `PlaybackManifestResponse` — confirmed from the DTO — so a null value is omitted from the JSON entirely rather than serialized as `null`; `doesNotExist()` is the correct assertion, not `.value(null)`.)

- [ ] **Step 7: Run the controller test**

Run: `cd api-service && ./gradlew test --tests PlaybackControllerTest`
Expected: PASS. If this endpoint unexpectedly 401s/403s in the test, check `SecurityConfig` — this route needs to be reachable without authentication in the real security filter chain, not just in this `@WebMvcTest` (which has `addFilters = false` and so wouldn't catch a missing permit-all rule). Cross-check `SecurityConfig.java`'s `authorizeHttpRequests` block: `/contents/**`, `/categories/**`, `/discover/**` etc. are already public there — `/playback/manifest/content/*/trailer` needs the same treatment, since today only `/playback/**` isn't in that public list at all (every other `/playback/*` route requires auth, correctly). Add a specific permit-all matcher for this one path ahead of any broader `/playback/**` auth rule, matching whatever ordering convention `SecurityConfig` already uses for its public-path exceptions.

- [ ] **Step 8: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java api-service/src/main/java/com/tinniestudio/api/modules/playback/controller/PlaybackController.java api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java api-service/src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java api-service/src/test/java/com/tinniestudio/api/modules/playback/controller/PlaybackControllerTest.java
git commit -m "feat: public trailer manifest endpoint"
```

---

### Task 3: Continue Watching thumbnail enrichment

**Files:**
- Modify: `src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java`

- [ ] **Step 1: Write the failing test**

Add to the `getContinueWatching` nested test class:

```java
        @Test
        void populatesThumbnailUrlFromContent() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();

            WatchProgress p = new WatchProgress();
            p.setContentId(contentId);
            p.setProgressSeconds(300);
            p.setDurationSeconds(3600);
            p.setCompletionPercentage(new java.math.BigDecimal("8.33"));
            p.setLastWatchedAt(java.time.Instant.now());

            Content content = new Content();
            content.setTitle("My Movie");
            content.setId(contentId);
            content.setThumbnailUrl("posters/my-movie-thumb.jpg");

            when(watchProgressRepo.findByUserIdAndCompletedFalseOrderByLastWatchedAtDesc(eq(userId), any()))
                .thenReturn(List.of(p));
            when(contentRepo.findAllById(any())).thenReturn(List.of(content));
            when(episodeRepo.findAllById(any())).thenReturn(List.of());

            List<ContinueWatchingItem> result = service.getContinueWatching(userId);

            assertThat(result.get(0).getThumbnailUrl()).isEqualTo("posters/my-movie-thumb.jpg");
        }

        @Test
        void populatesThumbnailUrlFromEpisode() {
            UUID userId = UUID.randomUUID();
            UUID episodeId = UUID.randomUUID();

            WatchProgress p = new WatchProgress();
            p.setEpisodeId(episodeId);
            p.setProgressSeconds(300);
            p.setDurationSeconds(1800);
            p.setCompletionPercentage(new java.math.BigDecimal("16.67"));
            p.setLastWatchedAt(java.time.Instant.now());

            Episode episode = new Episode();
            episode.setTitle("Pilot");
            episode.setThumbnailUrl("posters/pilot-thumb.jpg");

            when(watchProgressRepo.findByUserIdAndCompletedFalseOrderByLastWatchedAtDesc(eq(userId), any()))
                .thenReturn(List.of(p));
            when(contentRepo.findAllById(any())).thenReturn(List.of());
            when(episodeRepo.findAllById(any())).thenReturn(List.of(episode));
            when(episode.getId()).thenReturn(null); // Episode id not set explicitly in this fixture — see note below

            List<ContinueWatchingItem> result = service.getContinueWatching(userId);

            assertThat(result.get(0).getThumbnailUrl()).isEqualTo("posters/pilot-thumb.jpg");
        }
```

Note on the second test: `episodeMap` is built via `episodeRepo.findAllById(...).collect(Collectors.toMap(Episode::getId, e -> e))` — the mocked `episode` here needs a real, settable id (via `BaseEntity.setId(...)`, inherited) matching `p.getEpisodeId()`, not a stubbed `getId()` return (mocking a getter on a real object, not a Mockito mock, doesn't work). Replace the `when(episode.getId())...` line above with `episode.setId(episodeId);` set right after `Episode episode = new Episode();` instead — the placeholder above was illustrative only; use the entity's real setter, which is what `mapsMovieProgressToItem`'s existing test (`content.setId(contentId)`) already establishes as the correct pattern in this file.

- [ ] **Step 2: Run to verify these fail**

Run: `cd api-service && ./gradlew test --tests PlaybackServiceTest`
Expected: FAIL — both assertions get `null` instead of the expected thumbnail URL.

- [ ] **Step 3: Populate `thumbnailUrl` in `getContinueWatching`**

Find:
```java
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
            .toList();
```

Replace with:
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

- [ ] **Step 4: Run the tests**

Run: `cd api-service && ./gradlew test --tests PlaybackServiceTest`
Expected: PASS — including the pre-existing `mapsMovieProgressToItem` test, which never asserted on `thumbnailUrl` before and still doesn't need to.

- [ ] **Step 5: Full module test run**

Run: `cd api-service && ./gradlew test`
Expected: BUILD SUCCESSFUL — confirms nothing outside the `playback` module broke (nothing should; this task only touches files inside it).

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java api-service/src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java
git commit -m "fix: populate ContinueWatchingItem.thumbnailUrl from content/episode"
```

---

## Self-Review Notes

- **Spec coverage:** Trailer manifest ✓ (Task 2), admin bypass ✓ (Task 1), thumbnail enrichment ✓ (Task 3).
- **Corrected from the spec text:** admin-bypass spec understated the ripple — `getContentManifest`/`getEpisodeManifest` signatures widen too, not just `checkAccess`, because they call it internally with what the controller previously pre-extracted as a raw `UUID`. Traced the actual call graph before writing tasks rather than trusting the spec's phrasing.
- **New finding during planning, not in either spec:** the trailer endpoint needs an explicit `SecurityConfig` permit-all entry — `@WebMvcTest`'s `addFilters = false` in `PlaybackControllerTest` wouldn't catch a missing one, so Task 2 calls this out explicitly rather than letting it silently pass tests while 401ing in production.
- **Sequencing within this plan:** Task 1 must land before Task 2, since Task 2's `getTrailerManifest` is declared on the interface as part of Task 1 Step 5 (bundled there to avoid a second round of "add one more method to this interface" churn) but implemented in Task 2. Task 3 is independent of both and could be done first if preferred — order here just follows spec-doc order.
