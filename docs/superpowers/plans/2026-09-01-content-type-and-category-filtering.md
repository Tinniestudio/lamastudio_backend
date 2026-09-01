# Content Type & Category Filtering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hardcoded 2-value `ContentType` enum on `Content` with an admin-manageable `ContentType` entity (name/slug/structuralKind), and let `ContentController.list` filter by multiple categories at once (AND semantics).

**Architecture:** New `content_types` table mirrors `categories` exactly (same Postgres slug-trigger pattern, same admin-CRUD shape), plus a fixed, non-editable `structuralKind` enum (`SINGLE_VIDEO` | `MULTI_EPISODE`) that upload/season logic can trust regardless of what an admin names a type. `Content.type` (raw enum column) becomes `Content.contentType` (`@ManyToOne` FK). Every DTO/query that touched the old `type` column is updated in the same pass, including two native SQL search queries and 13 existing test fixtures.

**Tech Stack:** Spring Boot, Spring Data JPA (Specifications + native `@Query`), Flyway, PostgreSQL, JUnit 5 + Mockito + AssertJ, Testcontainers (for the one real-DB regression test).

**Covers specs:**
- `docs/superpowers/specs/2026-09-01-dynamic-content-type-design.md`
- `docs/superpowers/specs/2026-09-01-multi-category-content-filtering-design.md`

**Note on the specs' text:** the dynamic-content-type spec says slugs are "admin-supplied, same convention as Category.slug." That was based on a wrong reading of `CategoryService` — the *real* mechanism (verified against `V16__add_categories.sql`) is a Postgres trigger (`slugify()` + a collision-retry loop) that generates the slug from `name` server-side, overriding anything the Java layer sets. This plan follows the real mechanism, not the spec text.

---

## File Structure

**Create:**
- `src/main/resources/db/migration/V53__add_content_types.sql` — table, seed rows, `contents.content_type_id` FK, backfill, drop old `type` column
- `src/main/java/com/tinniestudio/api/shared/entity/ContentType.java` — JPA entity
- `src/main/java/com/tinniestudio/api/modules/contenttype/repository/ContentTypeRepository.java`
- `src/main/java/com/tinniestudio/api/modules/contenttype/dto/ContentTypeResponse.java`
- `src/main/java/com/tinniestudio/api/modules/contenttype/dto/CreateContentTypeRequest.java`
- `src/main/java/com/tinniestudio/api/modules/contenttype/dto/UpdateContentTypeRequest.java`
- `src/main/java/com/tinniestudio/api/modules/contenttype/service/ContentTypeService.java`
- `src/main/java/com/tinniestudio/api/modules/contenttype/controller/ContentTypeController.java` — public `GET /content-types`
- `src/main/java/com/tinniestudio/api/modules/contenttype/controller/AdminContentTypeController.java` — `/admin/content-types` CRUD
- Tests: `src/test/java/com/tinniestudio/api/modules/contenttype/service/ContentTypeServiceTest.java`
- Tests: `src/test/java/com/tinniestudio/api/modules/contenttype/controller/AdminContentTypeControllerTest.java`

**Modify:**
- `src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java` — add `StructuralKind`, remove old `ContentType` enum (last step, once nothing references it)
- `src/main/java/com/tinniestudio/api/shared/entity/Content.java` — `type` field → `contentType` FK
- `src/main/java/com/tinniestudio/api/modules/content/repository/ContentSpecifications.java` — `hasType` becomes slug-join; add `hasCategories`
- `src/main/java/com/tinniestudio/api/modules/content/repository/ContentRepository.java` — 3 native search queries
- `src/main/java/com/tinniestudio/api/modules/content/service/ContentService.java` — `list()` signature, category-splitting
- `src/main/java/com/tinniestudio/api/modules/content/controller/ContentController.java` — `list()` param types
- `src/main/java/com/tinniestudio/api/modules/content/dto/CreateContentRequest.java` — `type` → `contentTypeId`
- `src/main/java/com/tinniestudio/api/modules/content/dto/ContentResponse.java` — `type` string → nested `contentType`
- `src/main/java/com/tinniestudio/api/modules/content/dto/ContentSummaryResponse.java` — same
- `src/main/java/com/tinniestudio/api/modules/partner/dto/PartnerContentResponse.java` — same, both factory methods
- `src/main/java/com/tinniestudio/api/modules/search/dto/SearchRequest.java` — `type` enum → `typeSlug` String
- `src/main/java/com/tinniestudio/api/modules/search/service/SearchServiceImpl.java` — pass `typeSlug` through
- `src/main/java/com/tinniestudio/api/modules/season/service/SeasonService.java` — structural-kind guard in `create()`
- 13 existing test files (listed in Task 10) — one-line fixture fix each
- `src/test/java/com/tinniestudio/api/modules/content/service/ContentServiceTest.java` — `list()`/`create()` tests updated for new signatures
- `src/test/java/com/tinniestudio/api/modules/content/controller/ContentControllerTest.java` — same
- `src/test/java/com/tinniestudio/api/modules/season/service/SeasonServiceTest.java` — new structural-kind-guard tests
- `src/test/java/com/tinniestudio/api/modules/search/service/SearchServiceTest.java` — `typeSlug` param

---

### Task 1: `StructuralKind` enum + `ContentType` entity

**Files:**
- Modify: `src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java`
- Create: `src/main/java/com/tinniestudio/api/shared/entity/ContentType.java`

- [ ] **Step 1: Add `StructuralKind` to `DomainEnums`**

Add this nested enum anywhere alongside the other nested enums in `DomainEnums.java` (e.g. right after the existing `ContentType` enum, which Task 9 will delete):

```java
    /**
     * Fixed, non-admin-editable — unlike ContentType itself (see the ContentType entity), this
     * is what upload/season logic actually branches on. Adding LIVE here later (once live
     * streaming is built) is a one-line addition; the content_types.structural_kind column is
     * string-backed, so no migration is needed when that happens.
     */
    public enum StructuralKind {
        SINGLE_VIDEO,
        MULTI_EPISODE
    }
```

- [ ] **Step 2: Create the `ContentType` entity**

```java
package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "content_types")
@Getter
@Setter
@NoArgsConstructor
public class ContentType extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    private String description;

    @Column(nullable = false)
    private Boolean isActive = true;

    @Column(nullable = false)
    private Integer displayOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "structural_kind", nullable = false)
    private DomainEnums.StructuralKind structuralKind;
}
```

- [ ] **Step 3: Compile check**

Run: `cd api-service && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL (nothing references either new type yet, so this only proves they parse).

- [ ] **Step 4: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java api-service/src/main/java/com/tinniestudio/api/shared/entity/ContentType.java
git commit -m "feat: add StructuralKind enum and ContentType entity"
```

---

### Task 2: Migration — `content_types` table, seed, backfill, drop old column

**Files:**
- Create: `src/main/resources/db/migration/V53__add_content_types.sql`
- Modify: `src/test/java/com/tinniestudio/api/modules/content/repository/ContentSearchRepositoryTest.java` (verification only, no code change yet — this task just proves the migration itself runs)

- [ ] **Step 1: Write the migration**

Mirrors `V16__add_categories.sql`'s table+trigger shape, plus the `contents` backfill. `content_type_id` is added as nullable first (so the backfill UPDATE has something to target), then flipped to `NOT NULL` after backfill — the standard safe order for adding a required column to a populated table.

```sql
CREATE TABLE IF NOT EXISTS content_types (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL UNIQUE,
    slug            VARCHAR(120) NOT NULL UNIQUE,
    description     TEXT,
    structural_kind VARCHAR(20) NOT NULL,
    display_order   INTEGER     NOT NULL DEFAULT 0,
    is_active       BOOLEAN     NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_content_types_is_active ON content_types(is_active);
CREATE INDEX idx_content_types_order     ON content_types(display_order);

CREATE OR REPLACE FUNCTION set_content_type_slug() RETURNS TRIGGER AS $$
DECLARE
    base_slug TEXT;
    candidate TEXT;
    counter   INTEGER := 2;
BEGIN
    base_slug := slugify(NEW.name);
    candidate := base_slug;
    WHILE EXISTS (
        SELECT 1 FROM content_types
        WHERE slug = candidate
          AND (TG_OP = 'INSERT' OR id != NEW.id)
    ) LOOP
        candidate := base_slug || '-' || counter;
        counter   := counter + 1;
    END LOOP;
    NEW.slug := candidate;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_content_type_slug
    BEFORE INSERT OR UPDATE OF name ON content_types
    FOR EACH ROW EXECUTE FUNCTION set_content_type_slug();

-- Seed the two types today's fixed structural set actually needs. The trigger above fires on
-- these inserts too, computing slug from name — no need to specify it manually.
INSERT INTO content_types (name, structural_kind, display_order) VALUES
    ('Movie', 'SINGLE_VIDEO', 0),
    ('Series', 'MULTI_EPISODE', 1);

-- Add nullable first — a NOT NULL column can't be added to a populated table without a default
-- or a two-step add-then-backfill-then-constrain, and there's no sensible single default here
-- since it must vary per row based on the existing `type` value.
ALTER TABLE contents ADD COLUMN content_type_id UUID;

UPDATE contents c
SET content_type_id = ct.id
FROM content_types ct
WHERE (c.type = 'MOVIE'  AND ct.slug = 'movie')
   OR (c.type = 'SERIES' AND ct.slug = 'series');

ALTER TABLE contents ALTER COLUMN content_type_id SET NOT NULL;
ALTER TABLE contents ADD CONSTRAINT fk_contents_content_type
    FOREIGN KEY (content_type_id) REFERENCES content_types(id);

DROP INDEX IF EXISTS idx_content_type;
CREATE INDEX idx_content_content_type_id ON contents(content_type_id);

ALTER TABLE contents DROP COLUMN type;
```

- [ ] **Step 2: Verify the migration runs cleanly against a real Postgres**

This repo already has a Testcontainers-backed integration test (`ContentSearchRepositoryTest`) that boots Flyway against a real Postgres container — the fastest way to prove V53 is syntactically and semantically correct before touching any Java code that depends on it. Don't edit the test yet (it still calls `content.setType(...)`, which won't compile once Task 3 lands) — just run Flyway's own validation:

Run: `cd api-service && ./gradlew flywayValidate -Dflyway.url=<local-test-db-url> -Dflyway.user=test -Dflyway.password=test`

If no local Postgres is available for a manual check, skip straight to Task 3 — `ContentSearchRepositoryTest` (updated in Task 10) will exercise this migration for real via Testcontainers, which is the authoritative check.

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/resources/db/migration/V53__add_content_types.sql
git commit -m "feat: add content_types table, migrate contents.type to content_type_id FK"
```

---

### Task 3: `Content` entity — `type` → `contentType`

**Files:**
- Modify: `src/main/java/com/tinniestudio/api/shared/entity/Content.java`

- [ ] **Step 1: Replace the field**

Find:
```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentType type;
```

Replace with:
```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_type_id", nullable = false)
    private ContentType contentType;
```

`ContentType` here resolves to `com.tinniestudio.api.shared.entity.ContentType` (same package as `Content`, no import needed) instead of the old `DomainEnums.ContentType` import — if `Content.java` has an explicit `import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;` (or a wildcard `DomainEnums.*` import used only for this), remove it; check whether `DomainEnums.ContentStatus`/`MaturityRating` etc. are still needed via a separate import statement and keep those.

- [ ] **Step 2: Confirm it does not compile yet (expected — downstream callers still reference `getType()`/`setType()`)**

Run: `cd api-service && ./gradlew compileJava`
Expected: FAIL — "cannot find symbol: method setType" / "getType" in `ContentService`, `ContentSpecifications`, DTOs, etc. This is expected; the remaining tasks fix each caller. Do not attempt to fix them all in this step — that's Tasks 4–8.

- [ ] **Step 3: Commit anyway (deliberate broken intermediate state, matching the plan's task order)**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/Content.java
git commit -m "refactor: Content.type -> Content.contentType (callers fixed in following tasks)"
```

---

### Task 4: `ContentSpecifications` — slug-based type filter + multi-category filter

**Files:**
- Modify: `src/main/java/com/tinniestudio/api/modules/content/repository/ContentSpecifications.java`

- [ ] **Step 1: Replace `hasType`**

Find:
```java
    public static Specification<Content> hasType(ContentType type) {
        return (root, query, cb) -> type == null ? cb.conjunction()
            : cb.equal(root.get("type"), type);
    }
```

Replace with:
```java
    public static Specification<Content> hasType(String contentTypeSlug) {
        return (root, query, cb) -> contentTypeSlug == null ? cb.conjunction()
            : cb.equal(root.join("contentType", JoinType.INNER).get("slug"), contentTypeSlug);
    }
```

- [ ] **Step 2: Add `hasCategories` (multi-slug, AND semantics) alongside the existing `hasCategory`**

Add this new method near `hasCategory`/`hasAnyCategory`:

```java
    /**
     * AND semantics: content must belong to ALL given category slugs, not just one of them.
     * One join per slug — each loop iteration gets its own alias in Hibernate's Criteria API, so
     * N slugs correctly requires N distinct category memberships (the standard tag-AND-filter
     * pattern). Contrast with hasAnyCategory, which is OR-by-id and used elsewhere.
     */
    public static Specification<Content> hasCategories(java.util.List<String> slugs) {
        return (root, query, cb) -> {
            if (slugs == null || slugs.isEmpty()) return cb.conjunction();
            if (query != null && !Long.class.equals(query.getResultType())) {
                query.distinct(true);
            }
            var predicates = slugs.stream()
                .map(slug -> cb.equal(root.join("categories", JoinType.INNER).get("slug"), slug))
                .toArray(jakarta.persistence.criteria.Predicate[]::new);
            return cb.and(predicates);
        };
    }
```

- [ ] **Step 3: Remove the now-unused `ContentType` import, add `ContentType` isn't needed at all in this file anymore**

Find and delete:
```java
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
```

- [ ] **Step 4: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/content/repository/ContentSpecifications.java
git commit -m "feat: slug-based type filter, add multi-category AND filter"
```

---

### Task 5: `ContentService.list()` — new signature, category-splitting

**Files:**
- Modify: `src/main/java/com/tinniestudio/api/modules/content/service/ContentService.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/content/service/ContentServiceTest.java`

- [ ] **Step 1: Write the failing test for category-splitting**

Add to `ContentServiceTest`'s `ListTests` nested class:

```java
        @Test
        @DisplayName("splits a comma-separated category param into an AND-matched list")
        void splitsCommaSeparatedCategoryParam() {
            Page<Content> page = new PageImpl<>(List.of(content));
            when(contentRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            contentService.list(null, "sermons,bible-study", null, null, Pageable.unpaged());

            verify(contentRepository).findAll(any(Specification.class), any(Pageable.class));
            // Behavior itself (the AND join) is proven by ContentSpecificationsTest / the
            // Testcontainers search test — this test only proves list() doesn't choke on commas
            // and still delegates through the repository as before.
        }

        @Test
        @DisplayName("passes a single category slug through unchanged (backward compatible)")
        void singleCategorySlugUnchanged() {
            Page<Content> page = new PageImpl<>(List.of(content));
            when(contentRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            Page<ContentSummaryResponse> result = contentService.list(null, "sermons", null, null, Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
        }
```

- [ ] **Step 2: Run to verify these fail to compile**

Run: `cd api-service && ./gradlew test --tests ContentServiceTest`
Expected: FAIL to compile — `ContentService.list`'s first param is still `ContentType type`, and `ContentServiceTest` line 84's existing call (`contentService.list(null, null, null, null, ...)`) still works positionally, but `type` param type needs to change everywhere it's referenced with a real value. (Since all existing callers pass `null` for type, this specific compile failure will actually be about `hasType` from Task 4 already expecting `String`, not `ContentType` — confirm the failure is exactly that.)

- [ ] **Step 3: Update `list()`'s signature and add category-splitting**

Find:
```java
    @Transactional(readOnly = true)
    public Page<ContentSummaryResponse> list(
            ContentType type, String categorySlug,
            MaturityRating maturityRating, Boolean comingSoon,
            Pageable pageable) {

        Specification<Content> spec = ContentSpecifications.isPublished()
            .and(ContentSpecifications.hasType(type))
            .and(ContentSpecifications.hasCategory(categorySlug))
            .and(ContentSpecifications.hasMaturityRating(maturityRating))
            .and(ContentSpecifications.isComingSoon(comingSoon));

        return contentRepository.findAll(spec, pageable).map(ContentSummaryResponse::from);
    }
```

Replace with:
```java
    @Transactional(readOnly = true)
    public Page<ContentSummaryResponse> list(
            String typeSlug, String category,
            MaturityRating maturityRating, Boolean comingSoon,
            Pageable pageable) {

        List<String> categorySlugs = splitCategorySlugs(category);

        Specification<Content> spec = ContentSpecifications.isPublished()
            .and(ContentSpecifications.hasType(typeSlug))
            .and(ContentSpecifications.hasCategories(categorySlugs))
            .and(ContentSpecifications.hasMaturityRating(maturityRating))
            .and(ContentSpecifications.isComingSoon(comingSoon));

        return contentRepository.findAll(spec, pageable).map(ContentSummaryResponse::from);
    }

    /**
     * "category" is a comma-separated list of slugs, AND-matched (Multi-Category Content
     * Filtering spec). A single slug with no comma behaves exactly as before — this is fully
     * backward compatible, no new query param name.
     */
    private List<String> splitCategorySlugs(String category) {
        if (category == null || category.isBlank()) return List.of();
        return java.util.Arrays.stream(category.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
```

- [ ] **Step 4: Remove the now-unused `ContentType` import from `ContentService.java`**

Find and delete:
```java
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
```

- [ ] **Step 5: Run the tests**

Run: `cd api-service && ./gradlew test --tests ContentServiceTest`
Expected: still FAIL — `content.setType(ContentType.MOVIE)` in `ContentServiceTest`'s `@BeforeEach` no longer compiles. This is Task 10's job. Leave it failing and move to Task 6; Task 10 sweeps every broken fixture (including this file) in one pass and is where this test suite goes green.

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/content/service/ContentService.java api-service/src/test/java/com/tinniestudio/api/modules/content/service/ContentServiceTest.java
git commit -m "feat: ContentService.list() takes typeSlug + comma-separated category"
```

---

### Task 6: `ContentController` — param types, `CreateContentRequest`

**Files:**
- Modify: `src/main/java/com/tinniestudio/api/modules/content/controller/ContentController.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/content/dto/CreateContentRequest.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/content/service/ContentService.java` (`create()`)
- Modify: `src/test/java/com/tinniestudio/api/modules/content/controller/ContentControllerTest.java`

- [ ] **Step 1: Update `ContentController.list()`'s `type` param**

Find:
```java
    public ResponseEntity<Page<ContentSummaryResponse>> list(
            @RequestParam(required = false) ContentType type,
            @RequestParam(required = false) String category,
```

Replace with:
```java
    public ResponseEntity<Page<ContentSummaryResponse>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
```

Remove the now-unused import:
```java
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
```

- [ ] **Step 2: Update `CreateContentRequest`**

Find:
```java
public record CreateContentRequest(
    @NotBlank String title,
    @NotNull ContentType type,
    MaturityRating maturityRating,
    String description,
    String shortDescription,
    LocalDate releaseDate,
    Boolean comingSoon,
    Integer durationSeconds,
    List<UUID> categoryIds
) {}
```

Replace with:
```java
public record CreateContentRequest(
    @NotBlank String title,
    @NotNull UUID contentTypeId,
    MaturityRating maturityRating,
    String description,
    String shortDescription,
    LocalDate releaseDate,
    Boolean comingSoon,
    Integer durationSeconds,
    List<UUID> categoryIds
) {}
```

Remove the now-unused import:
```java
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
```

- [ ] **Step 3: Update `ContentService.create()` to resolve the FK**

`ContentService` needs a `ContentTypeRepository` to look up `req.contentTypeId()`. Add the dependency and use it:

Find:
```java
    private final ContentRepository contentRepository;
    private final CategoryRepository categoryRepository;
    private final RabbitTemplate rabbitTemplate;
```

Replace with:
```java
    private final ContentRepository contentRepository;
    private final CategoryRepository categoryRepository;
    private final com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository contentTypeRepository;
    private final RabbitTemplate rabbitTemplate;
```

Find (inside `create()`):
```java
        Content content = new Content();
        content.setTitle(req.title());
        content.setType(req.type());
```

Replace with:
```java
        Content content = new Content();
        content.setTitle(req.title());
        content.setContentType(contentTypeRepository.findById(req.contentTypeId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown contentTypeId: " + req.contentTypeId())));
```

(`ResponseStatusException`/`HttpStatus` are already imported in this file.)

- [ ] **Step 4: Update the existing `ContentControllerTest` for the new `type` param type**

Find any occurrence of `ContentType.MOVIE`/`ContentType.SERIES` used as a request param value or import in `ContentControllerTest.java` and replace with the equivalent string (`"MOVIE"` → `"movie"`, matching the seeded slug from Task 2's migration) or remove the type-specific assertion if it was only there to exercise the parameter binding generically — read the file first to see the exact usage before editing, since this file wasn't read in full during planning; the fix is mechanical (enum literal → slug string) but the exact line depends on what's actually there.

- [ ] **Step 5: Run the tests**

Run: `cd api-service && ./gradlew test --tests ContentControllerTest --tests ContentServiceTest`
Expected: still failing on the `setType` fixture issue (Task 10). Confirm no *new* failures beyond that one known cause.

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/content/controller/ContentController.java api-service/src/main/java/com/tinniestudio/api/modules/content/dto/CreateContentRequest.java api-service/src/main/java/com/tinniestudio/api/modules/content/service/ContentService.java api-service/src/test/java/com/tinniestudio/api/modules/content/controller/ContentControllerTest.java
git commit -m "feat: ContentController/CreateContentRequest use contentTypeId, not enum"
```

---

### Task 7: Response DTOs — nested `contentType` instead of raw `type` string

**Files:**
- Create: `src/main/java/com/tinniestudio/api/modules/contenttype/dto/ContentTypeResponse.java` (needed by the other DTOs below — create first even though the rest of the ContentType CRUD module lands in Task 8)
- Modify: `src/main/java/com/tinniestudio/api/modules/content/dto/ContentResponse.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/content/dto/ContentSummaryResponse.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/partner/dto/PartnerContentResponse.java`

- [ ] **Step 1: Create `ContentTypeResponse`**

```java
package com.tinniestudio.api.modules.contenttype.dto;

import com.tinniestudio.api.shared.entity.ContentType;
import java.util.UUID;

public record ContentTypeResponse(
    UUID id,
    String name,
    String slug,
    String structuralKind,
    Integer displayOrder,
    Boolean isActive
) {
    public static ContentTypeResponse from(ContentType t) {
        return new ContentTypeResponse(
            t.getId(), t.getName(), t.getSlug(),
            t.getStructuralKind().name(),
            t.getDisplayOrder(), t.getIsActive()
        );
    }
}
```

- [ ] **Step 2: Update `ContentResponse`**

Find:
```java
public record ContentResponse(
    UUID id, String title, String slug, String description, String shortDescription,
    String type, String status, String maturityRating,
    LocalDate releaseDate, String language, String country,
    Boolean featured, Boolean comingSoon, Long viewCount,
    Integer durationSeconds, String posterUrl, String thumbnailUrl,
    BigDecimal averageRating, Integer reviewCount,
    List<String> categoryNames, Instant publishedAt, String rejectionReason
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
            c.getPublishedAt(), c.getRejectionReason()
        );
    }
}
```

Replace with:
```java
public record ContentResponse(
    UUID id, String title, String slug, String description, String shortDescription,
    com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse contentType,
    String status, String maturityRating,
    LocalDate releaseDate, String language, String country,
    Boolean featured, Boolean comingSoon, Long viewCount,
    Integer durationSeconds, String posterUrl, String thumbnailUrl,
    BigDecimal averageRating, Integer reviewCount,
    List<String> categoryNames, Instant publishedAt, String rejectionReason
) {
    public static ContentResponse from(Content c) {
        return new ContentResponse(
            c.getId(), c.getTitle(), c.getSlug(),
            c.getDescription(), c.getShortDescription(),
            com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse.from(c.getContentType()),
            c.getStatus().name(), c.getMaturityRating().name(),
            c.getReleaseDate(), c.getLanguage(), c.getCountry(),
            c.getFeatured(), c.getComingSoon(), c.getViewCount(),
            c.getDurationSeconds(), c.getPosterUrl(), c.getThumbnailUrl(),
            c.getAverageRating(), c.getReviewCount(),
            c.getCategories().stream().map(cat -> cat.getName()).toList(),
            c.getPublishedAt(), c.getRejectionReason()
        );
    }
}
```

- [ ] **Step 3: Update `ContentSummaryResponse`** (same transformation)

Find:
```java
public record ContentSummaryResponse(
    UUID id, String title, String slug, String shortDescription,
    String type, String status, String maturityRating,
    LocalDate releaseDate, Boolean featured, Boolean comingSoon,
    Long viewCount, BigDecimal averageRating, Integer reviewCount,
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

Replace with:
```java
public record ContentSummaryResponse(
    UUID id, String title, String slug, String shortDescription,
    com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse contentType,
    String status, String maturityRating,
    LocalDate releaseDate, Boolean featured, Boolean comingSoon,
    Long viewCount, BigDecimal averageRating, Integer reviewCount,
    String posterUrl, String thumbnailUrl
) {
    public static ContentSummaryResponse from(Content c) {
        return new ContentSummaryResponse(
            c.getId(), c.getTitle(), c.getSlug(), c.getShortDescription(),
            com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse.from(c.getContentType()),
            c.getStatus().name(), c.getMaturityRating().name(),
            c.getReleaseDate(), c.getFeatured(), c.getComingSoon(),
            c.getViewCount(), c.getAverageRating(), c.getReviewCount(),
            c.getPosterUrl(), c.getThumbnailUrl()
        );
    }
}
```

- [ ] **Step 4: Update `PartnerContentResponse`** (both factory methods)

Find:
```java
public record PartnerContentResponse(
    UUID id, String title, String slug, String description, String shortDescription,
    String type, String status, String maturityRating,
    LocalDate releaseDate, String language, String country,
    Boolean featured, Boolean comingSoon, Long viewCount,
    Integer durationSeconds, String posterUrl, String thumbnailUrl,
    BigDecimal averageRating, Integer reviewCount,
    List<String> categoryNames, Instant publishedAt, String rejectionReason
) {
    public static PartnerContentResponse from(Content c) {
        return new PartnerContentResponse(
            c.getId(), c.getTitle(), c.getSlug(),
            c.getDescription(), c.getShortDescription(),
            c.getType().name(), c.getStatus().name(), c.getMaturityRating().name(),
            c.getReleaseDate(), c.getLanguage(), c.getCountry(),
            c.getFeatured(), c.getComingSoon(), c.getViewCount(),
            c.getDurationSeconds(), c.getPosterUrl(), c.getThumbnailUrl(),
            c.getAverageRating(), c.getReviewCount(),
            c.getCategories().stream().map(cat -> cat.getName()).toList(),
            c.getPublishedAt(), c.getRejectionReason()
        );
    }

    /** For create/update, which go through ContentService and get back a ContentResponse. */
    public static PartnerContentResponse from(ContentResponse c) {
        return new PartnerContentResponse(
            c.id(), c.title(), c.slug(), c.description(), c.shortDescription(),
            c.type(), c.status(), c.maturityRating(),
            c.releaseDate(), c.language(), c.country(),
            c.featured(), c.comingSoon(), c.viewCount(),
            c.durationSeconds(), c.posterUrl(), c.thumbnailUrl(),
            c.averageRating(), c.reviewCount(),
            c.categoryNames(), c.publishedAt(), c.rejectionReason()
        );
    }
}
```

Replace with:
```java
public record PartnerContentResponse(
    UUID id, String title, String slug, String description, String shortDescription,
    com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse contentType,
    String status, String maturityRating,
    LocalDate releaseDate, String language, String country,
    Boolean featured, Boolean comingSoon, Long viewCount,
    Integer durationSeconds, String posterUrl, String thumbnailUrl,
    BigDecimal averageRating, Integer reviewCount,
    List<String> categoryNames, Instant publishedAt, String rejectionReason
) {
    public static PartnerContentResponse from(Content c) {
        return new PartnerContentResponse(
            c.getId(), c.getTitle(), c.getSlug(),
            c.getDescription(), c.getShortDescription(),
            com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse.from(c.getContentType()),
            c.getStatus().name(), c.getMaturityRating().name(),
            c.getReleaseDate(), c.getLanguage(), c.getCountry(),
            c.getFeatured(), c.getComingSoon(), c.getViewCount(),
            c.getDurationSeconds(), c.getPosterUrl(), c.getThumbnailUrl(),
            c.getAverageRating(), c.getReviewCount(),
            c.getCategories().stream().map(cat -> cat.getName()).toList(),
            c.getPublishedAt(), c.getRejectionReason()
        );
    }

    /** For create/update, which go through ContentService and get back a ContentResponse. */
    public static PartnerContentResponse from(ContentResponse c) {
        return new PartnerContentResponse(
            c.id(), c.title(), c.slug(), c.description(), c.shortDescription(),
            c.contentType(), c.status(), c.maturityRating(),
            c.releaseDate(), c.language(), c.country(),
            c.featured(), c.comingSoon(), c.viewCount(),
            c.durationSeconds(), c.posterUrl(), c.thumbnailUrl(),
            c.averageRating(), c.reviewCount(),
            c.categoryNames(), c.publishedAt(), c.rejectionReason()
        );
    }
}
```

- [ ] **Step 5: Compile check**

Run: `cd api-service && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL for `main` (test failures from `setType` fixtures are expected and handled in Task 10 — this step only checks production code).

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/contenttype/dto/ContentTypeResponse.java api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentResponse.java api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentSummaryResponse.java api-service/src/main/java/com/tinniestudio/api/modules/partner/dto/PartnerContentResponse.java
git commit -m "feat: response DTOs expose nested contentType instead of raw type string"
```

---

### Task 8: `ContentType` admin CRUD + public read endpoint

**Files:**
- Create: `src/main/java/com/tinniestudio/api/modules/contenttype/repository/ContentTypeRepository.java`
- Create: `src/main/java/com/tinniestudio/api/modules/contenttype/dto/CreateContentTypeRequest.java`
- Create: `src/main/java/com/tinniestudio/api/modules/contenttype/dto/UpdateContentTypeRequest.java`
- Create: `src/main/java/com/tinniestudio/api/modules/contenttype/service/ContentTypeService.java`
- Create: `src/main/java/com/tinniestudio/api/modules/contenttype/controller/ContentTypeController.java`
- Create: `src/main/java/com/tinniestudio/api/modules/contenttype/controller/AdminContentTypeController.java`
- Test: `src/test/java/com/tinniestudio/api/modules/contenttype/service/ContentTypeServiceTest.java`
- Test: `src/test/java/com/tinniestudio/api/modules/contenttype/controller/AdminContentTypeControllerTest.java`

- [ ] **Step 1: Write the failing service test**

```java
package com.tinniestudio.api.modules.contenttype.service;

import com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse;
import com.tinniestudio.api.modules.contenttype.dto.CreateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.dto.UpdateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.shared.entity.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.StructuralKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContentTypeService")
class ContentTypeServiceTest {

    @Mock private ContentTypeRepository contentTypeRepository;
    @InjectMocks private ContentTypeService contentTypeService;

    private ContentType movie;
    private UUID movieId;

    @BeforeEach
    void setUp() {
        movieId = UUID.randomUUID();
        movie = new ContentType();
        movie.setId(movieId);
        movie.setName("Movie");
        movie.setSlug("movie");
        movie.setStructuralKind(StructuralKind.SINGLE_VIDEO);
        movie.setIsActive(true);
        movie.setDisplayOrder(0);
    }

    @Nested
    @DisplayName("listActive()")
    class ListActiveTests {
        @Test
        @DisplayName("returns only active types, ordered by displayOrder")
        void returnsActiveTypes() {
            when(contentTypeRepository.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(movie));

            List<ContentTypeResponse> result = contentTypeService.listActive();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).slug()).isEqualTo("movie");
        }
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {
        @Test
        @DisplayName("saves a new content type with the given structuralKind")
        void savesNewType() {
            CreateContentTypeRequest req = new CreateContentTypeRequest("Documentary", "A documentary film", StructuralKind.SINGLE_VIDEO, 2);
            when(contentTypeRepository.saveAndFlush(any(ContentType.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentTypeResponse result = contentTypeService.create(req);

            assertThat(result.name()).isEqualTo("Documentary");
            assertThat(result.structuralKind()).isEqualTo("SINGLE_VIDEO");
        }

        @Test
        @DisplayName("throws 409 when name already exists")
        void throwsConflictOnDuplicateName() {
            CreateContentTypeRequest req = new CreateContentTypeRequest("Movie", null, StructuralKind.SINGLE_VIDEO, 0);
            when(contentTypeRepository.saveAndFlush(any(ContentType.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

            assertThatThrownBy(() -> contentTypeService.create(req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
        }
    }

    @Nested
    @DisplayName("update()")
    class UpdateTests {
        @Test
        @DisplayName("updates only non-null fields, structuralKind stays fixed unless explicitly given")
        void updatesNonNullFields() {
            UpdateContentTypeRequest req = new UpdateContentTypeRequest(null, "Updated description", null, null, false);
            when(contentTypeRepository.findById(movieId)).thenReturn(Optional.of(movie));
            when(contentTypeRepository.save(any(ContentType.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentTypeResponse result = contentTypeService.update(movieId, req);

            assertThat(result.name()).isEqualTo("Movie"); // unchanged
            assertThat(result.isActive()).isFalse();
        }

        @Test
        @DisplayName("throws 404 when id not found")
        void throws404WhenNotFound() {
            UUID missingId = UUID.randomUUID();
            UpdateContentTypeRequest req = new UpdateContentTypeRequest("X", null, null, null, null);
            when(contentTypeRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> contentTypeService.update(missingId, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {
        @Test
        @DisplayName("throws 409 when referenced by existing content")
        void throwsConflictWhenReferenced() {
            when(contentTypeRepository.findById(movieId)).thenReturn(Optional.of(movie));
            doThrow(new DataIntegrityViolationException("fk violation"))
                .when(contentTypeRepository).flush();

            assertThatThrownBy(() -> contentTypeService.delete(movieId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd api-service && ./gradlew test --tests ContentTypeServiceTest`
Expected: FAIL to compile — none of `ContentTypeRepository`/`ContentTypeService`/DTOs exist yet.

- [ ] **Step 3: Create `ContentTypeRepository`**

```java
package com.tinniestudio.api.modules.contenttype.repository;

import com.tinniestudio.api.shared.entity.ContentType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentTypeRepository extends JpaRepository<ContentType, UUID> {
    List<ContentType> findByIsActiveTrueOrderByDisplayOrderAsc();
    Optional<ContentType> findBySlug(String slug);
}
```

- [ ] **Step 4: Create the request DTOs**

```java
package com.tinniestudio.api.modules.contenttype.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.StructuralKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateContentTypeRequest(
    @NotBlank @Size(max = 100) String name,
    String description,
    @NotNull StructuralKind structuralKind,
    Integer displayOrder
) {}
```

```java
package com.tinniestudio.api.modules.contenttype.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.StructuralKind;
import jakarta.validation.constraints.Size;

public record UpdateContentTypeRequest(
    @Size(max = 100) String name,
    String description,
    StructuralKind structuralKind,
    Integer displayOrder,
    Boolean isActive
) {}
```

- [ ] **Step 5: Create `ContentTypeService`**

```java
package com.tinniestudio.api.modules.contenttype.service;

import com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse;
import com.tinniestudio.api.modules.contenttype.dto.CreateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.dto.UpdateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.shared.entity.ContentType;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentTypeService {

    private final ContentTypeRepository contentTypeRepository;

    @Cacheable("content-types")
    @Transactional(readOnly = true)
    public List<ContentTypeResponse> listActive() {
        return contentTypeRepository.findByIsActiveTrueOrderByDisplayOrderAsc()
                .stream().map(ContentTypeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ContentTypeResponse> listAll() {
        return contentTypeRepository.findAll().stream().map(ContentTypeResponse::from).toList();
    }

    @Transactional
    @CacheEvict(value = "content-types", allEntries = true)
    public ContentTypeResponse create(CreateContentTypeRequest req) {
        ContentType type = new ContentType();
        type.setName(req.name());
        type.setDescription(req.description());
        type.setStructuralKind(req.structuralKind());
        type.setDisplayOrder(req.displayOrder() != null ? req.displayOrder() : 0);
        try {
            // saveAndFlush: see ContentService.create for why plain save() doesn't reliably
            // surface the constraint violation inside this try/catch.
            return ContentTypeResponse.from(contentTypeRepository.saveAndFlush(type));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Content type name already exists: " + req.name());
        }
    }

    @Transactional
    @CacheEvict(value = "content-types", allEntries = true)
    public ContentTypeResponse update(UUID id, UpdateContentTypeRequest req) {
        ContentType type = contentTypeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content type not found: " + id));
        if (req.name() != null)           type.setName(req.name());
        if (req.description() != null)    type.setDescription(req.description());
        if (req.structuralKind() != null) type.setStructuralKind(req.structuralKind());
        if (req.displayOrder() != null)   type.setDisplayOrder(req.displayOrder());
        if (req.isActive() != null)       type.setIsActive(req.isActive());
        return ContentTypeResponse.from(contentTypeRepository.save(type));
    }

    @Transactional
    @CacheEvict(value = "content-types", allEntries = true)
    public void delete(UUID id) {
        ContentType type = contentTypeRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content type not found: " + id));
        try {
            contentTypeRepository.delete(type);
            contentTypeRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Content type is referenced by existing content and cannot be deleted");
        }
    }
}
```

- [ ] **Step 6: Run the service test**

Run: `cd api-service && ./gradlew test --tests ContentTypeServiceTest`
Expected: PASS

- [ ] **Step 7: Create the controllers**

```java
package com.tinniestudio.api.modules.contenttype.controller;

import com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse;
import com.tinniestudio.api.modules.contenttype.service.ContentTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Content Types", description = "Browse available content types")
@RestController
@RequestMapping("/content-types")
@RequiredArgsConstructor
public class ContentTypeController {

    private final ContentTypeService contentTypeService;

    @Operation(summary = "List all active content types")
    @GetMapping
    public ResponseEntity<List<ContentTypeResponse>> listActive() {
        return ResponseEntity.ok(contentTypeService.listActive());
    }
}
```

```java
package com.tinniestudio.api.modules.contenttype.controller;

import com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse;
import com.tinniestudio.api.modules.contenttype.dto.CreateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.dto.UpdateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.service.ContentTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Admin - Content Types", description = "Manage content types")
@RestController
@RequestMapping("/admin/content-types")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminContentTypeController {

    private final ContentTypeService contentTypeService;

    @Operation(summary = "List all content types including inactive")
    @GetMapping
    public ResponseEntity<List<ContentTypeResponse>> listAll() {
        return ResponseEntity.ok(contentTypeService.listAll());
    }

    @Operation(summary = "Create content type")
    @PostMapping
    public ResponseEntity<ContentTypeResponse> create(@RequestBody @Valid CreateContentTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contentTypeService.create(request));
    }

    @Operation(summary = "Update content type")
    @PatchMapping("/{id}")
    public ResponseEntity<ContentTypeResponse> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateContentTypeRequest request) {
        return ResponseEntity.ok(contentTypeService.update(id, request));
    }

    @Operation(summary = "Delete content type")
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable UUID id) {
        contentTypeService.delete(id);
        return ResponseEntity.ok(Map.of("message", "Content type deleted successfully"));
    }
}
```

- [ ] **Step 8: Write and run the admin controller test**

Mirror the existing `AdminCategoryController`'s test file structure if one exists (check `src/test/java/.../category/controller/` first — if none exists there either, use a standard `@WebMvcTest`-style controller test consistent with `ContentControllerTest`'s pattern for request/response shape assertions). At minimum, cover: `POST` with a valid body returns 201, `POST` with a duplicate name returns 409, `PATCH` on a missing id returns 404, `DELETE` returns 200.

Run: `cd api-service && ./gradlew test --tests AdminContentTypeControllerTest`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/contenttype/ api-service/src/test/java/com/tinniestudio/api/modules/contenttype/
git commit -m "feat: ContentType admin CRUD + public read endpoint"
```

---

### Task 9: `SearchRequest`/`SearchServiceImpl`/native search queries

**Files:**
- Modify: `src/main/java/com/tinniestudio/api/modules/search/dto/SearchRequest.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/search/service/SearchServiceImpl.java`
- Modify: `src/main/java/com/tinniestudio/api/modules/content/repository/ContentRepository.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/search/service/SearchServiceTest.java`

- [ ] **Step 1: Update `SearchRequest`**

Find:
```java
    private ContentType type;           // null = all types
```

Replace with:
```java
    private String type;                // content-type slug, null = all types
```

Remove the now-unused import:
```java
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
```

- [ ] **Step 2: Update `SearchServiceImpl`**

Find:
```java
        String typeStr      = request.getType() != null ? request.getType().name() : null;
```

Replace with:
```java
        String typeStr      = request.getType();
```

- [ ] **Step 3: Fix the three native search queries in `ContentRepository`**

Each of `searchByRelevance`/`searchByLatest`/`searchByPopular` (both the `value` and `countQuery`) currently selects `c.type` and filters `(:type IS NULL OR c.type = :type)` against the now-removed column. Replace `c.type,` in each SELECT list with `c.content_type_id,` (this is how a native query populates a `@ManyToOne` association when mapping to an entity result — Hibernate resolves it as the FK id and lazy-loads `Content.contentType` on access), join `content_types`, and filter on its slug.

For `searchByRelevance`, find:
```java
    @Query(
        value = "SELECT c.id, c.title, c.slug, c.description, c.short_description," +
                " c.type, c.status, c.maturity_rating, c.release_date, c.language, c.country," +
                " c.featured, c.poster_url, c.thumbnail_url, c.created_by, c.published_at," +
                " c.view_count, c.coming_soon, c.duration_seconds, c.created_at, c.updated_at," +
                " c.average_rating, c.review_count, c.deleted_at" +
                " FROM contents c" +
                " WHERE c.status = 'PUBLISHED'" +
                " AND c.search_vector @@ plainto_tsquery('english', :q)" +
                " AND (:type IS NULL OR c.type = :type)" +
                " AND (:language IS NULL OR c.language = :language)" +
                " AND (:country IS NULL OR c.country = :country)" +
                " AND (:categorySlug IS NULL OR EXISTS (" +
                "   SELECT 1 FROM content_categories cc" +
                "   JOIN categories cat ON cat.id = cc.category_id" +
                "   WHERE cc.content_id = c.id AND cat.slug = :categorySlug" +
                " ))" +
                " ORDER BY ts_rank(c.search_vector, plainto_tsquery('english', :q)) DESC",
        countQuery =
                "SELECT count(*) FROM contents c" +
                " WHERE c.status = 'PUBLISHED'" +
                " AND c.search_vector @@ plainto_tsquery('english', :q)" +
                " AND (:type IS NULL OR c.type = :type)" +
                " AND (:language IS NULL OR c.language = :language)" +
                " AND (:country IS NULL OR c.country = :country)" +
                " AND (:categorySlug IS NULL OR EXISTS (" +
                "   SELECT 1 FROM content_categories cc" +
                "   JOIN categories cat ON cat.id = cc.category_id" +
                "   WHERE cc.content_id = c.id AND cat.slug = :categorySlug" +
                " ))",
        nativeQuery = true
    )
    Page<Content> searchByRelevance(
```

Replace with:
```java
    @Query(
        value = "SELECT c.id, c.title, c.slug, c.description, c.short_description," +
                " c.content_type_id, c.status, c.maturity_rating, c.release_date, c.language, c.country," +
                " c.featured, c.poster_url, c.thumbnail_url, c.created_by, c.published_at," +
                " c.view_count, c.coming_soon, c.duration_seconds, c.created_at, c.updated_at," +
                " c.average_rating, c.review_count, c.deleted_at" +
                " FROM contents c" +
                " JOIN content_types ct ON ct.id = c.content_type_id" +
                " WHERE c.status = 'PUBLISHED'" +
                " AND c.search_vector @@ plainto_tsquery('english', :q)" +
                " AND (:type IS NULL OR ct.slug = :type)" +
                " AND (:language IS NULL OR c.language = :language)" +
                " AND (:country IS NULL OR c.country = :country)" +
                " AND (:categorySlug IS NULL OR EXISTS (" +
                "   SELECT 1 FROM content_categories cc" +
                "   JOIN categories cat ON cat.id = cc.category_id" +
                "   WHERE cc.content_id = c.id AND cat.slug = :categorySlug" +
                " ))" +
                " ORDER BY ts_rank(c.search_vector, plainto_tsquery('english', :q)) DESC",
        countQuery =
                "SELECT count(*) FROM contents c" +
                " JOIN content_types ct ON ct.id = c.content_type_id" +
                " WHERE c.status = 'PUBLISHED'" +
                " AND c.search_vector @@ plainto_tsquery('english', :q)" +
                " AND (:type IS NULL OR ct.slug = :type)" +
                " AND (:language IS NULL OR c.language = :language)" +
                " AND (:country IS NULL OR c.country = :country)" +
                " AND (:categorySlug IS NULL OR EXISTS (" +
                "   SELECT 1 FROM content_categories cc" +
                "   JOIN categories cat ON cat.id = cc.category_id" +
                "   WHERE cc.content_id = c.id AND cat.slug = :categorySlug" +
                " ))",
        nativeQuery = true
    )
    Page<Content> searchByRelevance(
```

Apply the exact same three edits (SELECT column, JOIN, WHERE clause — in both `value` and `countQuery`) to `searchByLatest` and `searchByPopular`. The `ORDER BY` clauses (`ts_rank(...)`, `c.published_at DESC NULLS LAST`, `c.view_count DESC`) and method signatures are unchanged in all three.

- [ ] **Step 4: Update `SearchServiceTest`**

The two lines flagged by the earlier grep (`c.setType(ContentType.MOVIE)` at line 45, `req.setType(ContentType.MOVIE)` at line 151) need the same treatment as Task 10's sweep — fix them here since this file is already open for the `typeStr` signature change; don't leave them for Task 10 to avoid re-touching this file twice. Replace:
```java
        c.setType(ContentType.MOVIE);
```
with (using the same inline `ContentType` entity construction pattern established in Task 10):
```java
        com.tinniestudio.api.shared.entity.ContentType movieType = new com.tinniestudio.api.shared.entity.ContentType();
        movieType.setSlug("movie");
        movieType.setStructuralKind(com.tinniestudio.api.shared.entity.DomainEnums.StructuralKind.SINGLE_VIDEO);
        c.setContentType(movieType);
```
And:
```java
            req.setType(ContentType.MOVIE);
```
with:
```java
            req.setType("movie");
```
Remove the now-unused `ContentType` import from this file if nothing else in it references `DomainEnums.ContentType`.

- [ ] **Step 5: Run the tests**

Run: `cd api-service && ./gradlew test --tests SearchServiceTest`
Expected: PASS

Run: `cd api-service && ./gradlew test --tests ContentSearchRepositoryTest`
Expected: still FAIL on `content.setType(ContentType.MOVIE)` at this test's own line 61 — that's Task 10's fix, not this task's. Confirm the failure is exactly that (a compile error on `setType`), not a query error — that's the signal the native SQL rewrite itself is correct.

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/search/dto/SearchRequest.java api-service/src/main/java/com/tinniestudio/api/modules/search/service/SearchServiceImpl.java api-service/src/main/java/com/tinniestudio/api/modules/content/repository/ContentRepository.java api-service/src/test/java/com/tinniestudio/api/modules/search/service/SearchServiceTest.java
git commit -m "feat: search type filter uses content-type slug, fix native queries for FK column"
```

---

### Task 10: Fix the remaining 12 broken test fixtures + delete the old enum

**Files:**
- Modify: `src/test/java/com/tinniestudio/api/modules/content/service/ContentServiceTest.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/content/controller/AdminContentOwnershipControllerTest.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/content/repository/ContentSearchRepositoryTest.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/discover/service/DiscoverServiceTest.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/episode/service/EpisodeServiceTest.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/library/service/FavoriteServiceTest.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/library/service/WatchHistoryServiceTest.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/partner/service/PartnerServiceTest.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/analytics/consumer/AnalyticsAtomicityIntegrationTest.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/season/service/SeasonServiceTest.java`
- Modify: `src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java` (delete the old enum, final step)

Every file below constructs a `Content` fixture purely as setup for unrelated behavior (favorites, watch history, discover, episodes, partner ownership, analytics) — none of them are testing type-filtering itself, so the fix is mechanical: replace the `.setType(ContentType.X)` line with a locally-constructed `ContentType` entity instance and `.setContentType(...)`, matching the inline-construction pattern (no shared test utility exists in this codebase to introduce one for — see `SeasonServiceTest.principalFor()` for the established convention of local-to-the-test-class helpers instead of a shared utility).

- [ ] **Step 1: Fix each `MOVIE`-typed fixture**

In each of `ContentServiceTest.java` (line 64), `AdminContentOwnershipControllerTest.java` (line 50), `ContentSearchRepositoryTest.java` (line 61), `DiscoverServiceTest.java` (line 46), `FavoriteServiceTest.java` (line 62), `WatchHistoryServiceTest.java` (line 63), `PartnerServiceTest.java` (line 204), `AnalyticsAtomicityIntegrationTest.java` (line 86):

Find:
```java
        content.setType(ContentType.MOVIE);
```
(the exact receiver variable name varies — `content`, `c`, etc.; use whatever the surrounding fixture already uses)

Replace with, immediately before that line:
```java
        com.tinniestudio.api.shared.entity.ContentType movieType = new com.tinniestudio.api.shared.entity.ContentType();
        movieType.setSlug("movie");
        movieType.setStructuralKind(com.tinniestudio.api.shared.entity.DomainEnums.StructuralKind.SINGLE_VIDEO);
```
and change the original line to:
```java
        content.setContentType(movieType);
```
(substituting the correct receiver variable name per file).

- [ ] **Step 2: Fix the two `SERIES`-typed fixtures**

In `EpisodeServiceTest.java` (line 64) and `SeasonServiceTest.java` (line 58):

Find:
```java
        content.setType(ContentType.SERIES);
```
(again, actual receiver variable name may differ — `seriesContent` in `SeasonServiceTest.java`)

Replace with, immediately before:
```java
        com.tinniestudio.api.shared.entity.ContentType seriesType = new com.tinniestudio.api.shared.entity.ContentType();
        seriesType.setSlug("series");
        seriesType.setStructuralKind(com.tinniestudio.api.shared.entity.DomainEnums.StructuralKind.MULTI_EPISODE);
```
and:
```java
        seriesContent.setContentType(seriesType);
```

- [ ] **Step 3: Remove now-unused `ContentType`/`DomainEnums.ContentType` imports**

In each of the 10 files above, if `import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;` (or equivalent) is no longer referenced anywhere else in that file, delete it.

- [ ] **Step 4: Run the full test suite**

Run: `cd api-service && ./gradlew test`
Expected: BUILD SUCCESSFUL — every test across the whole module compiles and passes, including `ContentSearchRepositoryTest`'s real Testcontainers run of migration V53 and the rewritten native search queries.

If `ContentSearchRepositoryTest` fails at this point, that's the first real signal something in Task 2's migration or Task 9's native query rewrite is wrong — debug against the actual Postgres container output before proceeding; don't paper over it by skipping the test.

- [ ] **Step 5: Delete the old `ContentType` enum from `DomainEnums`**

Now that nothing in `main` or `test` references `DomainEnums.ContentType`, remove the old enum entirely (search once more to confirm zero references first):

Run: `grep -rn "DomainEnums.ContentType\|DomainEnums\.\*.*ContentType" api-service/src`
Expected: no output.

Then delete the enum block:
```java
    public enum ContentType {
        MOVIE,
        SERIES
    }
```
from `DomainEnums.java`.

- [ ] **Step 6: Final full build**

Run: `cd api-service && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add api-service/src/test api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java
git commit -m "fix: repair 12 test fixtures broken by contentType migration, delete old enum"
```

---

### Task 11: Season structural-kind integrity check

**Files:**
- Modify: `src/main/java/com/tinniestudio/api/modules/season/service/SeasonService.java`
- Modify: `src/test/java/com/tinniestudio/api/modules/season/service/SeasonServiceTest.java`

- [ ] **Step 1: Write the failing test**

Add to `SeasonServiceTest`'s tests for `create()` (find the existing `@Nested` class covering `create()`, or add a new one if creation tests aren't grouped — follow whatever grouping convention the file already uses):

```java
        @Test
        @DisplayName("throws 409 when content's structuralKind is not MULTI_EPISODE")
        void throwsWhenContentIsNotMultiEpisode() {
            com.tinniestudio.api.shared.entity.ContentType movieType = new com.tinniestudio.api.shared.entity.ContentType();
            movieType.setSlug("movie");
            movieType.setStructuralKind(com.tinniestudio.api.shared.entity.DomainEnums.StructuralKind.SINGLE_VIDEO);
            seriesContent.setContentType(movieType); // reusing the fixture but overriding its type for this one test

            CreateSeasonRequest req = new CreateSeasonRequest(1, "Season 1", null, null, null, null);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));

            assertThatThrownBy(() -> seasonService.create(contentId, req, ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));

            verify(seasonRepository, never()).saveAndFlush(any());
        }
```

(Check `CreateSeasonRequest`'s actual constructor arg order/count against `src/main/java/com/tinniestudio/api/modules/season/dto/CreateSeasonRequest.java` before finalizing this test — the plan's exploration didn't read that file's exact fields, so confirm the record signature matches at edit time.)

- [ ] **Step 2: Run to verify it fails**

Run: `cd api-service && ./gradlew test --tests SeasonServiceTest`
Expected: FAIL — no such check exists yet, so this either doesn't throw or throws the wrong status.

- [ ] **Step 3: Add the check to `SeasonService.create()`**

Find (in `create()`, right after the existing ownership check):
```java
        Content content = contentRepository.findById(contentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + contentId));
        assertOwnedByCallerOrAdmin(content, principal);

        int seasonNumber;
```

Replace with:
```java
        Content content = contentRepository.findById(contentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + contentId));
        assertOwnedByCallerOrAdmin(content, principal);
        if (content.getContentType().getStructuralKind() != com.tinniestudio.api.shared.entity.DomainEnums.StructuralKind.MULTI_EPISODE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot add seasons to content whose type is not multi-episode: " + content.getContentType().getName());
        }

        int seasonNumber;
```

- [ ] **Step 4: Run the tests**

Run: `cd api-service && ./gradlew test --tests SeasonServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/season/service/SeasonService.java api-service/src/test/java/com/tinniestudio/api/modules/season/service/SeasonServiceTest.java
git commit -m "feat: reject creating a Season under non-MULTI_EPISODE content"
```

---

## Self-Review Notes

- **Spec coverage:** Dynamic Content Type spec — data model ✓ (Task 2), admin CRUD ✓ (Task 8), public read ✓ (Task 8), API surface updates ✓ (Tasks 5–7, 9), season integrity check ✓ (Task 11). Multi-Category spec — `hasCategories` ✓ (Task 4), comma-split param ✓ (Task 5), backward-compatible single-slug ✓ (same task). Both specs' "no LIVE yet" / "no partner-web changes" non-goals are respected — nothing here touches partner-web or adds a third structural kind.
- **Corrected from the spec text:** slug generation is a DB trigger, not app-level `generateSlug()` — see the note under Goal above.
- **Full blast radius verified:** every file in the codebase referencing `ContentType` or calling `.setType(` was enumerated via `grep -rl`/`grep -rn` before writing tasks, not assumed from the original spec's file list.
