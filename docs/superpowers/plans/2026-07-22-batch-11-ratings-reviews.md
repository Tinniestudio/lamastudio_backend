# Batch 11 — Ratings + Reviews + Aggregate Scoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow authenticated users to rate and review content (1–5 stars, optional text body), with aggregate scoring (average_rating + review_count) auto-maintained by a PostgreSQL trigger, and admin moderation to approve/reject reviews post-hoc.

**Architecture:** New `content_reviews` table (V33). Aggregate columns `average_rating` + `review_count` added to `contents` (V34) with a PostgreSQL trigger that recalculates them after every insert/update/delete on `content_reviews`. Reviews auto-publish (`APPROVED`) on creation; admin can change status. One review per user per content (unique DB constraint). New `modules/reviews/` module.

**Tech Stack:** Spring Boot 3 · Spring Data JPA · PostgreSQL trigger for aggregate · JUnit 5 + Mockito + `@WebMvcTest`

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `db/migration/V33__add_content_reviews.sql` | reviews table + unique constraint |
| Create | `db/migration/V34__add_review_aggregate.sql` | aggregate columns + PostgreSQL trigger |
| Modify | `shared/entity/DomainEnums.java` | Add `ReviewStatus` enum |
| Create | `shared/entity/ContentReview.java` | ContentReview JPA entity |
| Modify | `shared/entity/Content.java` | Add `averageRating`, `reviewCount` fields |
| Create | `modules/reviews/repository/ReviewRepository.java` | Spring Data repo |
| Create | `modules/reviews/dto/CreateReviewRequest.java` | rating (1-5) + optional body |
| Create | `modules/reviews/dto/UpdateReviewRequest.java` | partial update |
| Create | `modules/reviews/dto/ReviewResponse.java` | response DTO |
| Create | `modules/reviews/dto/UpdateReviewStatusRequest.java` | admin status change |
| Create | `modules/reviews/service/ReviewService.java` | Interface |
| Create | `modules/reviews/service/ReviewServiceImpl.java` | list, create, update, delete, moderateStatus |
| Create | `modules/reviews/controller/ReviewController.java` | User-facing 4 endpoints |
| Create | `modules/reviews/controller/AdminReviewController.java` | Admin moderation endpoint |
| Create | test files (6 test classes) | Service + controller TDD |
| Modify | `modules/content/dto/ContentResponse.java` | Expose averageRating + reviewCount |
| Modify | `modules/content/dto/ContentSummaryResponse.java` | Expose averageRating |

All paths relative to `api-service/src/main/java/com/tinniestudio/api/` (and `src/test/…` for tests).

---

## Task 1: DB Migrations V33 + V34

**Files:**
- Create: `api-service/src/main/resources/db/migration/V33__add_content_reviews.sql`
- Create: `api-service/src/main/resources/db/migration/V34__add_review_aggregate.sql`

- [ ] **Step 1: Create V33__add_content_reviews.sql**

```sql
CREATE TABLE IF NOT EXISTS content_reviews (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content_id  UUID        NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    rating      SMALLINT    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body        TEXT,
    status      VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_review_user_content UNIQUE (user_id, content_id)
);

CREATE INDEX idx_reviews_content_id ON content_reviews(content_id);
CREATE INDEX idx_reviews_user_id    ON content_reviews(user_id);
CREATE INDEX idx_reviews_status     ON content_reviews(status);
```

- [ ] **Step 2: Create V34__add_review_aggregate.sql**

```sql
ALTER TABLE contents
    ADD COLUMN IF NOT EXISTS average_rating NUMERIC(3,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS review_count   INTEGER      NOT NULL DEFAULT 0;

-- Function: recalculate aggregate for one content row
CREATE OR REPLACE FUNCTION update_content_review_aggregate()
RETURNS TRIGGER AS $$
DECLARE
    target_content_id UUID;
BEGIN
    -- NEW is NULL on DELETE, OLD is NULL on INSERT
    target_content_id := COALESCE(NEW.content_id, OLD.content_id);

    UPDATE contents
    SET
        average_rating = COALESCE(
            (SELECT AVG(rating::numeric)
             FROM content_reviews
             WHERE content_id = target_content_id
               AND status = 'APPROVED'),
            0
        ),
        review_count = (
            SELECT COUNT(*)
            FROM content_reviews
            WHERE content_id = target_content_id
              AND status = 'APPROVED'
        )
    WHERE id = target_content_id;

    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_content_review_aggregate
    AFTER INSERT OR UPDATE OR DELETE ON content_reviews
    FOR EACH ROW
    EXECUTE FUNCTION update_content_review_aggregate();
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew :api-service:compileJava 2>&1 | grep -E "ERROR|error" | head -5
```
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add api-service/src/main/resources/db/migration/V33__add_content_reviews.sql
git add api-service/src/main/resources/db/migration/V34__add_review_aggregate.sql
git commit -m "feat(reviews): add content_reviews table and aggregate trigger migrations (V33, V34)"
```

---

## Task 2: ReviewStatus enum + ContentReview entity + Repository + Content update

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/shared/entity/ContentReview.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/reviews/repository/ReviewRepository.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/Content.java`

- [ ] **Step 1: Add ReviewStatus to DomainEnums.java**

Read the file first. Inside the `DomainEnums` class, add:

```java
public enum ReviewStatus {
    PENDING,
    APPROVED,
    REJECTED
}
```

- [ ] **Step 2: Create ContentReview entity**

```java
package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.ReviewStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(
    name = "content_reviews",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_review_user_content",
        columnNames = {"user_id", "content_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class ContentReview extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(nullable = false)
    private Short rating;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status = ReviewStatus.APPROVED;
}
```

- [ ] **Step 3: Add averageRating + reviewCount to Content entity**

Read `Content.java`. Add these two fields after the `maturityRating` field:

```java
@Column(nullable = false, precision = 3, scale = 2)
private java.math.BigDecimal averageRating = java.math.BigDecimal.ZERO;

@Column(nullable = false)
private Integer reviewCount = 0;
```

- [ ] **Step 4: Create ReviewRepository**

```java
package com.tinniestudio.api.modules.reviews.repository;

import com.tinniestudio.api.shared.entity.ContentReview;
import com.tinniestudio.api.shared.entity.DomainEnums.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<ContentReview, UUID> {
    Page<ContentReview> findByContentIdAndStatusOrderByCreatedAtDesc(UUID contentId, ReviewStatus status, Pageable pageable);
    boolean existsByUserIdAndContentId(UUID userId, UUID contentId);
    Optional<ContentReview> findByIdAndUserId(UUID id, UUID userId);
}
```

- [ ] **Step 5: Verify compilation**

```bash
./gradlew :api-service:compileJava 2>&1 | grep -E "ERROR|error" | head -5
```
Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/ContentReview.java
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/Content.java
git add api-service/src/main/java/com/tinniestudio/api/modules/reviews/repository/ReviewRepository.java
git commit -m "feat(reviews): add ReviewStatus enum, ContentReview entity, repository, and Content aggregate fields"
```

---

## Task 3: DTOs + ReviewService (TDD)

**Files:**
- Create: `modules/reviews/dto/CreateReviewRequest.java`
- Create: `modules/reviews/dto/UpdateReviewRequest.java`
- Create: `modules/reviews/dto/ReviewResponse.java`
- Create: `modules/reviews/dto/UpdateReviewStatusRequest.java`
- Create: `modules/reviews/service/ReviewService.java`
- Create: `modules/reviews/service/ReviewServiceImpl.java`
- Create: `test/.../modules/reviews/service/ReviewServiceTest.java`

- [ ] **Step 1: Create DTOs**

**CreateReviewRequest.java:**
```java
package com.tinniestudio.api.modules.reviews.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateReviewRequest {

    @NotNull
    @Min(1)
    @Max(5)
    private Short rating;

    @Size(max = 2000)
    private String body;
}
```

**UpdateReviewRequest.java:**
```java
package com.tinniestudio.api.modules.reviews.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateReviewRequest {

    @Min(1)
    @Max(5)
    private Short rating;

    @Size(max = 2000)
    private String body;
}
```

**ReviewResponse.java:**
```java
package com.tinniestudio.api.modules.reviews.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tinniestudio.api.shared.entity.ContentReview;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewResponse(
    UUID id,
    UUID contentId,
    UUID userId,
    Short rating,
    String body,
    String status,
    Instant createdAt,
    Instant updatedAt
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

**UpdateReviewStatusRequest.java:**
```java
package com.tinniestudio.api.modules.reviews.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.ReviewStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateReviewStatusRequest {

    @NotNull
    private ReviewStatus status;
}
```

- [ ] **Step 2: Write the failing tests**

Create `api-service/src/test/java/com/tinniestudio/api/modules/reviews/service/ReviewServiceTest.java`:

```java
package com.tinniestudio.api.modules.reviews.service;

import com.tinniestudio.api.modules.reviews.dto.CreateReviewRequest;
import com.tinniestudio.api.modules.reviews.dto.ReviewResponse;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewRequest;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewStatusRequest;
import com.tinniestudio.api.modules.reviews.repository.ReviewRepository;
import com.tinniestudio.api.shared.entity.ContentReview;
import com.tinniestudio.api.shared.entity.DomainEnums.ReviewStatus;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewService")
class ReviewServiceTest {

    @Mock private ReviewRepository reviewRepo;
    @InjectMocks private ReviewServiceImpl reviewService;

    private ContentReview review(UUID id, UUID userId, UUID contentId) {
        ContentReview r = new ContentReview();
        ReflectionTestUtils.setField(r, "id", id);
        r.setUserId(userId);
        r.setContentId(contentId);
        r.setRating((short) 4);
        r.setBody("Great content!");
        r.setStatus(ReviewStatus.APPROVED);
        return r;
    }

    @Nested
    @DisplayName("list()")
    class ListTests {

        @Test
        @DisplayName("returns page of approved reviews for content")
        void returnsApprovedReviews() {
            UUID contentId = UUID.randomUUID();
            ContentReview r = review(UUID.randomUUID(), UUID.randomUUID(), contentId);

            when(reviewRepo.findByContentIdAndStatusOrderByCreatedAtDesc(
                    eq(contentId), eq(ReviewStatus.APPROVED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r)));

            var page = reviewService.list(contentId, PageRequest.of(0, 20));

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).rating()).isEqualTo((short) 4);
        }
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("saves review when user has not reviewed content before")
        void savesReview() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();
            CreateReviewRequest req = new CreateReviewRequest();
            req.setRating((short) 5);
            req.setBody("Excellent!");

            when(reviewRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(false);
            when(reviewRepo.save(any())).thenAnswer(inv -> {
                ContentReview saved = inv.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            ReviewResponse result = reviewService.create(userId, contentId, req);

            ArgumentCaptor<ContentReview> captor = ArgumentCaptor.forClass(ContentReview.class);
            verify(reviewRepo).save(captor.capture());
            assertThat(captor.getValue().getRating()).isEqualTo((short) 5);
            assertThat(captor.getValue().getStatus()).isEqualTo(ReviewStatus.APPROVED);
            assertThat(result.contentId()).isEqualTo(contentId);
        }

        @Test
        @DisplayName("throws 409 when user has already reviewed content")
        void throws409WhenDuplicate() {
            UUID userId = UUID.randomUUID();
            UUID contentId = UUID.randomUUID();
            CreateReviewRequest req = new CreateReviewRequest();
            req.setRating((short) 3);

            when(reviewRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(true);

            assertThatThrownBy(() -> reviewService.create(userId, contentId, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
        }
    }

    @Nested
    @DisplayName("update()")
    class UpdateTests {

        @Test
        @DisplayName("updates rating and body when user owns the review")
        void updatesWhenOwned() {
            UUID userId = UUID.randomUUID();
            UUID reviewId = UUID.randomUUID();
            ContentReview existing = review(reviewId, userId, UUID.randomUUID());

            UpdateReviewRequest req = new UpdateReviewRequest();
            req.setRating((short) 2);
            req.setBody("Changed my mind");

            when(reviewRepo.findByIdAndUserId(reviewId, userId)).thenReturn(Optional.of(existing));
            when(reviewRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ReviewResponse result = reviewService.update(userId, reviewId, req);

            assertThat(result.rating()).isEqualTo((short) 2);
            assertThat(result.body()).isEqualTo("Changed my mind");
        }

        @Test
        @DisplayName("throws 404 when review not owned by user")
        void throws404WhenNotOwned() {
            UUID userId = UUID.randomUUID();
            UUID reviewId = UUID.randomUUID();

            when(reviewRepo.findByIdAndUserId(reviewId, userId)).thenReturn(Optional.empty());

            UpdateReviewRequest req = new UpdateReviewRequest();
            req.setRating((short) 1);

            assertThatThrownBy(() -> reviewService.update(userId, reviewId, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("deletes when user owns the review")
        void deletesWhenOwned() {
            UUID userId = UUID.randomUUID();
            UUID reviewId = UUID.randomUUID();
            ContentReview r = review(reviewId, userId, UUID.randomUUID());

            when(reviewRepo.findByIdAndUserId(reviewId, userId)).thenReturn(Optional.of(r));

            reviewService.delete(userId, reviewId);

            verify(reviewRepo).delete(r);
        }

        @Test
        @DisplayName("throws 404 when not owned")
        void throws404WhenNotOwned() {
            UUID userId = UUID.randomUUID();
            UUID reviewId = UUID.randomUUID();

            when(reviewRepo.findByIdAndUserId(reviewId, userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reviewService.delete(userId, reviewId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("moderateStatus()")
    class ModerateTests {

        @Test
        @DisplayName("changes review status to REJECTED")
        void changesStatus() {
            UUID reviewId = UUID.randomUUID();
            ContentReview r = review(reviewId, UUID.randomUUID(), UUID.randomUUID());

            UpdateReviewStatusRequest req = new UpdateReviewStatusRequest();
            req.setStatus(ReviewStatus.REJECTED);

            when(reviewRepo.findById(reviewId)).thenReturn(Optional.of(r));
            when(reviewRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ReviewResponse result = reviewService.moderateStatus(reviewId, req);

            assertThat(result.status()).isEqualTo("REJECTED");
        }

        @Test
        @DisplayName("throws 404 when review not found")
        void throws404WhenNotFound() {
            UUID reviewId = UUID.randomUUID();
            when(reviewRepo.findById(reviewId)).thenReturn(Optional.empty());

            UpdateReviewStatusRequest req = new UpdateReviewStatusRequest();
            req.setStatus(ReviewStatus.APPROVED);

            assertThatThrownBy(() -> reviewService.moderateStatus(reviewId, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }
}
```

- [ ] **Step 3: Run tests — expect failure**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.reviews.service.ReviewServiceTest" 2>&1 | tail -5
```
Expected: compilation failure.

- [ ] **Step 4: Create ReviewService interface**

```java
package com.tinniestudio.api.modules.reviews.service;

import com.tinniestudio.api.modules.reviews.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ReviewService {
    Page<ReviewResponse> list(UUID contentId, Pageable pageable);
    ReviewResponse create(UUID userId, UUID contentId, CreateReviewRequest request);
    ReviewResponse update(UUID userId, UUID reviewId, UpdateReviewRequest request);
    void delete(UUID userId, UUID reviewId);
    ReviewResponse moderateStatus(UUID reviewId, UpdateReviewStatusRequest request);
}
```

- [ ] **Step 5: Create ReviewServiceImpl**

```java
package com.tinniestudio.api.modules.reviews.service;

import com.tinniestudio.api.modules.reviews.dto.*;
import com.tinniestudio.api.modules.reviews.repository.ReviewRepository;
import com.tinniestudio.api.shared.entity.ContentReview;
import com.tinniestudio.api.shared.entity.DomainEnums.ReviewStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRepository reviewRepo;

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewResponse> list(UUID contentId, Pageable pageable) {
        return reviewRepo
            .findByContentIdAndStatusOrderByCreatedAtDesc(contentId, ReviewStatus.APPROVED, pageable)
            .map(ReviewResponse::from);
    }

    @Override
    @Transactional
    public ReviewResponse create(UUID userId, UUID contentId, CreateReviewRequest request) {
        if (reviewRepo.existsByUserIdAndContentId(userId, contentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already reviewed this content");
        }

        ContentReview review = new ContentReview();
        review.setUserId(userId);
        review.setContentId(contentId);
        review.setRating(request.getRating());
        review.setBody(request.getBody());
        review.setStatus(ReviewStatus.APPROVED);

        return ReviewResponse.from(reviewRepo.save(review));
    }

    @Override
    @Transactional
    public ReviewResponse update(UUID userId, UUID reviewId, UpdateReviewRequest request) {
        ContentReview review = reviewRepo.findByIdAndUserId(reviewId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found"));

        if (request.getRating() != null) review.setRating(request.getRating());
        if (request.getBody() != null)   review.setBody(request.getBody());

        return ReviewResponse.from(reviewRepo.save(review));
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID reviewId) {
        ContentReview review = reviewRepo.findByIdAndUserId(reviewId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found"));
        reviewRepo.delete(review);
    }

    @Override
    @Transactional
    public ReviewResponse moderateStatus(UUID reviewId, UpdateReviewStatusRequest request) {
        ContentReview review = reviewRepo.findById(reviewId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found"));
        review.setStatus(request.getStatus());
        return ReviewResponse.from(reviewRepo.save(review));
    }
}
```

- [ ] **Step 6: Run tests — all must pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.reviews.service.ReviewServiceTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, 9 tests pass.

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/reviews/dto/
git add api-service/src/main/java/com/tinniestudio/api/modules/reviews/service/ReviewService.java
git add api-service/src/main/java/com/tinniestudio/api/modules/reviews/service/ReviewServiceImpl.java
git add api-service/src/test/java/com/tinniestudio/api/modules/reviews/service/ReviewServiceTest.java
git commit -m "feat(reviews): implement ReviewService with create/update/delete/moderate (TDD)"
```

---

## Task 4: ReviewController (TDD)

**Files:**
- Create: `modules/reviews/controller/ReviewController.java`
- Create: `test/.../modules/reviews/controller/ReviewControllerTest.java`

- [ ] **Step 1: Check admin controller auth pattern**

Read `api-service/src/main/java/com/tinniestudio/api/modules/content/controller/AdminContentController.java` (first 30 lines) to confirm how `ADMIN` role is enforced. Match that pattern in AdminReviewController.

Also read `PlaybackControllerTest.java` (first 45 lines) to confirm which `@MockBean` entries are needed for `@WebMvcTest`.

- [ ] **Step 2: Write the failing tests**

Create `api-service/src/test/java/com/tinniestudio/api/modules/reviews/controller/ReviewControllerTest.java`:

```java
package com.tinniestudio.api.modules.reviews.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.reviews.dto.CreateReviewRequest;
import com.tinniestudio.api.modules.reviews.dto.ReviewResponse;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewRequest;
import com.tinniestudio.api.modules.reviews.service.ReviewService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReviewControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private ReviewService reviewService;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    private ReviewResponse sampleResponse(UUID contentId) {
        return new ReviewResponse(
            UUID.randomUUID(), contentId, UUID.fromString(USER_ID),
            (short) 4, "Great movie!", "APPROVED",
            Instant.now(), Instant.now()
        );
    }

    @Test
    @DisplayName("GET /contents/{contentId}/reviews returns 200 with paginated reviews")
    void listReviews_returns200() throws Exception {
        UUID contentId = UUID.randomUUID();
        when(reviewService.list(eq(contentId), any()))
            .thenReturn(new PageImpl<>(List.of(sampleResponse(contentId))));

        mockMvc.perform(get("/contents/" + contentId + "/reviews"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].rating").value(4));
    }

    @Test
    @DisplayName("POST /contents/{contentId}/reviews returns 201 when created")
    @WithMockUser(username = USER_ID, roles = "USER")
    void createReview_returns201() throws Exception {
        UUID contentId = UUID.randomUUID();
        CreateReviewRequest req = new CreateReviewRequest();
        req.setRating((short) 5);
        req.setBody("Excellent!");

        when(reviewService.create(any(), eq(contentId), any()))
            .thenReturn(sampleResponse(contentId));

        mockMvc.perform(post("/contents/" + contentId + "/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.rating").value(4));
    }

    @Test
    @DisplayName("PATCH /reviews/{id} returns 200 with updated review")
    @WithMockUser(username = USER_ID, roles = "USER")
    void updateReview_returns200() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setRating((short) 3);

        when(reviewService.update(any(), eq(reviewId), any()))
            .thenReturn(sampleResponse(contentId));

        mockMvc.perform(patch("/reviews/" + reviewId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /reviews/{id} returns 204")
    @WithMockUser(username = USER_ID, roles = "USER")
    void deleteReview_returns204() throws Exception {
        UUID reviewId = UUID.randomUUID();
        doNothing().when(reviewService).delete(any(), eq(reviewId));

        mockMvc.perform(delete("/reviews/" + reviewId))
            .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 3: Run tests — expect compilation failure**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.reviews.controller.ReviewControllerTest" 2>&1 | tail -5
```
Expected: compilation failure.

- [ ] **Step 4: Create ReviewController**

```java
package com.tinniestudio.api.modules.reviews.controller;

import com.tinniestudio.api.modules.reviews.dto.*;
import com.tinniestudio.api.modules.reviews.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

@Tag(name = "Reviews", description = "Content ratings and reviews")
@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "List approved reviews for a content item")
    @GetMapping("/contents/{contentId}/reviews")
    public ResponseEntity<Page<ReviewResponse>> list(
            @PathVariable UUID contentId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(reviewService.list(contentId, pageable));
    }

    @Operation(summary = "Submit a review for a content item")
    @PostMapping("/contents/{contentId}/reviews")
    public ResponseEntity<ReviewResponse> create(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId,
            @Valid @RequestBody CreateReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(reviewService.create(userId(principal), contentId, request));
    }

    @Operation(summary = "Update the authenticated user's own review")
    @PatchMapping("/reviews/{id}")
    public ResponseEntity<ReviewResponse> update(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateReviewRequest request) {
        return ResponseEntity.ok(reviewService.update(userId(principal), id, request));
    }

    @Operation(summary = "Delete the authenticated user's own review")
    @DeleteMapping("/reviews/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id) {
        reviewService.delete(userId(principal), id);
        return ResponseEntity.noContent().build();
    }

    private UUID userId(UserDetails principal) {
        if (principal == null) throw new AuthenticationCredentialsNotFoundException("No credentials");
        return UUID.fromString(principal.getUsername());
    }
}
```

- [ ] **Step 5: Run tests — all must pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.reviews.controller.ReviewControllerTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/reviews/controller/ReviewController.java
git add api-service/src/test/java/com/tinniestudio/api/modules/reviews/controller/ReviewControllerTest.java
git commit -m "feat(reviews): implement ReviewController with list/create/update/delete endpoints (TDD)"
```

---

## Task 5: AdminReviewController (TDD)

**Files:**
- Create: `modules/reviews/controller/AdminReviewController.java`
- Create: `test/.../modules/reviews/controller/AdminReviewControllerTest.java`

- [ ] **Step 1: Write the failing test**

Before writing, read the first 30 lines of `api-service/src/main/java/com/tinniestudio/api/modules/content/controller/AdminContentController.java` to understand how admin role is enforced in this project. Match the same pattern exactly.

Create `api-service/src/test/java/com/tinniestudio/api/modules/reviews/controller/AdminReviewControllerTest.java`:

```java
package com.tinniestudio.api.modules.reviews.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.reviews.dto.ReviewResponse;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewStatusRequest;
import com.tinniestudio.api.modules.reviews.service.ReviewService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.entity.DomainEnums.ReviewStatus;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminReviewControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private ReviewService reviewService;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    @Test
    @DisplayName("PATCH /admin/reviews/{id}/status returns 200 with updated review")
    @WithMockUser(username = "admin-user", roles = "ADMIN")
    void moderateStatus_returns200() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UpdateReviewStatusRequest req = new UpdateReviewStatusRequest();
        req.setStatus(ReviewStatus.REJECTED);

        ReviewResponse response = new ReviewResponse(
            reviewId, UUID.randomUUID(), UUID.randomUUID(),
            (short) 3, "Spam content", "REJECTED",
            Instant.now(), Instant.now()
        );

        when(reviewService.moderateStatus(eq(reviewId), any())).thenReturn(response);

        mockMvc.perform(patch("/admin/reviews/" + reviewId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }
}
```

- [ ] **Step 2: Run test — expect compilation failure**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.reviews.controller.AdminReviewControllerTest" 2>&1 | tail -5
```
Expected: compilation failure.

- [ ] **Step 3: Create AdminReviewController**

Read `AdminContentController.java` header first to match the admin role enforcement pattern. Then create:

```java
package com.tinniestudio.api.modules.reviews.controller;

import com.tinniestudio.api.modules.reviews.dto.ReviewResponse;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewStatusRequest;
import com.tinniestudio.api.modules.reviews.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Admin — Reviews", description = "Admin review moderation")
@RestController
@RequestMapping("/admin/reviews")
@RequiredArgsConstructor
public class AdminReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "Change the moderation status of a review (APPROVED / REJECTED)")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ReviewResponse> moderateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateReviewStatusRequest request) {
        return ResponseEntity.ok(reviewService.moderateStatus(id, request));
    }
}
```

**IMPORTANT:** If `AdminContentController` uses `@PreAuthorize("hasRole('ADMIN')")` at the class level, add it here too. Match exactly.

- [ ] **Step 4: Run tests — all must pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.reviews.controller.AdminReviewControllerTest" 2>&1 | tail -10
```

- [ ] **Step 5: Run full test suite**

```bash
./gradlew :api-service:test 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/reviews/controller/AdminReviewController.java
git add api-service/src/test/java/com/tinniestudio/api/modules/reviews/controller/AdminReviewControllerTest.java
git commit -m "feat(reviews): implement AdminReviewController for review moderation (TDD)"
```

---

## Task 6: Expose averageRating + reviewCount in Content responses

**Files:**
- Modify: `modules/content/dto/ContentResponse.java`
- Modify: `modules/content/dto/ContentSummaryResponse.java`
- Modify: `modules/content/dto/ContentSummaryResponse.java` — update `from()` method
- Modify: `modules/content/dto/ContentResponse.java` — update `from()` method

- [ ] **Step 1: Add averageRating + reviewCount to ContentResponse**

Read `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentResponse.java`.

Add two new fields to the record:
- `java.math.BigDecimal averageRating` — after `thumbnailUrl`
- `Integer reviewCount` — after `averageRating`

Update `from(Content c)` to map:
```java
c.getAverageRating(), c.getReviewCount()
```

**Full updated record signature:**
```java
public record ContentResponse(
    UUID id, String title, String slug, String description, String shortDescription,
    String type, String status, String maturityRating,
    LocalDate releaseDate, String language, String country,
    Boolean featured, Boolean comingSoon, Long viewCount,
    Integer durationSeconds, String posterUrl, String thumbnailUrl,
    java.math.BigDecimal averageRating, Integer reviewCount,
    List<String> categoryNames, Instant publishedAt
) {
    public static ContentResponse from(Content c) {
        return new ContentResponse(
            c.getId(), c.getTitle(), c.getSlug(),
            c.getDescription(), c.getShortDescription(),
            c.getType().name(), c.getStatus().name(), c.getMaturityRating().name(),
            c.getReleaseDate(), c.getLanguage(), c.getCountry(),
            c.getFeatured(), c.getComingSoon(), c.getViewCount(),
            c.getDurationSeconds(), c.getPosterUrl(), c.getThumbnailUrl(),
            c.getAverageRating(), c.getReviewCount(),
            c.getCategories().stream().map(cat -> cat.getName()).toList(),
            c.getPublishedAt()
        );
    }
}
```

- [ ] **Step 2: Add averageRating to ContentSummaryResponse**

Read `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentSummaryResponse.java`.

Add `java.math.BigDecimal averageRating` and `Integer reviewCount` to the record (after `viewCount`):

**Full updated record signature:**
```java
public record ContentSummaryResponse(
    UUID id, String title, String slug, String shortDescription,
    String type, String status, String maturityRating,
    LocalDate releaseDate, Boolean featured, Boolean comingSoon,
    Long viewCount, java.math.BigDecimal averageRating, Integer reviewCount,
    String posterUrl, String thumbnailUrl
) {
    public static ContentSummaryResponse from(Content c) {
        return new ContentSummaryResponse(
            c.getId(), c.getTitle(), c.getSlug(), c.getShortDescription(),
            c.getType().name(), c.getStatus().name(), c.getMaturityRating().name(),
            c.getReleaseDate(), c.getFeatured(), c.getComingSoon(),
            c.getViewCount(), c.getAverageRating(), c.getReviewCount(),
            c.getPosterUrl(), c.getThumbnailUrl()
        );
    }
}
```

- [ ] **Step 3: Verify compilation and tests**

```bash
./gradlew :api-service:test 2>&1 | tail -10
```

**If tests fail:** `ContentSummaryResponse` is a record — any existing test that constructs it with `new ContentSummaryResponse(...)` using positional args will break because the record signature changed. Find all such usages:

```bash
grep -rn "new ContentSummaryResponse(" api-service/src/test/ 2>/dev/null
```

For each failing test, add the two new fields (`averageRating` and `reviewCount`) at the correct position: after `viewCount` and before `posterUrl`. Use `java.math.BigDecimal.ZERO` and `0` as defaults in test fixtures.

Similarly check `ContentResponse` usages:
```bash
grep -rn "new ContentResponse(" api-service/src/test/ 2>/dev/null
```

- [ ] **Step 4: Run full suite — all must pass**

```bash
./gradlew :api-service:test 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentResponse.java
git add api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentSummaryResponse.java
git commit -m "feat(reviews): expose averageRating and reviewCount in ContentResponse and ContentSummaryResponse"
```

---

## Self-Review

**Spec coverage:**
- ✅ `GET /contents/{contentId}/reviews` — public, paginated APPROVED reviews
- ✅ `POST /contents/{contentId}/reviews` — authenticated, 409 on duplicate, auto-APPROVED
- ✅ `PATCH /reviews/{id}` — user updates own (404 if not owned)
- ✅ `DELETE /reviews/{id}` — user deletes own (404 if not owned)
- ✅ `PATCH /admin/reviews/{id}/status` — admin changes status
- ✅ `content_reviews` table with unique(user_id, content_id), rating 1-5 constraint
- ✅ Aggregate scoring: `average_rating` + `review_count` on `contents`, auto-updated by PostgreSQL trigger after every insert/update/delete on `content_reviews`
- ✅ `ReviewStatus` enum: PENDING, APPROVED, REJECTED
- ✅ `averageRating` + `reviewCount` exposed in `ContentResponse` and `ContentSummaryResponse`
- ✅ No placeholder steps

**No placeholders found.**

**Type consistency:** `ContentSummaryResponse` adds `averageRating` and `reviewCount` at positions 12-13 (after `viewCount`). Task 6 explicitly handles all existing test fixtures that construct the record positionally.
