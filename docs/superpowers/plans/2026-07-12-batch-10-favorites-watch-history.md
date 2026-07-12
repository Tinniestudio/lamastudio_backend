# Batch 10 — Favorites + Watch History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-user favorites (save/remove/list content) and a watch-history log (automatically recorded during playback, viewable and clearable by the user).

**Architecture:** Two new tables (`favorites`, `watch_history`) with a new `modules/library/` module containing services and controllers. Entities follow the project convention of living in `shared/entity/`. Watch history is written by `PlaybackServiceImpl.recordProgress()` at the same time progress is recorded (single transaction). All endpoints are authenticated-only; favorites enforce a max of 500 items per user.

**Tech Stack:** Spring Boot 3 · Spring Data JPA · PostgreSQL · Mockito + AssertJ (unit tests) · `@WebMvcTest` (controller tests)

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `db/migration/V31__add_favorites.sql` | favorites table + unique constraint |
| Create | `db/migration/V32__add_watch_history.sql` | watch_history table |
| Create | `shared/entity/Favorite.java` | Favorite JPA entity |
| Create | `shared/entity/WatchHistory.java` | WatchHistory JPA entity |
| Create | `modules/library/repository/FavoriteRepository.java` | Spring Data repo for favorites |
| Create | `modules/library/repository/WatchHistoryRepository.java` | Spring Data repo for watch history |
| Create | `modules/library/dto/FavoriteResponse.java` | Favorite + embedded content summary |
| Create | `modules/library/dto/WatchHistoryResponse.java` | Watch history entry + embedded content summary |
| Create | `modules/library/service/FavoriteService.java` | Interface |
| Create | `modules/library/service/FavoriteServiceImpl.java` | add, remove, list, 409/404/400 guards |
| Create | `modules/library/service/WatchHistoryService.java` | Interface |
| Create | `modules/library/service/WatchHistoryServiceImpl.java` | list, delete-by-id, delete-all |
| Create | `modules/library/controller/FavoriteController.java` | 3 endpoints |
| Create | `modules/library/controller/HistoryController.java` | 3 endpoints |
| Create | `modules/library/service/FavoriteServiceTest.java` | Unit tests |
| Create | `modules/library/service/WatchHistoryServiceTest.java` | Unit tests |
| Create | `modules/library/controller/FavoriteControllerTest.java` | @WebMvcTest tests |
| Create | `modules/library/controller/HistoryControllerTest.java` | @WebMvcTest tests |
| Modify | `modules/playback/service/PlaybackServiceImpl.java` | inject WatchHistoryRepository, write history in recordProgress |

All paths are relative to `api-service/src/main/java/com/tinniestudio/api/` (and `src/test/…` for test files).

---

## Task 1: DB Migrations V31 + V32

**Files:**
- Create: `api-service/src/main/resources/db/migration/V31__add_favorites.sql`
- Create: `api-service/src/main/resources/db/migration/V32__add_watch_history.sql`

- [ ] **Step 1: Create V31__add_favorites.sql**

```sql
CREATE TABLE IF NOT EXISTS favorites (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content_id  UUID        NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_favorites_user_content UNIQUE (user_id, content_id)
);

CREATE INDEX idx_favorites_user_id ON favorites(user_id);
```

- [ ] **Step 2: Create V32__add_watch_history.sql**

```sql
CREATE TABLE IF NOT EXISTS watch_history (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content_id       UUID        NOT NULL REFERENCES contents(id),
    episode_id       UUID        REFERENCES episodes(id),
    watched_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    progress_seconds INTEGER,
    duration_seconds INTEGER,
    device_type      VARCHAR(50),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_watch_history_user_id ON watch_history(user_id);
CREATE INDEX idx_watch_history_user_watched ON watch_history(user_id, watched_at DESC);
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew :api-service:compileJava 2>&1 | grep -E "ERROR|error" | head -5
```
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add api-service/src/main/resources/db/migration/V31__add_favorites.sql
git add api-service/src/main/resources/db/migration/V32__add_watch_history.sql
git commit -m "feat(library): add favorites and watch_history DB migrations (V31, V32)"
```

---

## Task 2: Entities + Repositories

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/shared/entity/Favorite.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/shared/entity/WatchHistory.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/library/repository/FavoriteRepository.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/library/repository/WatchHistoryRepository.java`

- [ ] **Step 1: Create Favorite entity**

```java
package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "favorites",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_favorites_user_content",
        columnNames = {"user_id", "content_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class Favorite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

- [ ] **Step 2: Create WatchHistory entity**

```java
package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "watch_history")
@Getter
@Setter
@NoArgsConstructor
public class WatchHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(name = "episode_id")
    private UUID episodeId;

    @Column(name = "watched_at", nullable = false)
    private Instant watchedAt = Instant.now();

    @Column(name = "progress_seconds")
    private Integer progressSeconds;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "device_type", length = 50)
    private String deviceType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

- [ ] **Step 3: Create FavoriteRepository**

```java
package com.tinniestudio.api.modules.library.repository;

import com.tinniestudio.api.shared.entity.Favorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, UUID> {
    boolean existsByUserIdAndContentId(UUID userId, UUID contentId);
    Optional<Favorite> findByUserIdAndContentId(UUID userId, UUID contentId);
    Page<Favorite> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
    long countByUserId(UUID userId);
}
```

- [ ] **Step 4: Create WatchHistoryRepository**

```java
package com.tinniestudio.api.modules.library.repository;

import com.tinniestudio.api.shared.entity.WatchHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WatchHistoryRepository extends JpaRepository<WatchHistory, UUID> {
    Page<WatchHistory> findByUserIdOrderByWatchedAtDesc(UUID userId, Pageable pageable);
    Optional<WatchHistory> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM WatchHistory w WHERE w.userId = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);
}
```

- [ ] **Step 5: Verify compilation**

```bash
./gradlew :api-service:compileJava 2>&1 | grep -E "ERROR|error" | head -5
```
Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/Favorite.java
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/WatchHistory.java
git add api-service/src/main/java/com/tinniestudio/api/modules/library/repository/
git commit -m "feat(library): add Favorite and WatchHistory entities with repositories"
```

---

## Task 3: DTOs + FavoriteService (TDD)

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/library/dto/FavoriteResponse.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/library/service/FavoriteService.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/library/service/FavoriteServiceImpl.java`
- Create: `api-service/src/test/java/com/tinniestudio/api/modules/library/service/FavoriteServiceTest.java`

- [ ] **Step 1: Create FavoriteResponse DTO**

```java
package com.tinniestudio.api.modules.library.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FavoriteResponse(
    UUID id,
    UUID contentId,
    Instant createdAt,
    ContentSummaryResponse content
) {}
```

- [ ] **Step 2: Write the failing tests**

Create `FavoriteServiceTest.java`:

```java
package com.tinniestudio.api.modules.library.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.library.dto.FavoriteResponse;
import com.tinniestudio.api.modules.library.repository.FavoriteRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.Favorite;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FavoriteService")
class FavoriteServiceTest {

    @Mock private FavoriteRepository favoriteRepo;
    @Mock private ContentRepository contentRepo;
    @InjectMocks private FavoriteServiceImpl favoriteService;

    private Content publishedContent(UUID id) {
        Content c = new Content();
        ReflectionTestUtils.setField(c, "id", id);
        c.setTitle("Interstellar");
        c.setSlug("interstellar");
        c.setType(ContentType.MOVIE);
        c.setStatus(ContentStatus.PUBLISHED);
        c.setMaturityRating(MaturityRating.PG);
        c.setFeatured(false);
        c.setComingSoon(false);
        c.setViewCount(0L);
        c.setCreatedBy(UUID.randomUUID());
        c.setCategories(new java.util.HashSet<>());
        return c;
    }

    private Favorite favorite(UUID userId, UUID contentId) {
        Favorite f = new Favorite();
        ReflectionTestUtils.setField(f, "id", UUID.randomUUID());
        f.setUserId(userId);
        f.setContentId(contentId);
        f.setCreatedAt(Instant.now());
        return f;
    }

    @Nested
    @DisplayName("add()")
    class AddTests {

        @Test
        @DisplayName("saves favorite when content exists and not already favorited")
        void savesWhenValid() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();

            when(favoriteRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(false);
            when(favoriteRepo.countByUserId(userId)).thenReturn(0L);
            when(contentRepo.findById(contentId)).thenReturn(Optional.of(publishedContent(contentId)));
            when(favoriteRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            favoriteService.add(userId, contentId);

            ArgumentCaptor<Favorite> captor = ArgumentCaptor.forClass(Favorite.class);
            verify(favoriteRepo).save(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(userId);
            assertThat(captor.getValue().getContentId()).isEqualTo(contentId);
        }

        @Test
        @DisplayName("throws 409 when already favorited")
        void throws409WhenDuplicate() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();

            when(favoriteRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(true);

            assertThatThrownBy(() -> favoriteService.add(userId, contentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("throws 400 when user has reached 500 favorites")
        void throws400WhenLimitReached() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();

            when(favoriteRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(false);
            when(favoriteRepo.countByUserId(userId)).thenReturn(500L);

            assertThatThrownBy(() -> favoriteService.add(userId, contentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("throws 404 when content does not exist")
        void throws404WhenContentNotFound() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();

            when(favoriteRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(false);
            when(favoriteRepo.countByUserId(userId)).thenReturn(0L);
            when(contentRepo.findById(contentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> favoriteService.add(userId, contentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("remove()")
    class RemoveTests {

        @Test
        @DisplayName("deletes favorite when it exists")
        void deletesWhenFound() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();
            Favorite f = favorite(userId, contentId);

            when(favoriteRepo.findByUserIdAndContentId(userId, contentId)).thenReturn(Optional.of(f));

            favoriteService.remove(userId, contentId);

            verify(favoriteRepo).delete(f);
        }

        @Test
        @DisplayName("throws 404 when favorite does not exist")
        void throws404WhenNotFound() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();

            when(favoriteRepo.findByUserIdAndContentId(userId, contentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> favoriteService.remove(userId, contentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("list()")
    class ListTests {

        @Test
        @DisplayName("returns page of favorites with content details")
        void returnsPageWithContent() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();
            Favorite f = favorite(userId, contentId);
            Content content = publishedContent(contentId);

            when(favoriteRepo.findByUserIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(f)));
            when(contentRepo.findAllById(List.of(contentId))).thenReturn(List.of(content));

            var page = favoriteService.list(userId, PageRequest.of(0, 20));

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).contentId()).isEqualTo(contentId);
            assertThat(page.getContent().get(0).content().title()).isEqualTo("Interstellar");
        }
    }
}
```

- [ ] **Step 3: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.library.service.FavoriteServiceTest" 2>&1 | tail -5
```
Expected: compilation failure.

- [ ] **Step 4: Create FavoriteService interface**

```java
package com.tinniestudio.api.modules.library.service;

import com.tinniestudio.api.modules.library.dto.FavoriteResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface FavoriteService {
    void add(UUID userId, UUID contentId);
    void remove(UUID userId, UUID contentId);
    Page<FavoriteResponse> list(UUID userId, Pageable pageable);
}
```

- [ ] **Step 5: Create FavoriteServiceImpl**

```java
package com.tinniestudio.api.modules.library.service;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.library.dto.FavoriteResponse;
import com.tinniestudio.api.modules.library.repository.FavoriteRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.Favorite;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    private static final int MAX_FAVORITES = 500;

    private final FavoriteRepository favoriteRepo;
    private final ContentRepository contentRepo;

    @Override
    @Transactional
    public void add(UUID userId, UUID contentId) {
        if (favoriteRepo.existsByUserIdAndContentId(userId, contentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Content already favorited");
        }
        if (favoriteRepo.countByUserId(userId) >= MAX_FAVORITES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Favorites limit of " + MAX_FAVORITES + " reached");
        }
        contentRepo.findById(contentId).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found"));

        Favorite favorite = new Favorite();
        favorite.setUserId(userId);
        favorite.setContentId(contentId);
        favoriteRepo.save(favorite);
    }

    @Override
    @Transactional
    public void remove(UUID userId, UUID contentId) {
        Favorite favorite = favoriteRepo.findByUserIdAndContentId(userId, contentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Favorite not found"));
        favoriteRepo.delete(favorite);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FavoriteResponse> list(UUID userId, Pageable pageable) {
        Page<Favorite> favPage = favoriteRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable);

        // Batch-load content details to avoid N+1
        var contentIds = favPage.map(Favorite::getContentId).toList();
        Map<UUID, ContentSummaryResponse> contentMap = contentRepo.findAllById(contentIds).stream()
            .collect(Collectors.toMap(Content::getId, ContentSummaryResponse::from));

        return favPage.map(f -> new FavoriteResponse(
            f.getId(),
            f.getContentId(),
            f.getCreatedAt(),
            contentMap.get(f.getContentId())
        ));
    }
}
```

- [ ] **Step 6: Run tests — all must pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.library.service.FavoriteServiceTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/library/dto/FavoriteResponse.java
git add api-service/src/main/java/com/tinniestudio/api/modules/library/service/FavoriteService.java
git add api-service/src/main/java/com/tinniestudio/api/modules/library/service/FavoriteServiceImpl.java
git add api-service/src/test/java/com/tinniestudio/api/modules/library/service/FavoriteServiceTest.java
git commit -m "feat(library): implement FavoriteService with add/remove/list and limit enforcement (TDD)"
```

---

## Task 4: WatchHistoryService (TDD) + PlaybackServiceImpl integration

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/library/dto/WatchHistoryResponse.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/library/service/WatchHistoryService.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/library/service/WatchHistoryServiceImpl.java`
- Create: `api-service/src/test/java/com/tinniestudio/api/modules/library/service/WatchHistoryServiceTest.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java`

- [ ] **Step 1: Create WatchHistoryResponse DTO**

```java
package com.tinniestudio.api.modules.library.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WatchHistoryResponse(
    UUID id,
    UUID contentId,
    UUID episodeId,
    Instant watchedAt,
    Integer progressSeconds,
    Integer durationSeconds,
    String deviceType,
    ContentSummaryResponse content
) {}
```

- [ ] **Step 2: Write the failing tests**

Create `WatchHistoryServiceTest.java`:

```java
package com.tinniestudio.api.modules.library.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.library.dto.WatchHistoryResponse;
import com.tinniestudio.api.modules.library.repository.WatchHistoryRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.WatchHistory;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WatchHistoryService")
class WatchHistoryServiceTest {

    @Mock private WatchHistoryRepository historyRepo;
    @Mock private ContentRepository contentRepo;
    @InjectMocks private WatchHistoryServiceImpl historyService;

    private Content content(UUID id) {
        Content c = new Content();
        ReflectionTestUtils.setField(c, "id", id);
        c.setTitle("Interstellar");
        c.setSlug("interstellar");
        c.setType(ContentType.MOVIE);
        c.setStatus(ContentStatus.PUBLISHED);
        c.setMaturityRating(MaturityRating.PG);
        c.setFeatured(false);
        c.setComingSoon(false);
        c.setViewCount(0L);
        c.setCreatedBy(UUID.randomUUID());
        c.setCategories(new java.util.HashSet<>());
        return c;
    }

    private WatchHistory history(UUID id, UUID userId, UUID contentId) {
        WatchHistory h = new WatchHistory();
        ReflectionTestUtils.setField(h, "id", id);
        h.setUserId(userId);
        h.setContentId(contentId);
        h.setWatchedAt(Instant.now());
        h.setProgressSeconds(300);
        h.setDurationSeconds(3600);
        return h;
    }

    @Nested
    @DisplayName("list()")
    class ListTests {

        @Test
        @DisplayName("returns page of history with content details")
        void returnsPageWithContent() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();
            UUID histId = UUID.randomUUID();
            WatchHistory h = history(histId, userId, contentId);
            Content c = content(contentId);

            when(historyRepo.findByUserIdOrderByWatchedAtDesc(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(h)));
            when(contentRepo.findAllById(List.of(contentId))).thenReturn(List.of(c));

            var page = historyService.list(userId, PageRequest.of(0, 50));

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).contentId()).isEqualTo(contentId);
            assertThat(page.getContent().get(0).content().title()).isEqualTo("Interstellar");
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("deletes entry when user owns it")
        void deletesWhenOwned() {
            UUID userId = UUID.randomUUID();
            UUID histId = UUID.randomUUID();
            WatchHistory h = history(histId, userId, UUID.randomUUID());

            when(historyRepo.findByIdAndUserId(histId, userId)).thenReturn(Optional.of(h));

            historyService.delete(userId, histId);

            verify(historyRepo).delete(h);
        }

        @Test
        @DisplayName("throws 404 when entry not found or not owned")
        void throws404WhenNotFound() {
            UUID userId = UUID.randomUUID();
            UUID histId = UUID.randomUUID();

            when(historyRepo.findByIdAndUserId(histId, userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> historyService.delete(userId, histId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("deleteAll()")
    class DeleteAllTests {

        @Test
        @DisplayName("deletes all history for user")
        void deletesAll() {
            UUID userId = UUID.randomUUID();

            historyService.deleteAll(userId);

            verify(historyRepo).deleteAllByUserId(userId);
        }
    }
}
```

- [ ] **Step 3: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.library.service.WatchHistoryServiceTest" 2>&1 | tail -5
```
Expected: compilation failure.

- [ ] **Step 4: Create WatchHistoryService interface**

```java
package com.tinniestudio.api.modules.library.service;

import com.tinniestudio.api.modules.library.dto.WatchHistoryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface WatchHistoryService {
    Page<WatchHistoryResponse> list(UUID userId, Pageable pageable);
    void delete(UUID userId, UUID historyId);
    void deleteAll(UUID userId);
}
```

- [ ] **Step 5: Create WatchHistoryServiceImpl**

```java
package com.tinniestudio.api.modules.library.service;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.library.dto.WatchHistoryResponse;
import com.tinniestudio.api.modules.library.repository.WatchHistoryRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.WatchHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WatchHistoryServiceImpl implements WatchHistoryService {

    private final WatchHistoryRepository historyRepo;
    private final ContentRepository contentRepo;

    @Override
    @Transactional(readOnly = true)
    public Page<WatchHistoryResponse> list(UUID userId, Pageable pageable) {
        Page<WatchHistory> page = historyRepo.findByUserIdOrderByWatchedAtDesc(userId, pageable);

        var contentIds = page.map(WatchHistory::getContentId).toList();
        Map<UUID, ContentSummaryResponse> contentMap = contentRepo.findAllById(contentIds).stream()
            .collect(Collectors.toMap(Content::getId, ContentSummaryResponse::from));

        return page.map(h -> new WatchHistoryResponse(
            h.getId(),
            h.getContentId(),
            h.getEpisodeId(),
            h.getWatchedAt(),
            h.getProgressSeconds(),
            h.getDurationSeconds(),
            h.getDeviceType(),
            contentMap.get(h.getContentId())
        ));
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID historyId) {
        WatchHistory entry = historyRepo.findByIdAndUserId(historyId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "History entry not found"));
        historyRepo.delete(entry);
    }

    @Override
    @Transactional
    public void deleteAll(UUID userId) {
        historyRepo.deleteAllByUserId(userId);
    }
}
```

- [ ] **Step 6: Integrate watch history into PlaybackServiceImpl.recordProgress()**

Read `api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java` first.

Add `WatchHistoryRepository` import and field injection:
```java
import com.tinniestudio.api.modules.library.repository.WatchHistoryRepository;
import com.tinniestudio.api.shared.entity.WatchHistory;
```

Add field (after existing fields):
```java
private final WatchHistoryRepository watchHistoryRepo;
```

At the end of `recordProgress()`, before the analytics publish block (but after `watchProgressRepo.save(progress)`), add:

```java
// Write watch history entry
WatchHistory historyEntry = new WatchHistory();
historyEntry.setUserId(userId);
historyEntry.setContentId(progress.getContentId());
historyEntry.setEpisodeId(req.getEpisodeId());
historyEntry.setProgressSeconds(req.getProgressSeconds());
historyEntry.setDurationSeconds(req.getDurationSeconds());
historyEntry.setDeviceType(req.getDeviceType());
watchHistoryRepo.save(historyEntry);
```

- [ ] **Step 7: Run all watch history tests + existing playback tests**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.library.service.WatchHistoryServiceTest" 2>&1 | tail -10
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.service.PlaybackServiceTest" 2>&1 | tail -5
```
Both must pass.

- [ ] **Step 8: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/library/dto/WatchHistoryResponse.java
git add api-service/src/main/java/com/tinniestudio/api/modules/library/service/
git add api-service/src/test/java/com/tinniestudio/api/modules/library/service/WatchHistoryServiceTest.java
git add api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java
git commit -m "feat(library): implement WatchHistoryService and integrate history write into playback progress (TDD)"
```

---

## Task 5: FavoriteController (TDD)

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/library/controller/FavoriteController.java`
- Create: `api-service/src/test/java/com/tinniestudio/api/modules/library/controller/FavoriteControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `FavoriteControllerTest.java`:

```java
package com.tinniestudio.api.modules.library.controller;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.library.dto.FavoriteResponse;
import com.tinniestudio.api.modules.library.service.FavoriteService;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.config.JwtTokenProvider;
import com.tinniestudio.api.shared.security.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.service.UserDetailsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = FavoriteController.class)
@AutoConfigureMockMvc(addFilters = false)
class FavoriteControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean  private FavoriteService favoriteService;
    @MockBean  private JwtTokenProvider jwtTokenProvider;
    @MockBean  private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean  private UserDetailsServiceImpl userDetailsService;
    @MockBean  private AppProperties appProperties;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    private static final ContentSummaryResponse MOVIE = new ContentSummaryResponse(
        UUID.randomUUID(), "Interstellar", "interstellar", "Space odyssey",
        "MOVIE", "PUBLISHED", "PG", LocalDate.of(2014, 11, 7),
        false, false, 1500L, null, null
    );

    @Test
    @DisplayName("GET /favorites returns 200 with page of favorites")
    @WithMockUser(username = USER_ID, roles = "USER")
    void list_returns200() throws Exception {
        FavoriteResponse fav = new FavoriteResponse(
            UUID.randomUUID(), MOVIE.id(), Instant.now(), MOVIE);

        when(favoriteService.list(any(UUID.class), any()))
            .thenReturn(new PageImpl<>(List.of(fav)));

        mockMvc.perform(get("/favorites").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].content.title").value("Interstellar"));
    }

    @Test
    @DisplayName("POST /favorites/{contentId} returns 201 when added")
    @WithMockUser(username = USER_ID, roles = "USER")
    void add_returns201() throws Exception {
        UUID contentId = UUID.randomUUID();
        doNothing().when(favoriteService).add(any(UUID.class), eq(contentId));

        mockMvc.perform(post("/favorites/" + contentId))
            .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("DELETE /favorites/{contentId} returns 204")
    @WithMockUser(username = USER_ID, roles = "USER")
    void remove_returns204() throws Exception {
        UUID contentId = UUID.randomUUID();
        doNothing().when(favoriteService).remove(any(UUID.class), eq(contentId));

        mockMvc.perform(delete("/favorites/" + contentId))
            .andExpect(status().isNoContent());
    }
}
```

Note: Read `PlaybackControllerTest.java` to confirm which `@MockBean` entries are required for `@WebMvcTest` in this project. Use the same ones.

- [ ] **Step 2: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.library.controller.FavoriteControllerTest" 2>&1 | tail -5
```
Expected: compilation failure.

- [ ] **Step 3: Create FavoriteController**

```java
package com.tinniestudio.api.modules.library.controller;

import com.tinniestudio.api.modules.library.dto.FavoriteResponse;
import com.tinniestudio.api.modules.library.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Favorites", description = "User favorites management")
@RestController
@RequestMapping("/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @Operation(summary = "List the authenticated user's favorites (paginated)")
    @GetMapping
    public ResponseEntity<Page<FavoriteResponse>> list(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(favoriteService.list(userId(principal), pageable));
    }

    @Operation(summary = "Add a content item to favorites")
    @PostMapping("/{contentId}")
    public ResponseEntity<Void> add(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId) {
        favoriteService.add(userId(principal), contentId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "Remove a content item from favorites")
    @DeleteMapping("/{contentId}")
    public ResponseEntity<Void> remove(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId) {
        favoriteService.remove(userId(principal), contentId);
        return ResponseEntity.noContent().build();
    }

    private UUID userId(UserDetails principal) {
        if (principal == null) throw new AuthenticationCredentialsNotFoundException("No credentials");
        return UUID.fromString(principal.getUsername());
    }
}
```

- [ ] **Step 4: Run tests — all must pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.library.controller.FavoriteControllerTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/library/controller/FavoriteController.java
git add api-service/src/test/java/com/tinniestudio/api/modules/library/controller/FavoriteControllerTest.java
git commit -m "feat(library): implement FavoriteController with GET/POST/DELETE endpoints (TDD)"
```

---

## Task 6: HistoryController (TDD)

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/library/controller/HistoryController.java`
- Create: `api-service/src/test/java/com/tinniestudio/api/modules/library/controller/HistoryControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `HistoryControllerTest.java`:

```java
package com.tinniestudio.api.modules.library.controller;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.library.dto.WatchHistoryResponse;
import com.tinniestudio.api.modules.library.service.WatchHistoryService;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.config.JwtTokenProvider;
import com.tinniestudio.api.shared.security.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.service.UserDetailsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = HistoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class HistoryControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean  private WatchHistoryService historyService;
    @MockBean  private JwtTokenProvider jwtTokenProvider;
    @MockBean  private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean  private UserDetailsServiceImpl userDetailsService;
    @MockBean  private AppProperties appProperties;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    private static final ContentSummaryResponse MOVIE = new ContentSummaryResponse(
        UUID.randomUUID(), "Interstellar", "interstellar", "Space odyssey",
        "MOVIE", "PUBLISHED", "PG", LocalDate.of(2014, 11, 7),
        false, false, 1500L, null, null
    );

    @Test
    @DisplayName("GET /history returns 200 with page of history")
    @WithMockUser(username = USER_ID, roles = "USER")
    void list_returns200() throws Exception {
        WatchHistoryResponse item = new WatchHistoryResponse(
            UUID.randomUUID(), MOVIE.id(), null,
            Instant.now(), 300, 3600, null, MOVIE);

        when(historyService.list(any(UUID.class), any()))
            .thenReturn(new PageImpl<>(List.of(item)));

        mockMvc.perform(get("/history").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].content.title").value("Interstellar"));
    }

    @Test
    @DisplayName("DELETE /history/{id} returns 204")
    @WithMockUser(username = USER_ID, roles = "USER")
    void delete_returns204() throws Exception {
        UUID histId = UUID.randomUUID();
        doNothing().when(historyService).delete(any(UUID.class), eq(histId));

        mockMvc.perform(delete("/history/" + histId))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /history returns 204 (clear all)")
    @WithMockUser(username = USER_ID, roles = "USER")
    void deleteAll_returns204() throws Exception {
        doNothing().when(historyService).deleteAll(any(UUID.class));

        mockMvc.perform(delete("/history"))
            .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.library.controller.HistoryControllerTest" 2>&1 | tail -5
```
Expected: compilation failure.

- [ ] **Step 3: Create HistoryController**

```java
package com.tinniestudio.api.modules.library.controller;

import com.tinniestudio.api.modules.library.dto.WatchHistoryResponse;
import com.tinniestudio.api.modules.library.service.WatchHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Watch History", description = "User watch history")
@RestController
@RequestMapping("/history")
@RequiredArgsConstructor
public class HistoryController {

    private final WatchHistoryService historyService;

    @Operation(summary = "Get the authenticated user's watch history (paginated)")
    @GetMapping
    public ResponseEntity<Page<WatchHistoryResponse>> list(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(historyService.list(userId(principal), pageable));
    }

    @Operation(summary = "Delete a single watch history entry")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id) {
        historyService.delete(userId(principal), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Clear all watch history for the authenticated user")
    @DeleteMapping
    public ResponseEntity<Void> deleteAll(@AuthenticationPrincipal UserDetails principal) {
        historyService.deleteAll(userId(principal));
        return ResponseEntity.noContent().build();
    }

    private UUID userId(UserDetails principal) {
        if (principal == null) throw new AuthenticationCredentialsNotFoundException("No credentials");
        return UUID.fromString(principal.getUsername());
    }
}
```

- [ ] **Step 4: Run all tests**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.library.controller.HistoryControllerTest" 2>&1 | tail -10
./gradlew :api-service:test 2>&1 | tail -5
```
Both must pass.

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/library/controller/HistoryController.java
git add api-service/src/test/java/com/tinniestudio/api/modules/library/controller/HistoryControllerTest.java
git commit -m "feat(library): implement HistoryController with GET/DELETE/DELETE-all endpoints (TDD)"
```

---

## Self-Review

**Spec coverage:**
- ✅ 10.1 `favorites` table — V31 migration with unique constraint + cascade delete
- ✅ 10.1 `watch_history` table — V32 migration
- ✅ 10.2 Favorites toggle: `POST /favorites/{contentId}` creates if not exists, 409 if duplicate; `DELETE /favorites/{contentId}` removes; `GET /favorites` paginated list with content details
- ✅ 10.2 Max 500 favorites per user — enforced in `FavoriteServiceImpl`
- ✅ 10.2 Watch history: `GET /history` ordered by `watched_at DESC`, paginated (default 50); `DELETE /history/{id}` checks ownership; `DELETE /history` clears all for user
- ✅ "Watch history populated from playback progress" — `PlaybackServiceImpl.recordProgress()` writes a `WatchHistory` entry
- ✅ 10.3 All 6 endpoints covered
- ✅ Batch 10 completion criteria: favorites add/remove/list, watch history from playback, clear all working

**No placeholders found.**

**Type consistency:** `FavoriteResponse` and `WatchHistoryResponse` both use `ContentSummaryResponse` for content enrichment, consistent with `ContinueWatchingItem` in the playback module. `userId()` helper pattern matches `PlaybackController`.

---

Plan saved to `docs/superpowers/plans/2026-07-12-batch-10-favorites-watch-history.md`.

**Two execution options:**

**1. Subagent-Driven (recommended)** — fresh subagent per task, spec + quality review between tasks

**2. Inline Execution** — execute tasks in this session using executing-plans

Which approach?
