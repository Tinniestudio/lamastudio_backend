# Combined Batch 3+4+5: Content, Discovery & Seasons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the full content system — categories, homepage sections, content CRUD with Netflix-style filters, discovery endpoints, seasons, and episodes — so the platform can ingest and surface video content.

**Architecture:** Feature modules under `com.tinniestudio.api.modules.*`, each with `dto/`, `repository/`, `service/`, `controller/` sub-packages, following the existing billing/user module pattern. Shared entities live in `api/shared/entity/`. DB state driven by Flyway V15–V24. Redis caching via existing `RedisConfig` CacheManager with per-cache TTL overrides.

**Tech Stack:** Spring Boot 3.3.5, Java 21, PostgreSQL (Flyway migrations), Spring Data JPA + Specifications, Spring Cache + Redis (Lettuce), AWS SDK v2 (MinIO), Lombok, Jakarta Validation, Mockito (unit tests), MockMvc (slice tests).

---

## File Map

**New migrations:**
- `api-service/src/main/resources/db/migration/V15__add_slugify_function.sql`
- `api-service/src/main/resources/db/migration/V16__add_categories.sql`
- `api-service/src/main/resources/db/migration/V17__add_homepage_sections.sql`
- `api-service/src/main/resources/db/migration/V18__add_contents.sql`
- `api-service/src/main/resources/db/migration/V19__add_content_associations.sql`
- `api-service/src/main/resources/db/migration/V20__add_seasons.sql`
- `api-service/src/main/resources/db/migration/V21__add_episodes.sql`
- `api-service/src/main/resources/db/migration/V22__add_watch_progress.sql`
- `api-service/src/main/resources/db/migration/V23__seed_categories.sql`
- `api-service/src/main/resources/db/migration/V24__seed_homepage_sections.sql`

**Modified shared files:**
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java` — add REVIEW, REJECTED, MaturityRating, SectionType
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/Category.java` — add posterUrl, displayOrder
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/Content.java` — add viewCount, comingSoon, maturityRating, durationSeconds
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/WatchProgress.java` — add videoAssetId, completionPercentage, deviceType
- `api-service/src/main/java/com/tinniestudio/api/shared/storage/StorageService.java` — add uploadFile
- `api-service/src/main/java/com/tinniestudio/api/shared/storage/MinioStorageService.java` — implement uploadFile
- `api-service/src/main/java/com/tinniestudio/api/shared/storage/NoOpStorageService.java` — stub uploadFile
- `api-service/src/main/java/com/tinniestudio/api/shared/config/RedisConfig.java` — add per-cache TTL overrides
- `api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java` — add public endpoints

**New shared entities:**
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/HomepageSection.java`
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/ContentCast.java`

**New category module:**
- `api-service/src/main/java/com/tinniestudio/api/modules/category/dto/CreateCategoryRequest.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/category/dto/UpdateCategoryRequest.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/category/dto/CategoryResponse.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/category/repository/CategoryRepository.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/category/service/CategoryService.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/category/controller/CategoryController.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/category/controller/AdminCategoryController.java`

**New homepage module:**
- `api-service/src/main/java/com/tinniestudio/api/modules/homepage/dto/CreateSectionRequest.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/homepage/dto/UpdateSectionRequest.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/homepage/dto/SectionResponse.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/homepage/repository/HomepageSectionRepository.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/homepage/service/HomepageSectionService.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/homepage/controller/HomepageSectionController.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/homepage/controller/AdminHomepageSectionController.java`

**New content module:**
- `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/CreateContentRequest.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/UpdateContentRequest.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentResponse.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentSummaryResponse.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/content/repository/ContentRepository.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/content/repository/ContentSpecifications.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/content/service/ContentService.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/content/controller/ContentController.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/content/controller/AdminContentController.java`

**New discover module:**
- `api-service/src/main/java/com/tinniestudio/api/modules/discover/service/DiscoverService.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/discover/controller/DiscoverController.java`

**New season module:**
- `api-service/src/main/java/com/tinniestudio/api/modules/season/dto/CreateSeasonRequest.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/season/dto/UpdateSeasonRequest.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/season/dto/SeasonResponse.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/season/repository/SeasonRepository.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/season/service/SeasonService.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/season/controller/SeasonController.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/season/controller/AdminSeasonController.java`

**New episode module:**
- `api-service/src/main/java/com/tinniestudio/api/modules/episode/dto/CreateEpisodeRequest.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/episode/dto/UpdateEpisodeRequest.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/episode/dto/EpisodeResponse.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/episode/dto/ReorderEpisodesRequest.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/episode/repository/EpisodeRepository.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/episode/service/EpisodeService.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/episode/controller/EpisodeController.java`
- `api-service/src/main/java/com/tinniestudio/api/modules/episode/controller/AdminEpisodeController.java`

---

## Task 1: DB Migrations V15–V24

**Files:**
- Create: `api-service/src/main/resources/db/migration/V15__add_slugify_function.sql`
- Create: `api-service/src/main/resources/db/migration/V16__add_categories.sql`
- Create: `api-service/src/main/resources/db/migration/V17__add_homepage_sections.sql`
- Create: `api-service/src/main/resources/db/migration/V18__add_contents.sql`
- Create: `api-service/src/main/resources/db/migration/V19__add_content_associations.sql`
- Create: `api-service/src/main/resources/db/migration/V20__add_seasons.sql`
- Create: `api-service/src/main/resources/db/migration/V21__add_episodes.sql`
- Create: `api-service/src/main/resources/db/migration/V22__add_watch_progress.sql`
- Create: `api-service/src/main/resources/db/migration/V23__seed_categories.sql`
- Create: `api-service/src/main/resources/db/migration/V24__seed_homepage_sections.sql`

- [ ] **Step 1: Write V15 — slugify function**

```sql
-- V15__add_slugify_function.sql
CREATE OR REPLACE FUNCTION slugify(val TEXT) RETURNS TEXT AS $$
BEGIN
    RETURN lower(
        regexp_replace(
            trim(regexp_replace(val, '[^a-zA-Z0-9\s\-]', '', 'g')),
            '\s+', '-', 'g'
        )
    );
END;
$$ LANGUAGE plpgsql IMMUTABLE;
```

- [ ] **Step 2: Write V16 — categories table**

```sql
-- V16__add_categories.sql
CREATE TABLE IF NOT EXISTS categories (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL UNIQUE,
    slug            VARCHAR(120) NOT NULL UNIQUE,
    description     TEXT,
    poster_url      TEXT,
    display_order   INTEGER     NOT NULL DEFAULT 0,
    is_active       BOOLEAN     NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_categories_slug        ON categories(slug);
CREATE INDEX idx_categories_is_active   ON categories(is_active);
CREATE INDEX idx_categories_order       ON categories(display_order);

CREATE OR REPLACE FUNCTION set_category_slug() RETURNS TRIGGER AS $$
DECLARE
    base_slug TEXT;
    candidate TEXT;
    counter   INTEGER := 2;
BEGIN
    base_slug := slugify(NEW.name);
    candidate := base_slug;
    WHILE EXISTS (
        SELECT 1 FROM categories
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

CREATE TRIGGER trg_category_slug
    BEFORE INSERT OR UPDATE OF name ON categories
    FOR EACH ROW EXECUTE FUNCTION set_category_slug();
```

- [ ] **Step 3: Write V17 — homepage_sections table**

```sql
-- V17__add_homepage_sections.sql
CREATE TABLE IF NOT EXISTS homepage_sections (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    title         VARCHAR(100) NOT NULL,
    section_type  VARCHAR(30)  NOT NULL,
    category_id   UUID         REFERENCES categories(id) ON DELETE SET NULL,
    display_order INTEGER      NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_homepage_sections_order ON homepage_sections(display_order);
```

- [ ] **Step 4: Write V18 — contents table**

```sql
-- V18__add_contents.sql
CREATE TABLE IF NOT EXISTS contents (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title            VARCHAR(255) NOT NULL,
    slug             VARCHAR(280) NOT NULL UNIQUE,
    description      TEXT,
    short_description VARCHAR(500),
    type             VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    maturity_rating  VARCHAR(10)  NOT NULL DEFAULT 'NOT_RATED',
    release_date     DATE,
    language         VARCHAR(50),
    country          VARCHAR(50),
    featured         BOOLEAN      NOT NULL DEFAULT false,
    coming_soon      BOOLEAN      NOT NULL DEFAULT false,
    view_count       BIGINT       NOT NULL DEFAULT 0,
    duration_seconds INTEGER,
    poster_url       TEXT,
    thumbnail_url    TEXT,
    created_by       UUID         NOT NULL,
    published_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_content_slug        ON contents(slug);
CREATE INDEX idx_content_type        ON contents(type);
CREATE INDEX idx_content_status      ON contents(status);
CREATE INDEX idx_content_view_count  ON contents(view_count DESC);
CREATE INDEX idx_content_featured    ON contents(featured) WHERE featured = true;
CREATE INDEX idx_content_coming_soon ON contents(coming_soon) WHERE coming_soon = true;
CREATE INDEX idx_content_published   ON contents(published_at DESC) WHERE status = 'PUBLISHED';

CREATE OR REPLACE FUNCTION set_content_slug() RETURNS TRIGGER AS $$
DECLARE
    base_slug TEXT;
    candidate TEXT;
    counter   INTEGER := 2;
BEGIN
    base_slug := slugify(NEW.title);
    candidate := base_slug;
    WHILE EXISTS (
        SELECT 1 FROM contents
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

CREATE TRIGGER trg_content_slug
    BEFORE INSERT OR UPDATE OF title ON contents
    FOR EACH ROW EXECUTE FUNCTION set_content_slug();
```

- [ ] **Step 5: Write V19 — content_categories join + content_cast**

```sql
-- V19__add_content_associations.sql
CREATE TABLE IF NOT EXISTS content_categories (
    content_id  UUID NOT NULL REFERENCES contents(id)   ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    PRIMARY KEY (content_id, category_id)
);

CREATE INDEX idx_content_categories_category ON content_categories(category_id);

CREATE TABLE IF NOT EXISTS content_cast (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    content_id        UUID         NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    name              VARCHAR(100) NOT NULL,
    role              VARCHAR(100),
    character_name    VARCHAR(100),
    profile_image_url TEXT,
    display_order     INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_content_cast_content ON content_cast(content_id);
```

- [ ] **Step 6: Write V20 — seasons table**

```sql
-- V20__add_seasons.sql
CREATE TABLE IF NOT EXISTS seasons (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    content_id    UUID        NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    season_number INTEGER     NOT NULL,
    title         VARCHAR(255),
    description   TEXT,
    release_date  DATE,
    poster_url    TEXT,
    thumbnail_url TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (content_id, season_number)
);

CREATE INDEX idx_seasons_content_id ON seasons(content_id);
```

- [ ] **Step 7: Write V21 — episodes table**

```sql
-- V21__add_episodes.sql
CREATE TABLE IF NOT EXISTS episodes (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    season_id        UUID         NOT NULL REFERENCES seasons(id) ON DELETE CASCADE,
    episode_number   INTEGER      NOT NULL,
    title            VARCHAR(255) NOT NULL,
    description      TEXT,
    release_date     DATE,
    duration_seconds INTEGER,
    thumbnail_url    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (season_id, episode_number)
);

CREATE INDEX idx_episodes_season_id ON episodes(season_id);
```

- [ ] **Step 8: Write V22 — watch_progress table**

```sql
-- V22__add_watch_progress.sql
CREATE TABLE IF NOT EXISTS watch_progress (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID          NOT NULL,
    content_id            UUID          REFERENCES contents(id) ON DELETE SET NULL,
    episode_id            UUID          REFERENCES episodes(id) ON DELETE SET NULL,
    video_asset_id        UUID,
    progress_seconds      INTEGER       NOT NULL DEFAULT 0,
    duration_seconds      INTEGER,
    completion_percentage NUMERIC(5,2),
    completed             BOOLEAN       NOT NULL DEFAULT false,
    device_type           VARCHAR(50),
    last_watched_at       TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_watch_progress_user              ON watch_progress(user_id);
CREATE INDEX idx_watch_progress_user_content      ON watch_progress(user_id, content_id);
CREATE UNIQUE INDEX idx_watch_progress_user_ep    ON watch_progress(user_id, episode_id)  WHERE episode_id IS NOT NULL;
CREATE UNIQUE INDEX idx_watch_progress_user_movie ON watch_progress(user_id, content_id)  WHERE episode_id IS NULL;
```

- [ ] **Step 9: Write V23 — seed 18 categories**

```sql
-- V23__seed_categories.sql
-- Slugs are set automatically by trg_category_slug
INSERT INTO categories (name, display_order) VALUES
    ('Action',      1),
    ('Drama',       2),
    ('Comedy',      3),
    ('Thriller',    4),
    ('Sci-Fi',      5),
    ('Horror',      6),
    ('Romance',     7),
    ('Documentary', 8),
    ('Kids',        9),
    ('Animation',  10),
    ('Fantasy',    11),
    ('Crime',      12),
    ('Sports',     13),
    ('Sermons',    14),
    ('Reality',    15),
    ('History',    16),
    ('Music',      17),
    ('Travel',     18)
ON CONFLICT (name) DO NOTHING;
```

- [ ] **Step 10: Write V24 — seed homepage sections**

```sql
-- V24__seed_homepage_sections.sql
INSERT INTO homepage_sections (title, section_type, display_order, is_active) VALUES
    ('Trending Now',  'TRENDING',     1, true),
    ('Featured',      'FEATURED',     2, true),
    ('New Releases',  'NEW_RELEASES', 3, true),
    ('Coming Soon',   'COMING_SOON',  4, true)
ON CONFLICT DO NOTHING;
```

- [ ] **Step 11: Run migrations and verify**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
./gradlew :api-service:flywayMigrate
```

Expected: `Successfully applied 10 migrations to schema "public"` (V15–V24). No errors.

- [ ] **Step 12: Commit**

```bash
git add api-service/src/main/resources/db/migration/
git commit -m "feat(db): add V15-V24 migrations — content, category, discovery schema + seeds"
```

---

## Task 2: Entity + Enum Updates + StorageService.uploadFile

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/Category.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/Content.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/WatchProgress.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/shared/entity/HomepageSection.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/shared/entity/ContentCast.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/storage/StorageService.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/storage/MinioStorageService.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/storage/NoOpStorageService.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/config/RedisConfig.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/shared/storage/MinioStorageServiceTest.java`

- [ ] **Step 1: Write the failing test for uploadFile in MinioStorageServiceTest**

Add two new test cases inside `MinioStorageServiceTest`:

```java
@Nested
@DisplayName("uploadFile()")
class UploadFileTests {

    @Test
    @DisplayName("uploads bytes and returns public URL")
    void returnsPublicUrl() {
        // MinIO returns endpoint/bucket/key as the public URL
        String url = service.uploadFile("posters/categories/action.jpg", new byte[]{1, 2, 3}, "image/jpeg");

        assertThat(url).isEqualTo("http://localhost:9000/tinniestudio/posters/categories/action.jpg");
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("wraps SdkException as StorageException")
    void wrapsUploadException() {
        doThrow(SdkException.builder().message("timeout").build())
            .when(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        assertThatThrownBy(() ->
            service.uploadFile("posters/categories/action.jpg", new byte[]{1}, "image/jpeg")
        ).isInstanceOf(StorageException.class)
         .hasMessageContaining("posters/categories/action.jpg");
    }
}
```

Run: `./gradlew :api-service:test --tests "*.MinioStorageServiceTest" -i`
Expected: FAIL — `uploadFile` not yet on interface.

- [ ] **Step 2: Add uploadFile to StorageService interface**

In `StorageService.java`, add after `deleteObject`:

```java
/**
 * Upload raw bytes to object storage. Returns the public URL of the stored object.
 * Used for direct server-side uploads (e.g., category poster via multipart form).
 */
String uploadFile(String key, byte[] content, String contentType);
```

- [ ] **Step 3: Implement uploadFile in MinioStorageService**

Add import `software.amazon.awssdk.core.sync.RequestBody` and add method:

```java
@Override
public String uploadFile(String key, byte[] content, String contentType) {
    try {
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(props.getBucket())
                .key(key)
                .contentType(contentType)
                .contentLength((long) content.length)
                .build(),
            RequestBody.fromBytes(content)
        );
        return props.getEndpoint() + "/" + props.getBucket() + "/" + key;
    } catch (SdkException e) {
        throw new StorageException("Failed to upload file for key=" + key, e);
    }
}
```

- [ ] **Step 4: Stub uploadFile in NoOpStorageService**

```java
@Override
public String uploadFile(String key, byte[] content, String contentType) {
    return "https://noop.storage/" + key;
}
```

- [ ] **Step 5: Run uploadFile tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.MinioStorageServiceTest" -i
```

Expected: all tests PASS (13 total including 2 new).

- [ ] **Step 6: Update DomainEnums — add enums**

In `DomainEnums.java`, make the following changes:

Replace `ContentStatus`:
```java
public enum ContentStatus {
    DRAFT,
    REVIEW,
    PROCESSING,
    PUBLISHED,
    REJECTED,
    ARCHIVED
}
```

Add after `ContentType`:
```java
public enum MaturityRating {
    G,
    PG,
    PG_13,
    R,
    NOT_RATED
}

public enum SectionType {
    TRENDING,
    FEATURED,
    CONTINUE_WATCHING,
    CATEGORY,
    NEW_RELEASES,
    COMING_SOON
}
```

- [ ] **Step 7: Update Category entity**

Add fields to `Category.java`:
```java
private String posterUrl;

@Column(nullable = false)
private Integer displayOrder = 0;
```

- [ ] **Step 8: Update Content entity**

Add imports `import java.math.BigDecimal;` and add fields to `Content.java` (also add `@Index` for `view_count` to the `@Table` annotation):

```java
@Column(nullable = false)
private Long viewCount = 0L;

@Column(nullable = false)
private Boolean comingSoon = false;

@Enumerated(EnumType.STRING)
@Column(nullable = false)
private MaturityRating maturityRating = MaturityRating.NOT_RATED;

private Integer durationSeconds;
```

Also update `@Table` indexes to include view_count:
```java
@Table(name = "contents", indexes = {
    @Index(name = "idx_content_slug",        columnList = "slug",        unique = true),
    @Index(name = "idx_content_type",        columnList = "type"),
    @Index(name = "idx_content_status",      columnList = "status"),
    @Index(name = "idx_content_view_count",  columnList = "view_count")
})
```

- [ ] **Step 9: Update WatchProgress entity**

Add fields to `WatchProgress.java`:
```java
private UUID videoAssetId;

@Column(precision = 5, scale = 2)
private java.math.BigDecimal completionPercentage;

private String deviceType;
```

- [ ] **Step 10: Create HomepageSection entity**

```java
package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.SectionType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "homepage_sections")
@Getter
@Setter
@NoArgsConstructor
public class HomepageSection extends BaseEntity {

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SectionType sectionType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false)
    private Integer displayOrder = 0;

    @Column(nullable = false)
    private Boolean isActive = true;
}
```

- [ ] **Step 11: Create ContentCast entity**

```java
package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "content_cast")
@Getter
@Setter
@NoArgsConstructor
public class ContentCast extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    @Column(nullable = false)
    private String name;

    private String role;

    private String characterName;

    private String profileImageUrl;

    @Column(nullable = false)
    private Integer displayOrder = 0;
}
```

- [ ] **Step 12: Update RedisConfig — add per-cache TTL overrides**

In `RedisConfig.cacheManager()`, replace the `return` statement with:

```java
Map<String, RedisCacheConfiguration> cacheConfigs = new java.util.HashMap<>();
cacheConfigs.put("categories",       config.entryTtl(Duration.ofMinutes(10)));
cacheConfigs.put("homepage-sections",config.entryTtl(Duration.ofMinutes(5)));
cacheConfigs.put("content-list",     config.entryTtl(Duration.ofMinutes(2)));
cacheConfigs.put("content-detail",   config.entryTtl(Duration.ofMinutes(5)));
cacheConfigs.put("discover",         config.entryTtl(Duration.ofMinutes(2)));

log.info("CacheManager configured with default TTL: {} minutes + per-cache overrides",
        DEFAULT_CACHE_TTL_MINUTES);
return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(config)
        .withInitialCacheConfigurations(cacheConfigs)
        .build();
```

Add import at top: `import java.util.HashMap; import java.util.Map;`

- [ ] **Step 13: Build to verify no compilation errors**

```bash
./gradlew :api-service:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 14: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/
git commit -m "feat(entities): update enums, entities, StorageService.uploadFile, Redis TTL config"
```

---

## Task 3: Category Module

**Files:**
- Create: `dto/CreateCategoryRequest.java`, `dto/UpdateCategoryRequest.java`, `dto/CategoryResponse.java`
- Create: `repository/CategoryRepository.java`
- Create: `service/CategoryService.java`
- Create: `controller/CategoryController.java`
- Create: `controller/AdminCategoryController.java`
- Modify: `SecurityConfig.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/category/service/CategoryServiceTest.java`

All files in package `com.tinniestudio.api.modules.category.*`

- [ ] **Step 1: Write failing tests for CategoryService**

```java
package com.tinniestudio.api.modules.category.service;

import com.tinniestudio.api.modules.category.dto.CategoryResponse;
import com.tinniestudio.api.modules.category.dto.CreateCategoryRequest;
import com.tinniestudio.api.modules.category.repository.CategoryRepository;
import com.tinniestudio.api.shared.entity.Category;
import com.tinniestudio.api.shared.exception.StorageException;
import com.tinniestudio.api.shared.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryService")
class CategoryServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private StorageService storageService;
    @Mock private CacheManager cacheManager;

    @InjectMocks private CategoryService categoryService;

    private Category category;

    @BeforeEach
    void setUp() {
        category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Action");
        category.setSlug("action");
        category.setIsActive(true);
        category.setDisplayOrder(1);
    }

    @Nested
    @DisplayName("listActive()")
    class ListActiveTests {

        @Test
        @DisplayName("returns only active categories ordered by displayOrder")
        void returnsActiveCategories() {
            when(categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(category));

            List<CategoryResponse> result = categoryService.listActive();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("Action");
            assertThat(result.get(0).slug()).isEqualTo("action");
        }
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("saves category with poster upload when poster provided")
        void savesWithPoster() throws IOException {
            CreateCategoryRequest req = new CreateCategoryRequest("Horror", "Scary stuff", 6);
            MockMultipartFile poster = new MockMultipartFile(
                "poster", "horror.jpg", "image/jpeg", new byte[]{1, 2, 3}
            );

            when(storageService.uploadFile(anyString(), any(), eq("image/jpeg")))
                .thenReturn("http://localhost:9000/tinniestudio/posters/categories/horror.jpg");
            when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

            CategoryResponse result = categoryService.create(req, poster);

            assertThat(result.name()).isEqualTo("Horror");
            assertThat(result.posterUrl()).contains("horror.jpg");
            verify(storageService).uploadFile(anyString(), eq(new byte[]{1, 2, 3}), eq("image/jpeg"));
        }

        @Test
        @DisplayName("saves category without poster when poster is null")
        void savesWithoutPoster() {
            CreateCategoryRequest req = new CreateCategoryRequest("Drama", null, 2);
            when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

            CategoryResponse result = categoryService.create(req, null);

            assertThat(result.name()).isEqualTo("Drama");
            assertThat(result.posterUrl()).isNull();
            verifyNoInteractions(storageService);
        }
    }
}
```

Run: `./gradlew :api-service:test --tests "*.CategoryServiceTest" -i`
Expected: FAIL — classes not yet created.

- [ ] **Step 2: Create DTOs**

`CreateCategoryRequest.java`:
```java
package com.tinniestudio.api.modules.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
    @NotBlank @Size(max = 100) String name,
    String description,
    Integer displayOrder
) {}
```

`UpdateCategoryRequest.java`:
```java
package com.tinniestudio.api.modules.category.dto;

import jakarta.validation.constraints.Size;

public record UpdateCategoryRequest(
    @Size(max = 100) String name,
    String description,
    Integer displayOrder,
    Boolean isActive
) {}
```

`CategoryResponse.java`:
```java
package com.tinniestudio.api.modules.category.dto;

import com.tinniestudio.api.shared.entity.Category;
import java.util.UUID;

public record CategoryResponse(
    UUID id,
    String name,
    String slug,
    String description,
    String posterUrl,
    Integer displayOrder,
    Boolean isActive
) {
    public static CategoryResponse from(Category c) {
        return new CategoryResponse(
            c.getId(), c.getName(), c.getSlug(),
            c.getDescription(), c.getPosterUrl(),
            c.getDisplayOrder(), c.getIsActive()
        );
    }
}
```

- [ ] **Step 3: Create CategoryRepository**

```java
package com.tinniestudio.api.modules.category.repository;

import com.tinniestudio.api.shared.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByIsActiveTrueOrderByDisplayOrderAsc();
    Optional<Category> findBySlug(String slug);
    boolean existsByName(String name);
}
```

- [ ] **Step 4: Create CategoryService**

```java
package com.tinniestudio.api.modules.category.service;

import com.tinniestudio.api.modules.category.dto.CategoryResponse;
import com.tinniestudio.api.modules.category.dto.CreateCategoryRequest;
import com.tinniestudio.api.modules.category.dto.UpdateCategoryRequest;
import com.tinniestudio.api.modules.category.repository.CategoryRepository;
import com.tinniestudio.api.shared.entity.Category;
import com.tinniestudio.api.shared.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final StorageService storageService;

    @Cacheable("categories")
    public List<CategoryResponse> listActive() {
        return categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc()
                .stream().map(CategoryResponse::from).toList();
    }

    public List<CategoryResponse> listAll() {
        return categoryRepository.findAll().stream().map(CategoryResponse::from).toList();
    }

    public CategoryResponse getBySlug(String slug) {
        return categoryRepository.findBySlug(slug)
                .map(CategoryResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found: " + slug));
    }

    @Transactional
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse create(CreateCategoryRequest req, MultipartFile poster) {
        if (categoryRepository.existsByName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Category name already exists: " + req.name());
        }
        Category category = new Category();
        category.setName(req.name());
        category.setDescription(req.description());
        category.setDisplayOrder(req.displayOrder() != null ? req.displayOrder() : 0);
        if (poster != null && !poster.isEmpty()) {
            category.setPosterUrl(uploadPoster(poster, category.getName()));
        }
        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse update(UUID id, UpdateCategoryRequest req, MultipartFile poster) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found: " + id));
        if (req.name() != null)         category.setName(req.name());
        if (req.description() != null)  category.setDescription(req.description());
        if (req.displayOrder() != null) category.setDisplayOrder(req.displayOrder());
        if (req.isActive() != null)     category.setIsActive(req.isActive());
        if (poster != null && !poster.isEmpty()) {
            category.setPosterUrl(uploadPoster(poster, category.getName()));
        }
        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    @CacheEvict(value = "categories", allEntries = true)
    public void delete(UUID id) {
        if (!categoryRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found: " + id);
        }
        categoryRepository.deleteById(id);
    }

    private String uploadPoster(MultipartFile poster, String categoryName) {
        try {
            String ext = getExtension(poster.getOriginalFilename());
            String key = "posters/categories/" + categoryName.toLowerCase().replaceAll("\\s+", "-") + "." + ext;
            return storageService.uploadFile(key, poster.getBytes(), poster.getContentType());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload category poster");
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "jpg";
        return filename.substring(filename.lastIndexOf('.') + 1);
    }
}
```

- [ ] **Step 5: Run CategoryService tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.CategoryServiceTest" -i
```

Expected: all tests PASS.

- [ ] **Step 6: Create CategoryController (public endpoints)**

```java
package com.tinniestudio.api.modules.category.controller;

import com.tinniestudio.api.modules.category.dto.CategoryResponse;
import com.tinniestudio.api.modules.category.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Categories", description = "Browse content categories")
@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "List all active categories")
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> listActive() {
        return ResponseEntity.ok(categoryService.listActive());
    }

    @Operation(summary = "Get category by slug")
    @GetMapping("/{slug}")
    public ResponseEntity<CategoryResponse> getBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(categoryService.getBySlug(slug));
    }
}
```

- [ ] **Step 7: Create AdminCategoryController**

```java
package com.tinniestudio.api.modules.category.controller;

import com.tinniestudio.api.modules.category.dto.CategoryResponse;
import com.tinniestudio.api.modules.category.dto.CreateCategoryRequest;
import com.tinniestudio.api.modules.category.dto.UpdateCategoryRequest;
import com.tinniestudio.api.modules.category.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Tag(name = "Admin - Categories", description = "Manage categories")
@RestController
@RequestMapping("/admin/categories")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "List all categories including inactive")
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> listAll() {
        return ResponseEntity.ok(categoryService.listAll());
    }

    @Operation(summary = "Create category with optional poster image")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CategoryResponse> create(
            @RequestPart("request") @Valid CreateCategoryRequest request,
            @RequestPart(value = "poster", required = false) MultipartFile poster) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request, poster));
    }

    @Operation(summary = "Update category with optional new poster")
    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CategoryResponse> update(
            @PathVariable UUID id,
            @RequestPart("request") @Valid UpdateCategoryRequest request,
            @RequestPart(value = "poster", required = false) MultipartFile poster) {
        return ResponseEntity.ok(categoryService.update(id, request, poster));
    }

    @Operation(summary = "Delete category")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 8: Add public category endpoints to SecurityConfig**

In `SecurityConfig.java`, add to the `PUBLIC_ENDPOINTS` array:

```java
"/categories",
"/categories/**",
"/api/v1/categories",
"/api/v1/categories/**",
```

- [ ] **Step 9: Build to verify no compilation errors**

```bash
./gradlew :api-service:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/category/ \
        api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java \
        api-service/src/test/java/com/tinniestudio/api/modules/category/
git commit -m "feat(category): add category CRUD with multipart poster upload and Redis caching"
```

---

## Task 4: Homepage Sections Module

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/homepage/dto/CreateSectionRequest.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/homepage/dto/UpdateSectionRequest.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/homepage/dto/SectionResponse.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/homepage/repository/HomepageSectionRepository.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/homepage/service/HomepageSectionService.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/homepage/controller/HomepageSectionController.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/homepage/controller/AdminHomepageSectionController.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/homepage/service/HomepageSectionServiceTest.java`

- [ ] **Step 1: Write failing tests for HomepageSectionService**

```java
package com.tinniestudio.api.modules.homepage.service;

import com.tinniestudio.api.modules.homepage.dto.SectionResponse;
import com.tinniestudio.api.modules.homepage.repository.HomepageSectionRepository;
import com.tinniestudio.api.shared.entity.HomepageSection;
import com.tinniestudio.api.shared.entity.DomainEnums.SectionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HomepageSectionService")
class HomepageSectionServiceTest {

    @Mock private HomepageSectionRepository repository;
    @InjectMocks private HomepageSectionService service;

    @Test
    @DisplayName("listActive returns only active sections ordered by displayOrder")
    void listActiveReturnsOrderedSections() {
        HomepageSection section = new HomepageSection();
        section.setId(UUID.randomUUID());
        section.setTitle("Trending Now");
        section.setSectionType(SectionType.TRENDING);
        section.setDisplayOrder(1);
        section.setIsActive(true);

        when(repository.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(section));

        List<SectionResponse> result = service.listActive();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Trending Now");
        assertThat(result.get(0).sectionType()).isEqualTo("TRENDING");
    }
}
```

Run: `./gradlew :api-service:test --tests "*.HomepageSectionServiceTest" -i`
Expected: FAIL.

- [ ] **Step 2: Create DTOs**

`CreateSectionRequest.java`:
```java
package com.tinniestudio.api.modules.homepage.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.SectionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateSectionRequest(
    @NotBlank String title,
    @NotNull SectionType sectionType,
    UUID categoryId,
    Integer displayOrder
) {}
```

`UpdateSectionRequest.java`:
```java
package com.tinniestudio.api.modules.homepage.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.SectionType;
import java.util.UUID;

public record UpdateSectionRequest(
    String title,
    SectionType sectionType,
    UUID categoryId,
    Integer displayOrder,
    Boolean isActive
) {}
```

`SectionResponse.java`:
```java
package com.tinniestudio.api.modules.homepage.dto;

import com.tinniestudio.api.shared.entity.HomepageSection;
import java.util.UUID;

public record SectionResponse(
    UUID id,
    String title,
    String sectionType,
    UUID categoryId,
    String categorySlug,
    Integer displayOrder,
    Boolean isActive
) {
    public static SectionResponse from(HomepageSection s) {
        return new SectionResponse(
            s.getId(), s.getTitle(), s.getSectionType().name(),
            s.getCategory() != null ? s.getCategory().getId() : null,
            s.getCategory() != null ? s.getCategory().getSlug() : null,
            s.getDisplayOrder(), s.getIsActive()
        );
    }
}
```

- [ ] **Step 3: Create HomepageSectionRepository**

```java
package com.tinniestudio.api.modules.homepage.repository;

import com.tinniestudio.api.shared.entity.HomepageSection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface HomepageSectionRepository extends JpaRepository<HomepageSection, UUID> {
    List<HomepageSection> findByIsActiveTrueOrderByDisplayOrderAsc();
}
```

- [ ] **Step 4: Create HomepageSectionService**

```java
package com.tinniestudio.api.modules.homepage.service;

import com.tinniestudio.api.modules.category.repository.CategoryRepository;
import com.tinniestudio.api.modules.homepage.dto.CreateSectionRequest;
import com.tinniestudio.api.modules.homepage.dto.SectionResponse;
import com.tinniestudio.api.modules.homepage.dto.UpdateSectionRequest;
import com.tinniestudio.api.modules.homepage.repository.HomepageSectionRepository;
import com.tinniestudio.api.shared.entity.HomepageSection;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HomepageSectionService {

    private final HomepageSectionRepository repository;
    private final CategoryRepository categoryRepository;

    @Cacheable("homepage-sections")
    public List<SectionResponse> listActive() {
        return repository.findByIsActiveTrueOrderByDisplayOrderAsc()
                .stream().map(SectionResponse::from).toList();
    }

    public List<SectionResponse> listAll() {
        return repository.findAll().stream().map(SectionResponse::from).toList();
    }

    @Transactional
    @CacheEvict(value = "homepage-sections", allEntries = true)
    public SectionResponse create(CreateSectionRequest req) {
        HomepageSection section = new HomepageSection();
        section.setTitle(req.title());
        section.setSectionType(req.sectionType());
        section.setDisplayOrder(req.displayOrder() != null ? req.displayOrder() : 0);
        if (req.categoryId() != null) {
            section.setCategory(categoryRepository.findById(req.categoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found")));
        }
        return SectionResponse.from(repository.save(section));
    }

    @Transactional
    @CacheEvict(value = "homepage-sections", allEntries = true)
    public SectionResponse update(UUID id, UpdateSectionRequest req) {
        HomepageSection section = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Section not found: " + id));
        if (req.title() != null)       section.setTitle(req.title());
        if (req.sectionType() != null) section.setSectionType(req.sectionType());
        if (req.displayOrder() != null)section.setDisplayOrder(req.displayOrder());
        if (req.isActive() != null)    section.setIsActive(req.isActive());
        if (req.categoryId() != null) {
            section.setCategory(categoryRepository.findById(req.categoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found")));
        }
        return SectionResponse.from(repository.save(section));
    }

    @Transactional
    @CacheEvict(value = "homepage-sections", allEntries = true)
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Section not found: " + id);
        }
        repository.deleteById(id);
    }
}
```

- [ ] **Step 5: Run HomepageSectionService tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.HomepageSectionServiceTest" -i
```

Expected: PASS.

- [ ] **Step 6: Create HomepageSectionController (public)**

```java
package com.tinniestudio.api.modules.homepage.controller;

import com.tinniestudio.api.modules.homepage.dto.SectionResponse;
import com.tinniestudio.api.modules.homepage.service.HomepageSectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Homepage", description = "Homepage section configuration")
@RestController
@RequestMapping("/homepage-sections")
@RequiredArgsConstructor
public class HomepageSectionController {

    private final HomepageSectionService service;

    @Operation(summary = "List active homepage sections in display order")
    @GetMapping
    public ResponseEntity<List<SectionResponse>> listActive() {
        return ResponseEntity.ok(service.listActive());
    }
}
```

- [ ] **Step 7: Create AdminHomepageSectionController**

```java
package com.tinniestudio.api.modules.homepage.controller;

import com.tinniestudio.api.modules.homepage.dto.CreateSectionRequest;
import com.tinniestudio.api.modules.homepage.dto.SectionResponse;
import com.tinniestudio.api.modules.homepage.dto.UpdateSectionRequest;
import com.tinniestudio.api.modules.homepage.service.HomepageSectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Admin - Homepage", description = "Manage homepage sections")
@RestController
@RequestMapping("/admin/homepage-sections")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminHomepageSectionController {

    private final HomepageSectionService service;

    @Operation(summary = "List all homepage sections including inactive")
    @GetMapping
    public ResponseEntity<List<SectionResponse>> listAll() {
        return ResponseEntity.ok(service.listAll());
    }

    @Operation(summary = "Create a new homepage section")
    @PostMapping
    public ResponseEntity<SectionResponse> create(@Valid @RequestBody CreateSectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @Operation(summary = "Update a homepage section")
    @PatchMapping("/{id}")
    public ResponseEntity<SectionResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSectionRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @Operation(summary = "Delete a homepage section")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 8: Add public homepage-sections endpoints to SecurityConfig**

In `SecurityConfig.java`, add to `PUBLIC_ENDPOINTS`:

```java
"/homepage-sections",
"/api/v1/homepage-sections",
```

- [ ] **Step 9: Compile and run all tests**

```bash
./gradlew :api-service:test
```

Expected: all tests PASS, BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/homepage/ \
        api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java \
        api-service/src/test/java/com/tinniestudio/api/modules/homepage/
git commit -m "feat(homepage): add homepage sections module with admin CRUD and Redis caching"
```

---

## Task 5: Content Module

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/CreateContentRequest.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/UpdateContentRequest.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentResponse.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/content/dto/ContentSummaryResponse.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/content/repository/ContentRepository.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/content/repository/ContentSpecifications.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/content/service/ContentService.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/content/controller/ContentController.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/content/controller/AdminContentController.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/content/service/ContentServiceTest.java`

- [ ] **Step 1: Write failing tests for ContentService**

```java
package com.tinniestudio.api.modules.content.service;

import com.tinniestudio.api.modules.category.repository.CategoryRepository;
import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.dto.CreateContentRequest;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContentService")
class ContentServiceTest {

    @Mock private ContentRepository contentRepository;
    @Mock private CategoryRepository categoryRepository;

    @InjectMocks private ContentService contentService;

    private Content content;

    @BeforeEach
    void setUp() {
        content = new Content();
        content.setId(UUID.randomUUID());
        content.setTitle("Interstellar");
        content.setSlug("interstellar");
        content.setType(ContentType.MOVIE);
        content.setStatus(ContentStatus.PUBLISHED);
        content.setMaturityRating(MaturityRating.PG);
        content.setFeatured(false);
        content.setComingSoon(false);
        content.setViewCount(0L);
        content.setCreatedBy(UUID.randomUUID());
    }

    @Nested
    @DisplayName("list()")
    class ListTests {

        @Test
        @DisplayName("returns paginated content summaries")
        void returnsPaginatedContent() {
            Page<Content> page = new PageImpl<>(List.of(content));
            when(contentRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

            Page<ContentSummaryResponse> result = contentService.list(
                null, null, null, false, PageRequest.of(0, 20)
            );

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).title()).isEqualTo("Interstellar");
        }
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("creates content with DRAFT status and sets createdBy")
        void createsWithDraftStatus() {
            UUID creatorId = UUID.randomUUID();
            CreateContentRequest req = new CreateContentRequest(
                "Inception", ContentType.MOVIE, MaturityRating.PG_13,
                null, null, null, false, null, null
            );
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = contentService.create(req, creatorId);

            assertThat(result.title()).isEqualTo("Inception");
            assertThat(result.status()).isEqualTo("DRAFT");
        }
    }
}
```

Run: `./gradlew :api-service:test --tests "*.ContentServiceTest" -i`
Expected: FAIL.

- [ ] **Step 2: Create DTOs**

`ContentSummaryResponse.java` (list view — no cast/description):
```java
package com.tinniestudio.api.modules.content.dto;

import com.tinniestudio.api.shared.entity.Content;
import java.time.LocalDate;
import java.util.UUID;

public record ContentSummaryResponse(
    UUID id,
    String title,
    String slug,
    String shortDescription,
    String type,
    String status,
    String maturityRating,
    LocalDate releaseDate,
    Boolean featured,
    Boolean comingSoon,
    Long viewCount,
    String posterUrl,
    String thumbnailUrl
) {
    public static ContentSummaryResponse from(Content c) {
        return new ContentSummaryResponse(
            c.getId(), c.getTitle(), c.getSlug(), c.getShortDescription(),
            c.getType().name(), c.getStatus().name(), c.getMaturityRating().name(),
            c.getReleaseDate(), c.getFeatured(), c.getComingSoon(),
            c.getViewCount(), c.getPosterUrl(), c.getThumbnailUrl()
        );
    }
}
```

`ContentResponse.java` (full detail view):
```java
package com.tinniestudio.api.modules.content.dto;

import com.tinniestudio.api.shared.entity.Content;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ContentResponse(
    UUID id,
    String title,
    String slug,
    String description,
    String shortDescription,
    String type,
    String status,
    String maturityRating,
    LocalDate releaseDate,
    String language,
    String country,
    Boolean featured,
    Boolean comingSoon,
    Long viewCount,
    Integer durationSeconds,
    String posterUrl,
    String thumbnailUrl,
    List<String> categoryNames,
    Instant publishedAt
) {
    public static ContentResponse from(Content c) {
        return new ContentResponse(
            c.getId(), c.getTitle(), c.getSlug(),
            c.getDescription(), c.getShortDescription(),
            c.getType().name(), c.getStatus().name(), c.getMaturityRating().name(),
            c.getReleaseDate(), c.getLanguage(), c.getCountry(),
            c.getFeatured(), c.getComingSoon(), c.getViewCount(),
            c.getDurationSeconds(), c.getPosterUrl(), c.getThumbnailUrl(),
            c.getCategories().stream().map(cat -> cat.getName()).toList(),
            c.getPublishedAt()
        );
    }
}
```

`CreateContentRequest.java`:
```java
package com.tinniestudio.api.modules.content.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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

`UpdateContentRequest.java`:
```java
package com.tinniestudio.api.modules.content.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record UpdateContentRequest(
    String title,
    String description,
    String shortDescription,
    MaturityRating maturityRating,
    LocalDate releaseDate,
    String language,
    String country,
    Boolean comingSoon,
    Integer durationSeconds,
    String posterUrl,
    String thumbnailUrl,
    List<UUID> categoryIds
) {}
```

- [ ] **Step 3: Create ContentRepository and ContentSpecifications**

`ContentRepository.java`:
```java
package com.tinniestudio.api.modules.content.repository;

import com.tinniestudio.api.shared.entity.Content;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ContentRepository extends JpaRepository<Content, UUID>, JpaSpecificationExecutor<Content> {
    Optional<Content> findBySlug(String slug);

    @Modifying
    @Query("UPDATE Content c SET c.viewCount = c.viewCount + 1 WHERE c.id = :id")
    void incrementViewCount(@Param("id") UUID id);
}
```

`ContentSpecifications.java`:
```java
package com.tinniestudio.api.modules.content.repository;

import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

public class ContentSpecifications {

    private ContentSpecifications() {}

    public static Specification<Content> hasStatus(ContentStatus status) {
        return (root, query, cb) -> status == null ? cb.conjunction()
            : cb.equal(root.get("status"), status);
    }

    public static Specification<Content> hasType(ContentType type) {
        return (root, query, cb) -> type == null ? cb.conjunction()
            : cb.equal(root.get("type"), type);
    }

    public static Specification<Content> hasMaturityRating(MaturityRating rating) {
        return (root, query, cb) -> rating == null ? cb.conjunction()
            : cb.equal(root.get("maturityRating"), rating);
    }

    public static Specification<Content> isComingSoon(Boolean comingSoon) {
        return (root, query, cb) -> comingSoon == null ? cb.conjunction()
            : cb.equal(root.get("comingSoon"), comingSoon);
    }

    public static Specification<Content> isFeatured(Boolean featured) {
        return (root, query, cb) -> featured == null ? cb.conjunction()
            : cb.equal(root.get("featured"), featured);
    }

    public static Specification<Content> hasCategory(String categorySlug) {
        return (root, query, cb) -> {
            if (categorySlug == null) return cb.conjunction();
            var categories = root.join("categories", JoinType.INNER);
            return cb.equal(categories.get("slug"), categorySlug);
        };
    }

    public static Specification<Content> isPublished() {
        return hasStatus(ContentStatus.PUBLISHED);
    }
}
```

- [ ] **Step 4: Create ContentService**

```java
package com.tinniestudio.api.modules.content.service;

import com.tinniestudio.api.modules.category.repository.CategoryRepository;
import com.tinniestudio.api.modules.content.dto.*;
import com.tinniestudio.api.modules.content.repository.319ContentRepository;
import com.tinniestudio.api.modules.content.repository.ContentSpecifications;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentService {

    private final ContentRepository contentRepository;
    private final CategoryRepository categoryRepository;

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

    @Cacheable(value = "content-detail", key = "#slug")
    public ContentResponse getBySlug(String slug) {
        return contentRepository.findBySlug(slug)
            .map(ContentResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + slug));
    }

    @Cacheable(value = "content-detail", key = "#id")
    public ContentResponse getById(UUID id) {
        return contentRepository.findById(id)
            .map(ContentResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + id));
    }

    @Transactional
    @CacheEvict(value = {"content-list", "content-detail", "discover"}, allEntries = true)
    public ContentResponse create(CreateContentRequest req, UUID createdBy) {
        Content content = new Content();
        content.setTitle(req.title());
        content.setType(req.type());
        content.setStatus(ContentStatus.DRAFT);
        content.setMaturityRating(req.maturityRating() != null ? req.maturityRating() : MaturityRating.NOT_RATED);
        content.setDescription(req.description());
        content.setShortDescription(req.shortDescription());
        content.setReleaseDate(req.releaseDate());
        content.setComingSoon(req.comingSoon() != null ? req.comingSoon() : false);
        content.setDurationSeconds(req.durationSeconds());
        content.setCreatedBy(createdBy);
        content.setViewCount(0L);
        content.setFeatured(false);
        if (req.categoryIds() != null && !req.categoryIds().isEmpty()) {
            content.setCategories(new HashSet<>(categoryRepository.findAllById(req.categoryIds())));
        }
        return ContentResponse.from(contentRepository.save(content));
    }

    @Transactional
    @CacheEvict(value = {"content-list", "content-detail", "discover"}, allEntries = true)
    public ContentResponse update(UUID id, UpdateContentRequest req) {
        Content content = contentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + id));
        if (req.title() != null)           content.setTitle(req.title());
        if (req.description() != null)     content.setDescription(req.description());
        if (req.shortDescription() != null)content.setShortDescription(req.shortDescription());
        if (req.maturityRating() != null)  content.setMaturityRating(req.maturityRating());
        if (req.releaseDate() != null)     content.setReleaseDate(req.releaseDate());
        if (req.language() != null)        content.setLanguage(req.language());
        if (req.country() != null)         content.setCountry(req.country());
        if (req.comingSoon() != null)      content.setComingSoon(req.comingSoon());
        if (req.durationSeconds() != null) content.setDurationSeconds(req.durationSeconds());
        if (req.posterUrl() != null)       content.setPosterUrl(req.posterUrl());
        if (req.thumbnailUrl() != null)    content.setThumbnailUrl(req.thumbnailUrl());
        if (req.categoryIds() != null) {
            content.setCategories(new HashSet<>(categoryRepository.findAllById(req.categoryIds())));
        }
        return ContentResponse.from(contentRepository.save(content));
    }

    @Transactional
    @CacheEvict(value = {"content-list", "content-detail", "discover"}, allEntries = true)
    public void delete(UUID id) {
        if (!contentRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + id);
        }
        contentRepository.deleteById(id);
    }

    @Transactional
    public void transitionStatus(UUID id, ContentStatus targetStatus) {
        Content content = contentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + id));
        validateTransition(content.getStatus(), targetStatus);
        content.setStatus(targetStatus);
        if (targetStatus == ContentStatus.PUBLISHED && content.getPublishedAt() == null) {
            content.setPublishedAt(java.time.Instant.now());
        }
        contentRepository.save(content);
    }

    @Transactional
    public void toggleFeatured(UUID id) {
        Content content = contentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + id));
        content.setFeatured(!content.getFeatured());
        contentRepository.save(content);
    }

    private void validateTransition(ContentStatus current, ContentStatus target) {
        boolean valid = switch (current) {
            case DRAFT      -> target == ContentStatus.REVIEW || target == ContentStatus.ARCHIVED;
            case REVIEW     -> target == ContentStatus.PROCESSING || target == ContentStatus.REJECTED || target == ContentStatus.ARCHIVED;
            case PROCESSING -> target == ContentStatus.PUBLISHED || target == ContentStatus.ARCHIVED;
            case REJECTED   -> target == ContentStatus.DRAFT || target == ContentStatus.ARCHIVED;
            case PUBLISHED  -> target == ContentStatus.ARCHIVED;
            case ARCHIVED   -> false;
        };
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid status transition: " + current + " → " + target);
        }
    }
}
```

- [ ] **Step 5: Run ContentService tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.ContentServiceTest" -i
```

Expected: PASS.

- [ ] **Step 6: Create ContentController (public)**

```java
package com.tinniestudio.api.modules.content.controller;

import com.tinniestudio.api.modules.content.dto.ContentResponse;
import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.service.ContentService;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Content", description = "Browse and discover content")
@RestController
@RequestMapping("/contents")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;

    @Operation(summary = "List published content with optional filters (type, category, maturityRating, comingSoon)")
    @GetMapping
    public ResponseEntity<Page<ContentSummaryResponse>> list(
            @RequestParam(required = false) ContentType type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) MaturityRating maturityRating,
            @RequestParam(required = false) Boolean comingSoon,
            @PageableDefault(size = 20, sort = "publishedAt") Pageable pageable) {
        return ResponseEntity.ok(contentService.list(type, category, maturityRating, comingSoon, pageable));
    }

    @Operation(summary = "Get content detail by slug")
    @GetMapping("/{slug}")
    public ResponseEntity<ContentResponse> getBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(contentService.getBySlug(slug));
    }

    @Operation(summary = "Get content detail by id")
    @GetMapping("/id/{id}")
    public ResponseEntity<ContentResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(contentService.getById(id));
    }
}
```

- [ ] **Step 7: Create AdminContentController**

```java
package com.tinniestudio.api.modules.content.controller;

import com.tinniestudio.api.modules.content.dto.*;
import com.tinniestudio.api.modules.content.service.ContentService;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Admin - Content", description = "Manage content lifecycle")
@RestController
@RequestMapping("/admin/contents")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasRole('PARTNER')")
public class AdminContentController {

    private final ContentService contentService;

    @Operation(summary = "Create new content (starts in DRAFT)")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('PARTNER')")
    public ResponseEntity<ContentResponse> create(
            @Valid @RequestBody CreateContentRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(contentService.create(req, UUID.fromString(principal.getUsername())));
    }

    @Operation(summary = "Update content metadata")
    @PatchMapping("/{id}")
    public ResponseEntity<ContentResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateContentRequest req) {
        return ResponseEntity.ok(contentService.update(id, req));
    }

    @Operation(summary = "Delete content (admin only)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        contentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Submit content for review (DRAFT → REVIEW)")
    @PostMapping("/{id}/submit")
    public ResponseEntity<Void> submit(@PathVariable UUID id) {
        contentService.transitionStatus(id, ContentStatus.REVIEW);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Approve content for processing (REVIEW → PROCESSING) — admin only")
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> approve(@PathVariable UUID id) {
        contentService.transitionStatus(id, ContentStatus.PROCESSING);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Reject content (REVIEW → REJECTED) — admin only")
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> reject(@PathVariable UUID id) {
        contentService.transitionStatus(id, ContentStatus.REJECTED);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Publish content (PROCESSING → PUBLISHED) — admin only")
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> publish(@PathVariable UUID id) {
        contentService.transitionStatus(id, ContentStatus.PUBLISHED);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Archive content — admin only")
    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        contentService.transitionStatus(id, ContentStatus.ARCHIVED);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Toggle featured flag — admin only")
    @PatchMapping("/{id}/feature")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> toggleFeatured(@PathVariable UUID id) {
        contentService.toggleFeatured(id);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 8: Add public content endpoints to SecurityConfig**

In `SecurityConfig.java`, add to `PUBLIC_ENDPOINTS`:

```java
"/contents",
"/contents/**",
"/api/v1/contents",
"/api/v1/contents/**",
```

- [ ] **Step 9: Run all tests and verify compilation**

```bash
./gradlew :api-service:test
```

Expected: all tests PASS.

- [ ] **Step 10: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/content/ \
        api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java \
        api-service/src/test/java/com/tinniestudio/api/modules/content/
git commit -m "feat(content): add content module with CRUD, status workflow, Netflix-style filters"
```

---

*Phase 1 complete — Tasks 1–5. Continue with Phase 2 for Tasks 6–9 (Discovery, Season, Episode modules).*
