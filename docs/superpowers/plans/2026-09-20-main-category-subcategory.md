# Main Category / Subcategory (Server) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new, fixed `MainCategory` enum (`MOVIES | TV_SHOWS | KIDS | SERMONS`) as a required field on `Content`, fully independent of the existing `Category` (subcategory/genre) table and `ContentType.structuralKind`, wired through listing, search, creation, and update — the backend half of the cross-repo main-category/subcategory redesign. Client-web/partner-web/admin-web plans depend on this one shipping first.

**Architecture:** One new Java enum in `DomainEnums` (same pattern as `StructuralKind` — fixed, no DB lookup table, no admin CRUD), one new `NOT NULL` column on `contents`, one new `ContentSpecifications` predicate, threaded through `ContentService`/`SearchServiceImpl`'s existing filter-composition pattern exactly like `type`/`category` are today. External representation is a slug string (`tv-shows`, not `TV_SHOWS`) at every API boundary (query params, request/response JSON) — parsed to/from the enum inside the service layer via `MainCategory.fromSlug()`/`.getSlug()`, never relying on Jackson's default enum (de)serialization, since the external slug differs from the Java constant name for `TV_SHOWS`.

**Tech Stack:** Spring Boot, JUnit 5 + Mockito + AssertJ, Testcontainers (Postgres), Flyway.

**Covers spec:** `docs/superpowers/specs/2026-09-20-main-category-subcategory-design.md`

**Depends on:** none (this is the first plan in the cross-repo sequence; client-web/partner-web/admin-web plans depend on this one).

**Key design decisions carried over from the spec (don't re-litigate):**
- Exactly 4 values, no "Lives" (excluded — no live-streaming backend exists).
- `mainCategory` and `structuralKind` are fully independent — no validation ties them together.
- `mainCategory` is required at creation, optional (partial-update semantics) on update.
- The existing `Category` table (subcategory/genre tags) is structurally unchanged; only its "Kids" and "Sermons" rows are retired (`isActive = false`).
- `mainCategory` query param is optional everywhere it's added — omitting it returns all main categories mixed (needed for homepage/discover sections).

---

## File Structure

**Modify:**
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java` — add `MainCategory` enum
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/Content.java` — add `mainCategory` field
- `api-service/src/main/java/com/tinniestudio/api/modules/content/repository/ContentSpecifications.java` — add `hasMainCategory`
- `api-service/src/main/java/com/tinniestudio/api/modules/content/service/ContentService.java` — `list()`/`create()`/`update()` wiring
- `api-service/src/main/java/com/tinniestudio/api/modules/content/controller/ContentController.java` — new query param
- `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/CreateContentRequest.java` — required field
- `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/UpdateContentRequest.java` — optional field
- `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentResponse.java` — response field
- `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentSummaryResponse.java` — response field
- `api-service/src/main/java/com/tinniestudio/api/modules/partner/dto/PartnerContentResponse.java` — response field (both factory methods)
- `api-service/src/main/java/com/tinniestudio/api/modules/search/dto/SearchRequest.java` — new filter field
- `api-service/src/main/java/com/tinniestudio/api/modules/content/repository/ContentRepository.java` — 3 native search queries gain the column + filter
- `api-service/src/main/java/com/tinniestudio/api/modules/search/service/SearchServiceImpl.java` — wiring
- `api-service/src/test/java/com/tinniestudio/api/modules/content/service/ContentServiceTest.java` — updated call sites + new tests
- Up to 17 other test files constructing `Content` fixtures (Task 7 — found via grep, fixed only where they actually break)

**Create:**
- `api-service/src/main/resources/db/migration/V55__add_main_category.sql`

---

### Task 1: `MainCategory` enum + `Content` entity field

**Files:** `DomainEnums.java`, `Content.java`

- [ ] **Step 1: Add the enum to `DomainEnums.java`**

Find:
```java
    public enum StructuralKind {
        SINGLE_VIDEO,
        MULTI_EPISODE
    }
```

Replace with:
```java
    public enum StructuralKind {
        SINGLE_VIDEO,
        MULTI_EPISODE
    }

    /**
     * Independent of ContentType/structuralKind by design — a MULTI_EPISODE title is typically
     * TV_SHOWS, but nothing enforces it (e.g. a SINGLE_VIDEO kids film is KIDS, not MOVIES).
     * Fixed at exactly these 4 values; "Lives" is deliberately excluded until live-streaming
     * infrastructure exists (see StructuralKind's LIVE comment above). External representation
     * (API JSON, query params) is the slug below, not the Java constant name — TV_SHOWS
     * serializes/deserializes as "tv-shows" everywhere outside this enum.
     */
    public enum MainCategory {
        MOVIES("movies"),
        TV_SHOWS("tv-shows"),
        KIDS("kids"),
        SERMONS("sermons");

        private final String slug;

        MainCategory(String slug) {
            this.slug = slug;
        }

        public String getSlug() {
            return slug;
        }

        /** @throws IllegalArgumentException if slug doesn't match any value — callers translate this to a 400. */
        public static MainCategory fromSlug(String slug) {
            for (MainCategory mc : values()) {
                if (mc.slug.equals(slug)) return mc;
            }
            throw new IllegalArgumentException("Unknown mainCategory: " + slug);
        }
    }
```

- [ ] **Step 2: Add the field to `Content.java`**

Find:
```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_type_id", nullable = false)
    private ContentType contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentStatus status;
```

Replace with:
```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_type_id", nullable = false)
    private ContentType contentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "main_category", nullable = false)
    private DomainEnums.MainCategory mainCategory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentStatus status;
```

- [ ] **Step 3: Compile to confirm no breakage yet**

Run: `./gradlew :api-service:compileJava`
Expected: FAILS — every place constructing a `Content` via the builder-less no-args constructor still compiles fine (Lombok setters, no constructor changed), so this alone should actually succeed. If it fails, the error will point at any code assuming `Content`'s field count/order changed (there shouldn't be any, since this uses `@Getter`/`@Setter`, not `@AllArgsConstructor`). Confirm `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java api-service/src/main/java/com/tinniestudio/api/shared/entity/Content.java
git commit -m "feat: add MainCategory enum and Content.mainCategory field"
```

---

### Task 2: `ContentSpecifications.hasMainCategory` + `list()`/`ContentController` wiring

**Files:** `ContentSpecifications.java`, `ContentService.java`, `ContentController.java`, `ContentServiceTest.java`

- [ ] **Step 1: Write the failing tests in `ContentServiceTest.java`**

Add to the `ListTests` nested class (after `splitsCommaSeparatedCategoryParam`):

```java
        @Test
        @DisplayName("parses a valid mainCategory slug and filters by it")
        void filtersByMainCategory() {
            Page<Content> page = new PageImpl<>(List.of(content));
            when(contentRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            contentService.list(null, null, null, null, "tv-shows", Pageable.unpaged());

            verify(contentRepository).findAll(any(Specification.class), any(Pageable.class));
        }

        @Test
        @DisplayName("rejects an unknown mainCategory slug with 400")
        void rejectsUnknownMainCategory() {
            assertThatThrownBy(() ->
                contentService.list(null, null, null, null, "not-a-real-category", Pageable.unpaged()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown mainCategory");
        }

        @Test
        @DisplayName("omitting mainCategory returns content across all main categories")
        void nullMainCategoryMeansNoFilter() {
            Page<Content> page = new PageImpl<>(List.of(content));
            when(contentRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            contentService.list(null, null, null, null, null, Pageable.unpaged());

            verify(contentRepository).findAll(any(Specification.class), any(Pageable.class));
        }
```

Update the 4 existing call sites in the same file (they currently pass 5 args; `list()` is about to take 6):

Find each of these 4 lines:
```java
            Page<ContentSummaryResponse> result = contentService.list(null, null, null, null, Pageable.unpaged());
```
```java
            contentService.list(null, "sermons,bible-study", null, null, Pageable.unpaged());
```
```java
            Page<ContentSummaryResponse> result = contentService.list(null, "sermons", null, null, Pageable.unpaged());
```
```java
            assertThatThrownBy(() -> contentService.list(null, tooMany, null, null, Pageable.unpaged()))
```

Replace each with (inserting `null` for the new `mainCategory` param, 5th position, before `Pageable`):
```java
            Page<ContentSummaryResponse> result = contentService.list(null, null, null, null, null, Pageable.unpaged());
```
```java
            contentService.list(null, "sermons,bible-study", null, null, null, Pageable.unpaged());
```
```java
            Page<ContentSummaryResponse> result = contentService.list(null, "sermons", null, null, null, Pageable.unpaged());
```
```java
            assertThatThrownBy(() -> contentService.list(null, tooMany, null, null, null, Pageable.unpaged()))
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./gradlew :api-service:test --tests ContentServiceTest`
Expected: FAIL to compile — `list()` doesn't take 6 args yet.

- [ ] **Step 3: Add the specification**

In `ContentSpecifications.java`, find:
```java
    public static Specification<Content> hasMaturityRating(MaturityRating rating) {
        return (root, query, cb) -> rating == null ? cb.conjunction()
            : cb.equal(root.get("maturityRating"), rating);
    }
```

Replace with:
```java
    public static Specification<Content> hasMaturityRating(MaturityRating rating) {
        return (root, query, cb) -> rating == null ? cb.conjunction()
            : cb.equal(root.get("maturityRating"), rating);
    }

    public static Specification<Content> hasMainCategory(com.tinniestudio.api.shared.entity.DomainEnums.MainCategory mainCategory) {
        return (root, query, cb) -> mainCategory == null ? cb.conjunction()
            : cb.equal(root.get("mainCategory"), mainCategory);
    }
```

- [ ] **Step 4: Wire it into `ContentService.list()`**

Find:
```java
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
```

Replace with:
```java
    public Page<ContentSummaryResponse> list(
            String typeSlug, String category,
            MaturityRating maturityRating, Boolean comingSoon,
            String mainCategorySlug,
            Pageable pageable) {

        List<String> categorySlugs = splitCategorySlugs(category);
        DomainEnums.MainCategory mainCategory = parseMainCategoryOrThrow(mainCategorySlug);

        Specification<Content> spec = ContentSpecifications.isPublished()
            .and(ContentSpecifications.hasType(typeSlug))
            .and(ContentSpecifications.hasCategories(categorySlugs))
            .and(ContentSpecifications.hasMaturityRating(maturityRating))
            .and(ContentSpecifications.isComingSoon(comingSoon))
            .and(ContentSpecifications.hasMainCategory(mainCategory));

        return contentRepository.findAll(spec, pageable).map(ContentSummaryResponse::from);
    }

    /** Optional filter — null/blank slug means "all main categories"; an unrecognized non-null slug is a 400. */
    private DomainEnums.MainCategory parseMainCategoryOrThrow(String mainCategorySlug) {
        if (mainCategorySlug == null || mainCategorySlug.isBlank()) return null;
        try {
            return DomainEnums.MainCategory.fromSlug(mainCategorySlug);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
```

Add the import if not already present: `import com.tinniestudio.api.shared.entity.DomainEnums;` (check the existing imports first — `ContentStatus`/`MaturityRating` are likely imported as `DomainEnums.ContentStatus`/`DomainEnums.MaturityRating` directly; if so, add `import com.tinniestudio.api.shared.entity.DomainEnums;` as well so the fully-qualified `DomainEnums.MainCategory` reference resolves, or add `import com.tinniestudio.api.shared.entity.DomainEnums.MainCategory;` and use the bare `MainCategory` name consistently instead of the qualified form throughout this task — pick whichever matches this file's existing import style and use it consistently).

- [ ] **Step 5: Wire the query param into `ContentController.list()`**

Find:
```java
    public ResponseEntity<Page<ContentSummaryResponse>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) MaturityRating maturityRating,
            @RequestParam(required = false) Boolean comingSoon,
            @PageableDefault(size = 20, sort = "publishedAt") Pageable pageable) {
        return ResponseEntity.ok(contentService.list(type, category, maturityRating, comingSoon, pageable));
    }
```

Replace with:
```java
    public ResponseEntity<Page<ContentSummaryResponse>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) MaturityRating maturityRating,
            @RequestParam(required = false) Boolean comingSoon,
            @RequestParam(required = false) String mainCategory,
            @PageableDefault(size = 20, sort = "publishedAt") Pageable pageable) {
        return ResponseEntity.ok(contentService.list(type, category, maturityRating, comingSoon, mainCategory, pageable));
    }
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :api-service:test --tests ContentServiceTest`
Expected: PASS — all tests in this class, including the 3 new ones and the 4 updated call sites.

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/content/repository/ContentSpecifications.java api-service/src/main/java/com/tinniestudio/api/modules/content/service/ContentService.java api-service/src/main/java/com/tinniestudio/api/modules/content/controller/ContentController.java api-service/src/test/java/com/tinniestudio/api/modules/content/service/ContentServiceTest.java
git commit -m "feat: add mainCategory filter to GET /contents"
```

---

### Task 3: `mainCategory` on create/update

**Files:** `CreateContentRequest.java`, `UpdateContentRequest.java`, `ContentService.java`, `ContentServiceTest.java`

- [ ] **Step 1: Write the failing tests**

Find the `CreateTests` nested class in `ContentServiceTest.java` and add:

```java
        @Test
        @DisplayName("sets mainCategory from a valid slug")
        void setsMainCategoryFromSlug() {
            when(contentTypeRepository.findById(any())).thenReturn(Optional.of(movieType));
            when(contentRepository.saveAndFlush(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));
            CreateContentRequest req = new CreateContentRequest(
                "Test Movie", movieType.getId(), null, null, null, null, false, null, null, "movies");

            contentService.create(req, createdBy);

            ArgumentCaptor<Content> captor = ArgumentCaptor.forClass(Content.class);
            verify(contentRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getMainCategory()).isEqualTo(DomainEnums.MainCategory.MOVIES);
        }

        @Test
        @DisplayName("rejects an unknown mainCategory slug with 400")
        void rejectsUnknownMainCategoryOnCreate() {
            when(contentTypeRepository.findById(any())).thenReturn(Optional.of(movieType));
            CreateContentRequest req = new CreateContentRequest(
                "Test Movie", movieType.getId(), null, null, null, null, false, null, null, "not-real");

            assertThatThrownBy(() -> contentService.create(req, createdBy))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown mainCategory");
        }
```

Find the `UpdateTests` nested class and add:

```java
        @Test
        @DisplayName("updates mainCategory when provided")
        void updatesMainCategoryWhenProvided() {
            content.setMainCategory(DomainEnums.MainCategory.MOVIES);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));
            UpdateContentRequest req = new UpdateContentRequest(
                null, null, null, null, null, null, null, null, null, null, null, "kids");

            contentService.update(contentId, req);

            assertThat(content.getMainCategory()).isEqualTo(DomainEnums.MainCategory.KIDS);
        }

        @Test
        @DisplayName("leaves mainCategory unchanged when null")
        void leavesMainCategoryUnchangedWhenNull() {
            content.setMainCategory(DomainEnums.MainCategory.MOVIES);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));
            UpdateContentRequest req = new UpdateContentRequest(
                null, null, null, null, null, null, null, null, null, null, null, null);

            contentService.update(contentId, req);

            assertThat(content.getMainCategory()).isEqualTo(DomainEnums.MainCategory.MOVIES);
        }
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./gradlew :api-service:test --tests ContentServiceTest`
Expected: FAIL to compile — `CreateContentRequest`/`UpdateContentRequest` don't take a `mainCategory` arg yet.

- [ ] **Step 3: Add the field to `CreateContentRequest.java`**

Find:
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
    List<UUID> categoryIds,
    @NotBlank String mainCategory
) {}
```

- [ ] **Step 4: Add the field to `UpdateContentRequest.java`**

Find:
```java
public record UpdateContentRequest(
    String title, String description, String shortDescription,
    MaturityRating maturityRating, LocalDate releaseDate,
    String language, String country, Boolean comingSoon,
    Integer durationSeconds, String posterUrl, String thumbnailUrl,
    List<UUID> categoryIds
) {}
```

Replace with:
```java
public record UpdateContentRequest(
    String title, String description, String shortDescription,
    MaturityRating maturityRating, LocalDate releaseDate,
    String language, String country, Boolean comingSoon,
    Integer durationSeconds, String posterUrl, String thumbnailUrl,
    List<UUID> categoryIds, String mainCategory
) {}
```

- [ ] **Step 5: Wire into `ContentService.create()`**

Find:
```java
        content.setStatus(ContentStatus.DRAFT);
        content.setMaturityRating(req.maturityRating() != null ? req.maturityRating() : MaturityRating.NOT_RATED);
```

Replace with:
```java
        content.setStatus(ContentStatus.DRAFT);
        content.setMainCategory(parseMainCategoryOrThrow(req.mainCategory()) != null
            ? parseMainCategoryOrThrow(req.mainCategory())
            : (DomainEnums.MainCategory) failIfNull());
        content.setMaturityRating(req.maturityRating() != null ? req.maturityRating() : MaturityRating.NOT_RATED);
```

That's deliberately wrong — don't apply it. Use this instead (required field, `@NotBlank` already guarantees non-null/non-blank at the controller boundary via `@Valid`, so `create()` can call the parser directly without a null-check):

Find:
```java
        content.setStatus(ContentStatus.DRAFT);
        content.setMaturityRating(req.maturityRating() != null ? req.maturityRating() : MaturityRating.NOT_RATED);
```

Replace with:
```java
        content.setStatus(ContentStatus.DRAFT);
        content.setMainCategory(requireMainCategory(req.mainCategory()));
        content.setMaturityRating(req.maturityRating() != null ? req.maturityRating() : MaturityRating.NOT_RATED);
```

Add this new private helper next to `parseMainCategoryOrThrow` (added in Task 2):

```java
    /** Required field — @NotBlank on CreateContentRequest guarantees non-null/non-blank by the time this runs. */
    private DomainEnums.MainCategory requireMainCategory(String mainCategorySlug) {
        try {
            return DomainEnums.MainCategory.fromSlug(mainCategorySlug);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
```

- [ ] **Step 6: Wire into `ContentService.update()`**

Find:
```java
        if (req.categoryIds() != null) {
            content.setCategories(new HashSet<>(categoryRepository.findAllById(req.categoryIds())));
        }
        return ContentResponse.from(contentRepository.save(content));
```

Replace with:
```java
        if (req.categoryIds() != null) {
            content.setCategories(new HashSet<>(categoryRepository.findAllById(req.categoryIds())));
        }
        if (req.mainCategory() != null) {
            content.setMainCategory(parseMainCategoryOrThrow(req.mainCategory()));
        }
        return ContentResponse.from(contentRepository.save(content));
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew :api-service:test --tests ContentServiceTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/content/dto/CreateContentRequest.java api-service/src/main/java/com/tinniestudio/api/modules/content/dto/UpdateContentRequest.java api-service/src/main/java/com/tinniestudio/api/modules/content/service/ContentService.java api-service/src/test/java/com/tinniestudio/api/modules/content/service/ContentServiceTest.java
git commit -m "feat: accept mainCategory on content create and update"
```

---

### Task 4: Response DTOs gain `mainCategory`

**Files:** `ContentResponse.java`, `ContentSummaryResponse.java`, `PartnerContentResponse.java`

- [ ] **Step 1: `ContentResponse.java`**

Find:
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

Replace with:
```java
public record ContentResponse(
    UUID id, String title, String slug, String description, String shortDescription,
    com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse contentType,
    String mainCategory,
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
            c.getMainCategory().getSlug(),
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

- [ ] **Step 2: `ContentSummaryResponse.java`**

Find:
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

Replace with:
```java
public record ContentSummaryResponse(
    UUID id, String title, String slug, String shortDescription,
    com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse contentType,
    String mainCategory,
    String status, String maturityRating,
    LocalDate releaseDate, Boolean featured, Boolean comingSoon,
    Long viewCount, BigDecimal averageRating, Integer reviewCount,
    String posterUrl, String thumbnailUrl
) {
    public static ContentSummaryResponse from(Content c) {
        return new ContentSummaryResponse(
            c.getId(), c.getTitle(), c.getSlug(), c.getShortDescription(),
            com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse.from(c.getContentType()),
            c.getMainCategory().getSlug(),
            c.getStatus().name(), c.getMaturityRating().name(),
            c.getReleaseDate(), c.getFeatured(), c.getComingSoon(),
            c.getViewCount(), c.getAverageRating(), c.getReviewCount(),
            c.getPosterUrl(), c.getThumbnailUrl()
        );
    }
}
```

- [ ] **Step 3: `PartnerContentResponse.java`** (both factory methods — this file has two `from()` overloads, don't miss the second one)

Find:
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

Replace with:
```java
public record PartnerContentResponse(
    UUID id, String title, String slug, String description, String shortDescription,
    com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse contentType,
    String mainCategory,
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
            c.getMainCategory().getSlug(),
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
            c.contentType(), c.mainCategory(), c.status(), c.maturityRating(),
            c.releaseDate(), c.language(), c.country(),
            c.featured(), c.comingSoon(), c.viewCount(),
            c.durationSeconds(), c.posterUrl(), c.thumbnailUrl(),
            c.averageRating(), c.reviewCount(),
            c.categoryNames(), c.publishedAt(), c.rejectionReason()
        );
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :api-service:compileJava`
Expected: FAILS — every existing test constructing a `Content` fixture and passing it through one of these three `from()` methods will NPE at `c.getMainCategory().getSlug()` if `mainCategory` was never set on that fixture. This is expected and handled in Task 7 — don't fix it here, just confirm the compile itself succeeds (compilation failures here would mean a record-field-order mistake in this step; test failures are a separate, later concern).

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentResponse.java api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentSummaryResponse.java api-service/src/main/java/com/tinniestudio/api/modules/partner/dto/PartnerContentResponse.java
git commit -m "feat: add mainCategory to content response DTOs"
```

---

### Task 5: Search filter

**Files:** `SearchRequest.java`, `ContentRepository.java`, `SearchServiceImpl.java`

- [ ] **Step 1: Add the field to `SearchRequest.java`**

Find:
```java
    private String type;                // content-type slug, null = all types
    private String categorySlug;        // null = all categories
    private String language;            // null = all languages
    private String country;             // null = all countries
```

Replace with:
```java
    private String type;                // content-type slug, null = all types
    private String categorySlug;        // null = all categories
    private String mainCategory;        // main-category slug (movies/tv-shows/kids/sermons), null = all
    private String language;            // null = all languages
    private String country;             // null = all countries
```

- [ ] **Step 2: Add `main_category` to all 3 native queries + the new filter clause**

In `ContentRepository.java`, this same 4-line edit is needed in `searchByRelevance`, `searchByLatest`, and `searchByPopular` — both their `value` and `countQuery` strings. Do it once per method (6 total edits: 3 methods × {value, countQuery}).

For `searchByRelevance`'s `value`, find:
```java
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
        @Param("q") String q,
        @Param("type") String type,
        @Param("language") String language,
        @Param("country") String country,
        @Param("categorySlug") String categorySlug,
        Pageable pageable
    );
```

Replace with:
```java
        value = "SELECT c.id, c.title, c.slug, c.description, c.short_description," +
                " c.content_type_id, c.main_category, c.status, c.maturity_rating, c.release_date, c.language, c.country," +
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
                " AND (:mainCategory IS NULL OR c.main_category = :mainCategory)" +
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
                " AND (:mainCategory IS NULL OR c.main_category = :mainCategory)" +
                " AND (:categorySlug IS NULL OR EXISTS (" +
                "   SELECT 1 FROM content_categories cc" +
                "   JOIN categories cat ON cat.id = cc.category_id" +
                "   WHERE cc.content_id = c.id AND cat.slug = :categorySlug" +
                " ))",
        nativeQuery = true
    )
    Page<Content> searchByRelevance(
        @Param("q") String q,
        @Param("type") String type,
        @Param("language") String language,
        @Param("country") String country,
        @Param("categorySlug") String categorySlug,
        @Param("mainCategory") String mainCategory,
        Pageable pageable
    );
```

Apply the identical pattern (add `c.main_category` to the SELECT list, add the `AND (:mainCategory IS NULL OR c.main_category = :mainCategory)` clause to both `value` and `countQuery`, add `@Param("mainCategory") String mainCategory` to the method signature before `Pageable pageable`) to `searchByLatest` and `searchByPopular` as well — same 4 lines changed in each, only the `ORDER BY` clause differs between the three methods and is untouched.

**Important:** the value passed for `:mainCategory` must be the enum's `.name()` (e.g. `"TV_SHOWS"`), not the external slug (`"tv-shows"`) — `main_category` is stored via `@Enumerated(EnumType.STRING)`, so the column literally contains the Java constant name, not the slug. This conversion happens in `SearchServiceImpl` (Step 3 below), not here.

- [ ] **Step 3: Wire into `SearchServiceImpl.java`**

Find:
```java
        String typeStr      = request.getType();
        String language     = request.getLanguage();
        String country      = request.getCountry();
        String categorySlug = request.getCategorySlug();
        var pageable        = PageRequest.of(request.getPage(), request.getLimit());

        Page<com.tinniestudio.api.shared.entity.Content> page = switch (request.getSort()) {
            case LATEST  -> contentRepository.searchByLatest(q, typeStr, language, country, categorySlug, pageable);
            case POPULAR -> contentRepository.searchByPopular(q, typeStr, language, country, categorySlug, pageable);
            default      -> contentRepository.searchByRelevance(q, typeStr, language, country, categorySlug, pageable);
        };
```

Replace with:
```java
        String typeStr      = request.getType();
        String language     = request.getLanguage();
        String country      = request.getCountry();
        String categorySlug = request.getCategorySlug();
        String mainCategory = parseMainCategoryName(request.getMainCategory());
        var pageable        = PageRequest.of(request.getPage(), request.getLimit());

        Page<com.tinniestudio.api.shared.entity.Content> page = switch (request.getSort()) {
            case LATEST  -> contentRepository.searchByLatest(q, typeStr, language, country, categorySlug, mainCategory, pageable);
            case POPULAR -> contentRepository.searchByPopular(q, typeStr, language, country, categorySlug, mainCategory, pageable);
            default      -> contentRepository.searchByRelevance(q, typeStr, language, country, categorySlug, mainCategory, pageable);
        };
```

Add this private helper to the same class (converts the external slug to the stored enum-name string, or returns `null` for "no filter" — matching the native queries' `:mainCategory IS NULL` check):

```java
    /** Converts the external slug to the enum's stored .name() (e.g. "tv-shows" -> "TV_SHOWS"); null/blank means no filter. */
    private String parseMainCategoryName(String mainCategorySlug) {
        if (mainCategorySlug == null || mainCategorySlug.isBlank()) return null;
        try {
            return com.tinniestudio.api.shared.entity.DomainEnums.MainCategory.fromSlug(mainCategorySlug).name();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
```

- [ ] **Step 4: Write a test in `SearchServiceTest.java`**

Find how the existing tests in this file call `searchService.search(...)` and mock `contentRepository.searchByRelevance(...)` (read the file first to match its exact mocking style — likely `when(contentRepository.searchByRelevance(any(), any(), any(), any(), any(), any())).thenReturn(...)`), then add one test following that same style:

```java
    @Test
    @DisplayName("passes mainCategory through as the stored enum name")
    void passesMainCategoryAsEnumName() {
        SearchRequest request = new SearchRequest();
        request.setQ("test");
        request.setMainCategory("tv-shows");
        when(contentRepository.searchByRelevance(any(), any(), any(), any(), any(), eq("TV_SHOWS"), any()))
            .thenReturn(new PageImpl<>(List.of()));

        searchService.search(request);

        verify(contentRepository).searchByRelevance(any(), any(), any(), any(), any(), eq("TV_SHOWS"), any());
    }
```

Adjust the mock/verify argument order and any existing `when(...)` calls elsewhere in this file to account for the new 6th `mainCategory` parameter inserted before `Pageable` — every existing `searchByRelevance`/`searchByLatest`/`searchByPopular` mock in this file needs one more `any()` added in that position.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :api-service:test --tests SearchServiceTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/search/dto/SearchRequest.java api-service/src/main/java/com/tinniestudio/api/modules/content/repository/ContentRepository.java api-service/src/main/java/com/tinniestudio/api/modules/search/service/SearchServiceImpl.java api-service/src/test/java/com/tinniestudio/api/modules/search/service/SearchServiceTest.java
git commit -m "feat: add mainCategory filter to search"
```

---

### Task 6: Migration — backfill + retire Kids/Sermons

**Files:** `V55__add_main_category.sql`

- [ ] **Step 1: Write the migration**

Create `api-service/src/main/resources/db/migration/V55__add_main_category.sql`:

```sql
-- Add nullable first, same reasoning as V53's content_type_id: a NOT NULL column can't be added
-- to a populated table without a default, and the correct value varies per row.
ALTER TABLE contents ADD COLUMN main_category VARCHAR(20);

-- Priority 1: explicitly tagged "Sermons" (the about-to-be-retired genre category below).
UPDATE contents c
SET main_category = 'SERMONS'
WHERE EXISTS (
    SELECT 1 FROM content_categories cc
    JOIN categories cat ON cat.id = cc.category_id
    WHERE cc.content_id = c.id AND cat.slug = 'sermons'
);

-- Priority 2: explicitly tagged "Kids".
UPDATE contents c
SET main_category = 'KIDS'
WHERE c.main_category IS NULL
  AND EXISTS (
    SELECT 1 FROM content_categories cc
    JOIN categories cat ON cat.id = cc.category_id
    WHERE cc.content_id = c.id AND cat.slug = 'kids'
);

-- Priority 3: series (MULTI_EPISODE) with no Kids/Sermons tag.
UPDATE contents c
SET main_category = 'TV_SHOWS'
FROM content_types ct
WHERE c.main_category IS NULL
  AND c.content_type_id = ct.id
  AND ct.structural_kind = 'MULTI_EPISODE';

-- Priority 4 (default fallback): everything else.
UPDATE contents
SET main_category = 'MOVIES'
WHERE main_category IS NULL;

ALTER TABLE contents ALTER COLUMN main_category SET NOT NULL;

CREATE INDEX idx_content_main_category ON contents(main_category);

-- Retire the now-redundant genre rows — mainCategory alone expresses "Kids"/"Sermons" going
-- forward; keeping both would let a title be simultaneously mainCategory=KIDS and
-- subcategory-tagged "Kids", which is confusing and redundant. Deactivated, not deleted, to
-- preserve historical content_categories join rows (used by the backfill above).
UPDATE categories SET is_active = false WHERE slug IN ('kids', 'sermons');
```

- [ ] **Step 2: Run the app locally against a real/dev database to confirm the migration applies cleanly**

Run: `./gradlew :api-service:bootRun` (or however this repo normally starts the app against its dev DB — check `README.md`/`docs/` if unsure) and confirm Flyway reports `V55__add_main_category.sql` applied with no errors. If no dev DB is available in this environment, at minimum run `./gradlew :api-service:test` (Task 7's Testcontainers-backed tests will exercise this migration against a real ephemeral Postgres instance).

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/resources/db/migration/V55__add_main_category.sql
git commit -m "feat: add main_category column with backfill and retire Kids/Sermons categories"
```

---

### Task 7: Fix broken test fixtures + full suite

**Files:** up to 17 test files (excluding `ContentServiceTest.java`, already handled in Tasks 2-3) that construct `Content` via `new Content()`

- [ ] **Step 1: Find every file constructing a `Content` fixture**

Run: `grep -rl "new Content()" api-service/src/test/`
Expected output (18 files total, `ContentServiceTest.java` already handled):
```
api-service/src/test/java/com/tinniestudio/api/modules/content/repository/ContentSpecificationsTest.java
api-service/src/test/java/com/tinniestudio/api/modules/content/repository/ContentSearchRepositoryTest.java
api-service/src/test/java/com/tinniestudio/api/modules/discover/service/DiscoverServiceTest.java
api-service/src/test/java/com/tinniestudio/api/modules/content/controller/AdminContentOwnershipControllerTest.java
api-service/src/test/java/com/tinniestudio/api/modules/content/service/ContentServiceTest.java
api-service/src/test/java/com/tinniestudio/api/modules/upload/service/VideoActivationServiceTest.java
api-service/src/test/java/com/tinniestudio/api/modules/upload/service/PartnerVideoServiceTest.java
api-service/src/test/java/com/tinniestudio/api/modules/search/service/SearchServiceTest.java
api-service/src/test/java/com/tinniestudio/api/modules/analytics/consumer/AnalyticsAtomicityIntegrationTest.java
api-service/src/test/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceTest.java
api-service/src/test/java/com/tinniestudio/api/modules/episode/service/EpisodeServiceTest.java
api-service/src/test/java/com/tinniestudio/api/modules/season/service/SeasonServiceTest.java
api-service/src/test/java/com/tinniestudio/api/modules/upload/service/UploadServiceTest.java
api-service/src/test/java/com/tinniestudio/api/modules/partner/service/PartnerServiceTest.java
api-service/src/test/java/com/tinniestudio/api/modules/analytics/service/AnalyticsServiceImplTest.java
api-service/src/test/java/com/tinniestudio/api/modules/library/service/FavoriteServiceTest.java
api-service/src/test/java/com/tinniestudio/api/modules/library/service/WatchHistoryServiceTest.java
api-service/src/test/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumerTest.java
```

- [ ] **Step 2: Run the full test suite to see what actually breaks**

Run: `./gradlew :api-service:test`

Not every file in the list above will actually fail — only fixtures whose `Content` object gets passed through `ContentResponse.from()`, `ContentSummaryResponse.from()`, or `PartnerContentResponse.from()` will NPE at `c.getMainCategory().getSlug()` (a `Content` used only as, say, a `WatchProgress.content` reference or a raw entity assertion won't touch that code path and won't break). Record the exact list of failing test classes from the Gradle output.

- [ ] **Step 3: Fix each failing fixture**

For each test class that fails with an NPE traceable to `getMainCategory()`, find its `Content` fixture setup (typically a `@BeforeEach` method, following the same shape as `ContentServiceTest`'s `content = new Content(); content.setId(...); ...`) and add one line:

```java
content.setMainCategory(DomainEnums.MainCategory.MOVIES);
```

(Add the import `import com.tinniestudio.api.shared.entity.DomainEnums;` — or `DomainEnums.MainCategory` directly if the file already imports specific nested enums like `DomainEnums.ContentStatus` elsewhere — to each file this is added to, if not already present.)

Use `MOVIES` as the default value in every fixture unless that specific test's assertions are actually about `mainCategory`/category-based filtering (none of the files in the list above are — the only tests asserting on `mainCategory` are the ones added in Tasks 2-3) — the exact value doesn't matter to what these tests check, it only needs to be non-null to satisfy the new field's contract.

- [ ] **Step 4: Re-run the full suite**

Run: `./gradlew :api-service:test`
Expected: `BUILD SUCCESSFUL`, modulo this repo's known pre-existing unrelated Testcontainers/Postgres-environment failures (distinguish those from anything newly introduced — compare against a baseline run on the pre-Task-1 commit if there's any doubt about which failures are pre-existing).

- [ ] **Step 5: Commit**

```bash
git add api-service/src/test/
git commit -m "fix: set mainCategory on test fixtures broken by the new required field"
```

---

## Self-Review Notes

- **Spec coverage:** enum + entity field (Task 1), list filter (Task 2), create/update (Task 3), response DTOs including both `PartnerContentResponse` factories (Task 4), search (Task 5), migration + category retirement (Task 6), fixture fallout (Task 7) — every "Per-Repo Impact: server" bullet from the design spec has a corresponding task.
- **Placeholder scan:** Task 3 Step 5 deliberately shows a wrong intermediate snippet and explicitly says "don't apply it" before giving the real one — this is intentional pedagogical contrast, not a placeholder; the real replacement that follows is complete and correct.
- **Type consistency:** `mainCategory` is consistently a `String` (slug) at every controller/DTO/request boundary and only becomes the `DomainEnums.MainCategory` enum type inside `Content`/`ContentSpecifications`/service-internal logic — checked across all 7 tasks, no task treats it as the enum type where a prior task established it as a String, or vice versa.
- **Ordering dependency respected:** Task 4's DTO changes are known to break Task-7-scoped fixtures; Step 4 of Task 4 explicitly says not to fix that yet, so Task 7 isn't duplicating work or fixing things prematurely out of order.
