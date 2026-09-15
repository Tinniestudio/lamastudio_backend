# Favorite Exists Lookup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a client check whether a single content item is already favorited, without fetching the whole paginated list.

**Architecture:** One new endpoint backed by the existing `FavoriteRepository.existsByUserIdAndContentId` — no new repository query needed.

**Tech Stack:** Spring Boot, Spring Data JPA, JUnit 5 + Mockito + AssertJ, `@WebMvcTest` + MockMvc.

**Covers spec:** `docs/superpowers/specs/2026-09-14-favorite-exists-lookup-design.md`

---

## File Structure

**Create:**
- `src/main/java/com/tinniestudio/api/modules/library/dto/FavoriteExistsResponse.java`

**Modify:**
- `src/main/java/com/tinniestudio/api/modules/library/service/FavoriteService.java`
- `src/main/java/com/tinniestudio/api/modules/library/service/FavoriteServiceImpl.java`
- `src/main/java/com/tinniestudio/api/modules/library/controller/FavoriteController.java`
- `src/test/java/com/tinniestudio/api/modules/library/service/FavoriteServiceTest.java`
- `src/test/java/com/tinniestudio/api/modules/library/controller/FavoriteControllerTest.java`

---

### Task 1: `GET /favorites/{contentId}/exists`

**Files:** as listed above.

- [ ] **Step 1: Write the failing service test**

Add to `FavoriteServiceTest.java` (read the file first to match its existing setup/mock conventions — this plan's exploration confirmed the interface/repository shape but not the test file's exact fixture style):

```java
@Test
@DisplayName("exists: returns true when the favorite exists")
void exists_returnsTrueWhenFavorited() {
    when(favoriteRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(true);

    boolean result = favoriteService.exists(userId, contentId);

    assertThat(result).isTrue();
}

@Test
@DisplayName("exists: returns false when not favorited")
void exists_returnsFalseWhenNotFavorited() {
    when(favoriteRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(false);

    boolean result = favoriteService.exists(userId, contentId);

    assertThat(result).isFalse();
}
```

- [ ] **Step 2: Run to verify it fails to compile**

Run: `./gradlew :api-service:test --tests FavoriteServiceTest`
Expected: FAIL to compile — `FavoriteService.exists(...)` doesn't exist yet.

- [ ] **Step 3: Add `exists` to the interface and implementation**

Add to `FavoriteService`:
```java
    boolean exists(UUID userId, UUID contentId);
```

Add to `FavoriteServiceImpl`:
```java
    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID userId, UUID contentId) {
        return favoriteRepo.existsByUserIdAndContentId(userId, contentId);
    }
```

- [ ] **Step 4: Run the service tests**

Run: `./gradlew :api-service:test --tests FavoriteServiceTest`
Expected: PASS

- [ ] **Step 5: Create the response DTO**

```java
package com.tinniestudio.api.modules.library.dto;

public record FavoriteExistsResponse(boolean isFavorite) {}
```

- [ ] **Step 6: Add the controller endpoint**

Add to `FavoriteController`:
```java
    @Operation(summary = "Check whether a content item is already in the authenticated user's favorites")
    @GetMapping("/{contentId}/exists")
    public ResponseEntity<FavoriteExistsResponse> exists(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId) {
        return ResponseEntity.ok(new FavoriteExistsResponse(
            favoriteService.exists(CurrentUser.id(principal), contentId)));
    }
```

Add the `FavoriteExistsResponse` import.

- [ ] **Step 7: Write and run the controller test**

Add to `FavoriteControllerTest.java` (matching whatever `@WebMvcTest` helper/constant conventions the existing tests in that file already use):

```java
@Test
@DisplayName("GET /favorites/{contentId}/exists returns true when favorited")
@WithMockUser(username = USER_ID, roles = "USER")
void exists_returnsTrue() throws Exception {
    UUID contentId = UUID.randomUUID();
    when(favoriteService.exists(any(UUID.class), eq(contentId))).thenReturn(true);

    mockMvc.perform(getWithContext("/favorites/" + contentId + "/exists"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.isFavorite").value(true));
}

@Test
@DisplayName("GET /favorites/{contentId}/exists returns false when not favorited")
@WithMockUser(username = USER_ID, roles = "USER")
void exists_returnsFalse() throws Exception {
    UUID contentId = UUID.randomUUID();
    when(favoriteService.exists(any(UUID.class), eq(contentId))).thenReturn(false);

    mockMvc.perform(getWithContext("/favorites/" + contentId + "/exists"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.isFavorite").value(false));
}
```

Check the file's actual helper names (`getWithContext`, `USER_ID`, etc.) before finalizing — this plan's exploration confirmed the test file exists but not its exact conventions; mirror `PlaybackControllerTest`'s established pattern if this file diverges.

Run: `./gradlew :api-service:test --tests FavoriteControllerTest`
Expected: PASS

- [ ] **Step 8: Full module test run**

Run: `./gradlew :api-service:test`
Expected: BUILD SUCCESSFUL (module-relative pre-existing failures, if any, are the repo's known unrelated gap — confirm via the same pre-existing-vs-new distinction every prior plan in this repo has used).

- [ ] **Step 9: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/library api-service/src/test/java/com/tinniestudio/api/modules/library
git commit -m "feat: GET /favorites/{contentId}/exists -- single-item favorite check"
```

---

## Self-Review Notes

- **Spec coverage:** single endpoint, always-200 semantics (no 404 case, unlike the reviews "mine" lookup), backed by the existing repository method with no new query — matches the spec exactly.
- **Placeholder scan:** Steps 1 and 7 both explicitly flag reading the real test files first rather than assuming this plan's example code's naming is already correct — consistent with how every other small plan in this repo has handled the same honest uncertainty.
