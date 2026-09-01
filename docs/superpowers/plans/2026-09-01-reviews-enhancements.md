# Reviews Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show who wrote each review in the public list, and let a client reliably check whether the current user already has a review on a given content (regardless of its moderation status).

**Architecture:** `ReviewResponse` gains a nullable nested `author` field — populated only by `list()` (batch-fetching `User` rows for the page's reviews, same pattern `PlaybackServiceImpl.getContinueWatching()` already uses for content/episode titles), left `null` everywhere else (`create`/`update`/`moderateStatus`), since those operations are always about the caller's own review. A new `GET /contents/{contentId}/reviews/mine` endpoint adds a single derived-query repository method and one service method.

**Tech Stack:** Spring Boot, Spring Data JPA, JUnit 5 + Mockito + AssertJ.

**Covers specs:**
- `docs/superpowers/specs/2026-09-01-review-author-attribution-design.md`
- `docs/superpowers/specs/2026-09-01-my-review-lookup-design.md`

---

## File Structure

**Create:**
- `src/main/java/com/tinniestudio/api/modules/reviews/dto/ReviewAuthorResponse.java`

**Modify:**
- `src/main/java/com/tinniestudio/api/modules/reviews/dto/ReviewResponse.java` — add `author` field + two-arg factory overload
- `src/main/java/com/tinniestudio/api/modules/reviews/service/ReviewService.java` (interface) — `list()` gains author enrichment internally (no signature change); add `getMine`
- `src/main/java/com/tinniestudio/api/modules/reviews/service/ReviewServiceImpl.java`
- `src/main/java/com/tinniestudio/api/modules/reviews/repository/ReviewRepository.java` — add `findByUserIdAndContentId`
- `src/main/java/com/tinniestudio/api/modules/reviews/controller/ReviewController.java` — add `GET .../reviews/mine`
- `src/test/java/com/tinniestudio/api/modules/reviews/service/ReviewServiceTest.java`
- `src/test/java/com/tinniestudio/api/modules/reviews/controller/ReviewControllerTest.java`

---

### Task 1: Review author attribution

**Files:**
- Create: `src/main/java/com/tinniestudio/api/modules/reviews/dto/ReviewAuthorResponse.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/reviews/dto/ReviewResponse.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/reviews/service/ReviewServiceImpl.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/reviews/service/ReviewServiceTest.java`

- [ ] **Step 1: Create `ReviewAuthorResponse`**

Deliberately minimal — no id, no email, nothing beyond what a review list needs to display.

```java
package com.tinniestudio.api.modules.reviews.dto;

import com.tinniestudio.api.shared.entity.User;

public record ReviewAuthorResponse(String displayName, String avatarUrl) {
    /**
     * Falls back displayName -> firstName -> a generic label, never to email (a review list is
     * semi-public; email would be a privacy leak the fallback in other parts of the app that use
     * email as a last resort — e.g. UserProfileServiceImpl's notification-greeting fallback — is
     * not appropriate to copy here).
     */
    public static ReviewAuthorResponse from(User u) {
        String name = u.getDisplayName() != null ? u.getDisplayName()
            : u.getFirstName() != null ? u.getFirstName()
            : "Member";
        return new ReviewAuthorResponse(name, u.getAvatarUrl());
    }
}
```

- [ ] **Step 2: Write the failing service test**

Add to `ReviewServiceTest`, right after the existing `list_returnsApprovedReviews` test. This requires a `UserRepository` mock, added as a new field on the test class:

Find:
```java
    @Mock
    private ContentRepository contentRepo;

    @InjectMocks
    private ReviewServiceImpl reviewService;
```

Replace with:
```java
    @Mock
    private ContentRepository contentRepo;

    @Mock
    private com.tinniestudio.api.modules.user.repository.UserRepository userRepo;

    @InjectMocks
    private ReviewServiceImpl reviewService;
```

Add the new test:
```java
    @Test
    @DisplayName("list: populates author displayName/avatarUrl from a batch user lookup")
    void list_populatesAuthorFromBatchLookup() {
        ContentReview review = new ContentReview();
        ReflectionTestUtils.setField(review, "id", reviewId);
        review.setUserId(userId);
        review.setContentId(contentId);
        review.setRating((short) 4);
        review.setStatus(ReviewStatus.APPROVED);

        com.tinniestudio.api.shared.entity.User author = new com.tinniestudio.api.shared.entity.User();
        ReflectionTestUtils.setField(author, "id", userId);
        author.setDisplayName("Jane D.");
        author.setAvatarUrl("avatars/jane.jpg");

        Pageable pageable = PageRequest.of(0, 10);
        Page<ContentReview> page = new PageImpl<>(List.of(review), pageable, 1);

        when(reviewRepo.findByContentIdAndStatusOrderByCreatedAtDesc(contentId, ReviewStatus.APPROVED, pageable))
                .thenReturn(page);
        when(userRepo.findAllById(List.of(userId))).thenReturn(List.of(author));

        Page<ReviewResponse> result = reviewService.list(contentId, pageable);

        assertThat(result.getContent().get(0).author().displayName()).isEqualTo("Jane D.");
        assertThat(result.getContent().get(0).author().avatarUrl()).isEqualTo("avatars/jane.jpg");
    }

    @Test
    @DisplayName("list: falls back to a generic label when the author has no displayName or firstName")
    void list_fallsBackToGenericLabelWhenNoName() {
        ContentReview review = new ContentReview();
        ReflectionTestUtils.setField(review, "id", reviewId);
        review.setUserId(userId);
        review.setContentId(contentId);
        review.setRating((short) 3);
        review.setStatus(ReviewStatus.APPROVED);

        com.tinniestudio.api.shared.entity.User author = new com.tinniestudio.api.shared.entity.User();
        ReflectionTestUtils.setField(author, "id", userId);
        // displayName and firstName both left null

        Pageable pageable = PageRequest.of(0, 10);
        Page<ContentReview> page = new PageImpl<>(List.of(review), pageable, 1);

        when(reviewRepo.findByContentIdAndStatusOrderByCreatedAtDesc(contentId, ReviewStatus.APPROVED, pageable))
                .thenReturn(page);
        when(userRepo.findAllById(List.of(userId))).thenReturn(List.of(author));

        Page<ReviewResponse> result = reviewService.list(contentId, pageable);

        assertThat(result.getContent().get(0).author().displayName()).isEqualTo("Member");
    }

    @Test
    @DisplayName("create: leaves author null — mutation responses are always about the caller's own review")
    void create_leavesAuthorNull() {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating((short) 5);

        when(contentRepo.existsById(contentId)).thenReturn(true);
        when(reviewRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(false);

        ContentReview savedReview = new ContentReview();
        ReflectionTestUtils.setField(savedReview, "id", reviewId);
        savedReview.setUserId(userId);
        savedReview.setContentId(contentId);
        savedReview.setRating((short) 5);
        savedReview.setStatus(ReviewStatus.APPROVED);

        when(reviewRepo.saveAndFlush(any(ContentReview.class))).thenReturn(savedReview);

        ReviewResponse result = reviewService.create(userId, contentId, request);

        assertThat(result.author()).isNull();
        verifyNoInteractions(userRepo);
    }
```

- [ ] **Step 3: Run to verify these fail to compile**

Run: `cd api-service && ./gradlew test --tests ReviewServiceTest`
Expected: FAIL to compile — `ReviewResponse` has no `author()` accessor yet, `UserRepository` isn't a known field type here without the import resolving (it does resolve — `UserRepository` already exists; the failure is purely about the missing `author` field on `ReviewResponse`).

- [ ] **Step 4: Add `author` to `ReviewResponse`**

Find:
```java
public record ReviewResponse(
    UUID id, UUID contentId, UUID userId,
    Short rating, String body, String status,
    Instant createdAt, Instant updatedAt
) {
    public static ReviewResponse from(ContentReview r) {
        return new ReviewResponse(
            r.getId(), r.getContentId(), r.getUserId(),
            r.getRating(), r.getBody(), r.getStatus().name(),
            r.getCreatedAt(), r.getUpdatedAt()
        );
    }
}
```

Replace with:
```java
public record ReviewResponse(
    UUID id, UUID contentId, UUID userId,
    Short rating, String body, String status,
    Instant createdAt, Instant updatedAt,
    ReviewAuthorResponse author
) {
    /** Used by create/update/moderateStatus/getMine — author is always null; see the two-arg overload for list(). */
    public static ReviewResponse from(ContentReview r) {
        return from(r, null);
    }

    public static ReviewResponse from(ContentReview r, ReviewAuthorResponse author) {
        return new ReviewResponse(
            r.getId(), r.getContentId(), r.getUserId(),
            r.getRating(), r.getBody(), r.getStatus().name(),
            r.getCreatedAt(), r.getUpdatedAt(),
            author
        );
    }
}
```

- [ ] **Step 5: Batch-fetch authors in `ReviewServiceImpl.list()`**

Find:
```java
    @Override
    @Transactional(readOnly = true)
    public Page<ReviewResponse> list(UUID contentId, Pageable pageable) {
        return reviewRepo
                .findByContentIdAndStatusOrderByCreatedAtDesc(contentId, ReviewStatus.APPROVED, pageable)
                .map(ReviewResponse::from);
    }
```

Replace with:
```java
    @Override
    @Transactional(readOnly = true)
    public Page<ReviewResponse> list(UUID contentId, Pageable pageable) {
        Page<ContentReview> reviews = reviewRepo
                .findByContentIdAndStatusOrderByCreatedAtDesc(contentId, ReviewStatus.APPROVED, pageable);

        java.util.Set<UUID> userIds = reviews.getContent().stream()
                .map(ContentReview::getUserId)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<UUID, com.tinniestudio.api.shared.entity.User> usersById = userRepo.findAllById(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(com.tinniestudio.api.shared.entity.User::getId, u -> u));

        return reviews.map(r -> {
            com.tinniestudio.api.shared.entity.User author = usersById.get(r.getUserId());
            return ReviewResponse.from(r, author != null ? ReviewAuthorResponse.from(author) : null);
        });
    }
```

Add the constructor dependency:

Find:
```java
    private final ReviewRepository reviewRepo;
    private final ContentRepository contentRepo;
```

Replace with:
```java
    private final ReviewRepository reviewRepo;
    private final ContentRepository contentRepo;
    private final com.tinniestudio.api.modules.user.repository.UserRepository userRepo;
```

Add the import at the top of the file:
```java
import com.tinniestudio.api.modules.reviews.dto.ReviewAuthorResponse;
```

- [ ] **Step 6: Run the tests**

Run: `cd api-service && ./gradlew test --tests ReviewServiceTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/reviews/dto/ReviewAuthorResponse.java api-service/src/main/java/com/tinniestudio/api/modules/reviews/dto/ReviewResponse.java api-service/src/main/java/com/tinniestudio/api/modules/reviews/service/ReviewServiceImpl.java api-service/src/test/java/com/tinniestudio/api/modules/reviews/service/ReviewServiceTest.java
git commit -m "feat: review list responses include author displayName/avatarUrl"
```

---

### Task 2: My-review lookup

**Files:**
- Modify: `src/main/java/com/tinniestudio/api/modules/reviews/repository/ReviewRepository.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/reviews/service/ReviewService.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/reviews/service/ReviewServiceImpl.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/reviews/controller/ReviewController.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/reviews/service/ReviewServiceTest.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/reviews/controller/ReviewControllerTest.java`

- [ ] **Step 1: Write the failing service test**

Add to `ReviewServiceTest`:

```java
    // ─── getMine() ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getMine: returns the review regardless of its status when it exists")
    void getMine_returnsReviewOfAnyStatus() {
        ContentReview pending = new ContentReview();
        ReflectionTestUtils.setField(pending, "id", reviewId);
        pending.setUserId(userId);
        pending.setContentId(contentId);
        pending.setRating((short) 4);
        pending.setStatus(ReviewStatus.PENDING);

        when(reviewRepo.findByUserIdAndContentId(userId, contentId)).thenReturn(Optional.of(pending));

        ReviewResponse result = reviewService.getMine(userId, contentId);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.author()).isNull();
    }

    @Test
    @DisplayName("getMine: throws 404 when the user has no review on this content")
    void getMine_throwsNotFoundWhenNoneExists() {
        when(reviewRepo.findByUserIdAndContentId(userId, contentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.getMine(userId, contentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(NOT_FOUND));
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd api-service && ./gradlew test --tests ReviewServiceTest`
Expected: FAIL to compile — `ReviewService.getMine` doesn't exist.

- [ ] **Step 3: Add the repository method**

Add to `ReviewRepository`:
```java
    Optional<ContentReview> findByUserIdAndContentId(UUID userId, UUID contentId);
```

(Sound as a unique lookup — not paged — because `uq_review_user_content` on `ContentReview` already guarantees at most one row per user+content pair, the same guarantee `existsByUserIdAndContentId` relies on.)

- [ ] **Step 4: Add `getMine` to the service interface and implementation**

Add to `ReviewService` (interface):
```java
    ReviewResponse getMine(UUID userId, UUID contentId);
```

Add to `ReviewServiceImpl`:
```java
    @Override
    @Transactional(readOnly = true)
    public ReviewResponse getMine(UUID userId, UUID contentId) {
        return reviewRepo.findByUserIdAndContentId(userId, contentId)
                .map(ReviewResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No review found for this content"));
    }
```

- [ ] **Step 5: Run the service tests**

Run: `cd api-service && ./gradlew test --tests ReviewServiceTest`
Expected: PASS

- [ ] **Step 6: Add the controller endpoint**

Add to `ReviewController`, near the existing `list`:
```java
    @Operation(summary = "Get the authenticated user's own review for a content item, regardless of moderation status")
    @GetMapping("/contents/{contentId}/reviews/mine")
    public ResponseEntity<ReviewResponse> getMine(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId) {
        return ResponseEntity.ok(reviewService.getMine(CurrentUser.id(principal), contentId));
    }
```

(`CurrentUser`, `UserDetails`, `AuthenticationPrincipal` are already imported in this file for the existing `create`/`update`/`delete` endpoints.)

- [ ] **Step 7: Write and run the controller test**

Add to `ReviewControllerTest` (mirror whatever `@WebMvcTest` setup pattern the existing tests in that file already use for an authenticated GET):

```java
    @Test
    @DisplayName("GET /contents/{contentId}/reviews/mine returns 200 when a review exists")
    @WithMockUser(username = USER_ID, roles = "USER")
    void getMine_returnsReview() throws Exception {
        UUID contentId = UUID.randomUUID();
        ReviewResponse response = new ReviewResponse(
            UUID.randomUUID(), contentId, UUID.fromString(USER_ID),
            (short) 4, "Great!", "PENDING",
            Instant.now(), Instant.now(), null);
        when(reviewService.getMine(any(UUID.class), eq(contentId))).thenReturn(response);

        mockMvc.perform(getWithContext("/contents/" + contentId + "/reviews/mine"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /contents/{contentId}/reviews/mine returns 404 when no review exists")
    @WithMockUser(username = USER_ID, roles = "USER")
    void getMine_returns404WhenNoneExists() throws Exception {
        UUID contentId = UUID.randomUUID();
        when(reviewService.getMine(any(UUID.class), eq(contentId)))
            .thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "No review found for this content"));

        mockMvc.perform(getWithContext("/contents/" + contentId + "/reviews/mine"))
            .andExpect(status().isNotFound());
    }
```

Check the existing `ReviewControllerTest`'s constant/helper names (`USER_ID`, `getWithContext`, `CONTEXT_PATH`) match `PlaybackControllerTest`'s established convention before finalizing — read the file first if it diverges, since this plan's exploration didn't open `ReviewControllerTest.java` directly.

Run: `cd api-service && ./gradlew test --tests ReviewControllerTest`
Expected: PASS

- [ ] **Step 8: Full module test run**

Run: `cd api-service && ./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/reviews/repository/ReviewRepository.java api-service/src/main/java/com/tinniestudio/api/modules/reviews/service/ReviewService.java api-service/src/main/java/com/tinniestudio/api/modules/reviews/service/ReviewServiceImpl.java api-service/src/main/java/com/tinniestudio/api/modules/reviews/controller/ReviewController.java api-service/src/test/java/com/tinniestudio/api/modules/reviews/service/ReviewServiceTest.java api-service/src/test/java/com/tinniestudio/api/modules/reviews/controller/ReviewControllerTest.java
git commit -m "feat: GET /contents/{contentId}/reviews/mine — status-agnostic review lookup"
```

---

## Self-Review Notes

- **Spec coverage:** Author attribution ✓ (Task 1, including the fallback chain and the explicit "mutations stay null" behavior). My-review lookup ✓ (Task 2, any-status lookup + 404 when absent).
- **Placeholder scan:** `ReviewControllerTest`'s exact helper names are flagged as unverified (that file wasn't opened during planning) rather than guessed — Task 2 Step 7 says to check before finalizing instead of asserting a convention that might not match.
- **Type consistency:** `ReviewResponse` gains a trailing `author` field consistently across every construction site touched in this plan (`from(r)`, `from(r, author)`, the controller test's manual `new ReviewResponse(...)` call includes the new 9th positional arg).
