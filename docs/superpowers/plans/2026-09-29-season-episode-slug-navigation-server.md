# Season/Episode Slug Navigation (Server) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `slug` column to `seasons` (computed from `seasonNumber`) and `episodes` (slugified from `title`, scoped per-season) so client-web can build and resolve real season/episode slugs instead of raw UUIDs.

**Architecture:** Both columns are Postgres-trigger-generated, mirroring the existing `set_content_slug()` pattern (`V18__add_contents.sql`) exactly — `seasons` needs no collision loop (uniqueness already guaranteed by the existing `(content_id, season_number)` constraint), `episodes` reuses the shared `slugify()` function plus the same collision-suffix loop, scoped to `season_id` instead of globally. `SeasonResponse`/`EpisodeResponse` gain the `slug` field. No new endpoints — existing list endpoints (`GET /contents/{id}/seasons`, `GET /seasons/{id}/episodes`) already return everything client-web needs once `slug` is added to the response shape.

**Tech Stack:** Spring Boot, JUnit 5 + AssertJ, Testcontainers (Postgres), Flyway.

**Covers spec:** `docs/superpowers/specs/2026-09-29-season-episode-slug-navigation-design.md` — server-repo portions only (§1). Client-web routing/component work is covered by a separate plan in the `tinniestudio-client-web` repo.

**Depends on:** none. **Blocks:** the client-web plan (needs `slug` on both DTOs to exist first).

---

## File Structure

**Create:**
- `api-service/src/main/resources/db/migration/V57__add_season_slug.sql`
- `api-service/src/main/resources/db/migration/V58__add_episode_slug.sql`
- `api-service/src/test/java/com/tinniestudio/api/modules/season/repository/SeasonSlugTriggerTest.java`
- `api-service/src/test/java/com/tinniestudio/api/modules/episode/repository/EpisodeSlugTriggerTest.java`

**Modify:**
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/Season.java` — add `slug` field
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/Episode.java` — add `slug` field
- `api-service/src/main/java/com/tinniestudio/api/modules/season/dto/SeasonResponse.java` — add `slug`
- `api-service/src/main/java/com/tinniestudio/api/modules/episode/dto/EpisodeResponse.java` — add `slug`

---

### Task 1: `seasons.slug` (computed trigger, no collision loop)

**Files:** `V57__add_season_slug.sql`, `Season.java`, `SeasonResponse.java`, `SeasonSlugTriggerTest.java`

- [ ] **Step 1: Write the failing test**

Create `api-service/src/test/java/com/tinniestudio/api/modules/season/repository/SeasonSlugTriggerTest.java`:

```java
package com.tinniestudio.api.modules.season.repository;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.MainCategory;
import com.tinniestudio.api.shared.entity.Season;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB regression test for the trg_season_slug Postgres trigger (V57) — a pure Mockito unit
 * test can't verify DB-trigger behavior since mocking SeasonRepository.save() never executes real
 * SQL. Uses @DataJpaTest (JPA slice only) rather than @SpringBootTest so this doesn't depend on
 * beans unrelated to persistence (e.g. StorageService) being wired — same rationale as
 * ContentSpecificationsTest.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureTestEntityManager
@Testcontainers
@ActiveProfiles("test")
class SeasonSlugTriggerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_season_slug_test")
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

    @Autowired private SeasonRepository seasonRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentTypeRepository contentTypeRepository;

    private Content newContent(String title) {
        ContentType movieType = contentTypeRepository.findBySlug("movie")
            .orElseThrow(() -> new IllegalStateException("V53 seed row 'movie' not found"));
        Content content = new Content();
        content.setTitle(title);
        content.setContentType(movieType);
        content.setStatus(ContentStatus.DRAFT);
        content.setMainCategory(MainCategory.TV_SHOWS);
        content.setCreatedBy(UUID.randomUUID());
        return contentRepository.saveAndFlush(content);
    }

    @Test
    void setsSlugFromSeasonNumberOnInsert() {
        Content content = newContent("Slug Test Show " + System.nanoTime());
        Season season = new Season();
        season.setContent(content);
        season.setSeasonNumber(1);
        Season saved = seasonRepository.saveAndFlush(season);

        Season reloaded = seasonRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSlug()).isEqualTo("season-1");
    }

    @Test
    void differentContentsCanEachHaveSeasonOne() {
        Content contentA = newContent("Show A " + System.nanoTime());
        Content contentB = newContent("Show B " + System.nanoTime());

        Season seasonA = new Season();
        seasonA.setContent(contentA);
        seasonA.setSeasonNumber(1);
        Season savedA = seasonRepository.saveAndFlush(seasonA);

        Season seasonB = new Season();
        seasonB.setContent(contentB);
        seasonB.setSeasonNumber(1);
        Season savedB = seasonRepository.saveAndFlush(seasonB);

        assertThat(seasonRepository.findById(savedA.getId()).orElseThrow().getSlug()).isEqualTo("season-1");
        assertThat(seasonRepository.findById(savedB.getId()).orElseThrow().getSlug()).isEqualTo("season-1");
    }

    @Test
    void updatingSeasonNumberRecomputesSlug() {
        Content content = newContent("Renumber Show " + System.nanoTime());
        Season season = new Season();
        season.setContent(content);
        season.setSeasonNumber(1);
        Season saved = seasonRepository.saveAndFlush(season);

        saved.setSeasonNumber(2);
        seasonRepository.saveAndFlush(saved);

        Season reloaded = seasonRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSlug()).isEqualTo("season-2");
    }
}
```

- [ ] **Step 2: Run to verify the new test fails**

Run: `./gradlew :api-service:test --tests SeasonSlugTriggerTest`
Expected: FAIL to compile — `Season.getSlug()` doesn't exist yet, and the migration hasn't run.

- [ ] **Step 3: Create the migration**

Create `api-service/src/main/resources/db/migration/V57__add_season_slug.sql`:

```sql
-- Computed from season_number alone — no collision loop needed, since uniqueness is already
-- guaranteed by the existing (content_id, season_number) unique constraint, and season_number is
-- the only Season field guaranteed present (title is optional and often blank).
ALTER TABLE seasons ADD COLUMN slug VARCHAR(50);

CREATE OR REPLACE FUNCTION set_season_slug() RETURNS TRIGGER AS $$
BEGIN
    NEW.slug := 'season-' || NEW.season_number::text;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_season_slug
    BEFORE INSERT OR UPDATE OF season_number ON seasons
    FOR EACH ROW EXECUTE FUNCTION set_season_slug();

-- Backfill existing rows — the trigger only fires on future INSERT/UPDATE, not retroactively.
UPDATE seasons SET slug = 'season-' || season_number::text WHERE slug IS NULL;

ALTER TABLE seasons ALTER COLUMN slug SET NOT NULL;
ALTER TABLE seasons ADD CONSTRAINT uq_seasons_content_slug UNIQUE (content_id, slug);
```

- [ ] **Step 4: Add the field to `Season.java`**

Find:
```java
  private Integer seasonNumber;

  private String title;
```

Replace with:
```java
  private Integer seasonNumber;

  /** Trigger-generated (V57, trg_season_slug) — always "season-{seasonNumber}". Never set from Java. */
  @Column(nullable = false)
  private String slug;

  private String title;
```

- [ ] **Step 5: Add the field to `SeasonResponse.java`**

Find:
```java
public record SeasonResponse(
    UUID id,
    UUID contentId,
    Integer seasonNumber,
    String title,
    String description,
    LocalDate releaseDate,
    String posterUrl,
    String thumbnailUrl,
    int episodeCount
) {
    public static SeasonResponse from(Season s) {
        return new SeasonResponse(
            s.getId(),
            s.getContent().getId(),
            s.getSeasonNumber(),
            s.getTitle(),
            s.getDescription(),
            s.getReleaseDate(),
            s.getPosterUrl(),
            s.getThumbnailUrl(),
            s.getEpisodes().size()
        );
    }
}
```

Replace with:
```java
public record SeasonResponse(
    UUID id,
    UUID contentId,
    Integer seasonNumber,
    String slug,
    String title,
    String description,
    LocalDate releaseDate,
    String posterUrl,
    String thumbnailUrl,
    int episodeCount
) {
    public static SeasonResponse from(Season s) {
        return new SeasonResponse(
            s.getId(),
            s.getContent().getId(),
            s.getSeasonNumber(),
            s.getSlug(),
            s.getTitle(),
            s.getDescription(),
            s.getReleaseDate(),
            s.getPosterUrl(),
            s.getThumbnailUrl(),
            s.getEpisodes().size()
        );
    }
}
```

- [ ] **Step 6: Run the test**

Run: `./gradlew :api-service:test --tests SeasonSlugTriggerTest`
Expected: PASS — all 3 tests.

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/resources/db/migration/V57__add_season_slug.sql api-service/src/main/java/com/tinniestudio/api/shared/entity/Season.java api-service/src/main/java/com/tinniestudio/api/modules/season/dto/SeasonResponse.java api-service/src/test/java/com/tinniestudio/api/modules/season/repository/SeasonSlugTriggerTest.java
git commit -m "feat: add trigger-generated slug to seasons"
```

---

### Task 2: `episodes.slug` (slugify + collision loop, scoped per-season)

**Files:** `V58__add_episode_slug.sql`, `Episode.java`, `EpisodeResponse.java`, `EpisodeSlugTriggerTest.java`

- [ ] **Step 1: Write the failing test**

Create `api-service/src/test/java/com/tinniestudio/api/modules/episode/repository/EpisodeSlugTriggerTest.java`:

```java
package com.tinniestudio.api.modules.episode.repository;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.MainCategory;
import com.tinniestudio.api.shared.entity.Episode;
import com.tinniestudio.api.shared.entity.Season;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB regression test for the trg_episode_slug Postgres trigger (V58) — same rationale as
 * SeasonSlugTriggerTest: a pure Mockito unit test can't exercise a DB trigger.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureTestEntityManager
@Testcontainers
@ActiveProfiles("test")
class EpisodeSlugTriggerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_episode_slug_test")
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

    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private SeasonRepository seasonRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentTypeRepository contentTypeRepository;

    private Season newSeason(int seasonNumber) {
        ContentType movieType = contentTypeRepository.findBySlug("movie")
            .orElseThrow(() -> new IllegalStateException("V53 seed row 'movie' not found"));
        Content content = new Content();
        content.setTitle("Episode Slug Test Show " + System.nanoTime());
        content.setContentType(movieType);
        content.setStatus(ContentStatus.DRAFT);
        content.setMainCategory(MainCategory.TV_SHOWS);
        content.setCreatedBy(UUID.randomUUID());
        Content savedContent = contentRepository.saveAndFlush(content);

        Season season = new Season();
        season.setContent(savedContent);
        season.setSeasonNumber(seasonNumber);
        return seasonRepository.saveAndFlush(season);
    }

    @Test
    void slugifiesTitleOnInsert() {
        Season season = newSeason(1);
        Episode episode = new Episode();
        episode.setSeason(season);
        episode.setEpisodeNumber(1);
        episode.setTitle("The Pilot Episode");
        Episode saved = episodeRepository.saveAndFlush(episode);

        Episode reloaded = episodeRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSlug()).isEqualTo("the-pilot-episode");
    }

    @Test
    void appendsSuffixOnTitleCollisionWithinSameSeason() {
        Season season = newSeason(1);

        Episode first = new Episode();
        first.setSeason(season);
        first.setEpisodeNumber(1);
        first.setTitle("Pilot");
        Episode savedFirst = episodeRepository.saveAndFlush(first);

        Episode second = new Episode();
        second.setSeason(season);
        second.setEpisodeNumber(2);
        second.setTitle("Pilot");
        Episode savedSecond = episodeRepository.saveAndFlush(second);

        assertThat(episodeRepository.findById(savedFirst.getId()).orElseThrow().getSlug()).isEqualTo("pilot");
        assertThat(episodeRepository.findById(savedSecond.getId()).orElseThrow().getSlug()).isEqualTo("pilot-2");
    }

    @Test
    void differentSeasonsCanReuseTheSameEpisodeTitleWithoutCollision() {
        Season seasonA = newSeason(1);
        Season seasonB = newSeason(1); // different content, same season number — independent scope

        Episode episodeA = new Episode();
        episodeA.setSeason(seasonA);
        episodeA.setEpisodeNumber(1);
        episodeA.setTitle("Pilot");
        Episode savedA = episodeRepository.saveAndFlush(episodeA);

        Episode episodeB = new Episode();
        episodeB.setSeason(seasonB);
        episodeB.setEpisodeNumber(1);
        episodeB.setTitle("Pilot");
        Episode savedB = episodeRepository.saveAndFlush(episodeB);

        assertThat(episodeRepository.findById(savedA.getId()).orElseThrow().getSlug()).isEqualTo("pilot");
        assertThat(episodeRepository.findById(savedB.getId()).orElseThrow().getSlug()).isEqualTo("pilot");
    }
}
```

- [ ] **Step 2: Run to verify the new test fails**

Run: `./gradlew :api-service:test --tests EpisodeSlugTriggerTest`
Expected: FAIL to compile — `Episode.getSlug()` doesn't exist yet, and the migration hasn't run.

- [ ] **Step 3: Create the migration**

Create `api-service/src/main/resources/db/migration/V58__add_episode_slug.sql`:

```sql
-- Slugified from title (required, unlike Season.title), scoped to season_id — two episodes named
-- "Pilot" in different seasons/shows can both slug to "pilot" without collision, since the full
-- URL always carries content+season context alongside the episode slug.
ALTER TABLE episodes ADD COLUMN slug VARCHAR(280);

CREATE OR REPLACE FUNCTION set_episode_slug() RETURNS TRIGGER AS $$
DECLARE
    base_slug TEXT;
    candidate TEXT;
    counter   INTEGER := 2;
BEGIN
    base_slug := slugify(NEW.title);
    candidate := base_slug;
    WHILE EXISTS (
        SELECT 1 FROM episodes
        WHERE season_id = NEW.season_id
          AND slug = candidate
          AND (TG_OP = 'INSERT' OR id != NEW.id)
    ) LOOP
        candidate := base_slug || '-' || counter;
        counter   := counter + 1;
    END LOOP;
    NEW.slug := candidate;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_episode_slug
    BEFORE INSERT OR UPDATE OF title ON episodes
    FOR EACH ROW EXECUTE FUNCTION set_episode_slug();

-- Backfill existing rows using the same base+collision logic as the trigger, processed in
-- episode_number order within each season so ties resolve deterministically (lowest-numbered
-- episode gets the bare slug, later ones get -2/-3/...).
DO $$
DECLARE
    ep        RECORD;
    base_slug TEXT;
    candidate TEXT;
    counter   INTEGER;
BEGIN
    FOR ep IN SELECT id, season_id, title FROM episodes WHERE slug IS NULL ORDER BY season_id, episode_number LOOP
        base_slug := slugify(ep.title);
        candidate := base_slug;
        counter := 2;
        WHILE EXISTS (SELECT 1 FROM episodes WHERE season_id = ep.season_id AND slug = candidate) LOOP
            candidate := base_slug || '-' || counter;
            counter := counter + 1;
        END LOOP;
        UPDATE episodes SET slug = candidate WHERE id = ep.id;
    END LOOP;
END $$;

ALTER TABLE episodes ALTER COLUMN slug SET NOT NULL;
ALTER TABLE episodes ADD CONSTRAINT uq_episodes_season_slug UNIQUE (season_id, slug);
```

- [ ] **Step 4: Add the field to `Episode.java`**

Find:
```java
  private Integer episodeNumber;

  @Column(nullable = false)
  private String title;
```

Replace with:
```java
  private Integer episodeNumber;

  /** Trigger-generated (V58, trg_episode_slug) — slugify(title), unique within the season. Never set from Java. */
  @Column(nullable = false)
  private String slug;

  @Column(nullable = false)
  private String title;
```

- [ ] **Step 5: Add the field to `EpisodeResponse.java`**

Find:
```java
public record EpisodeResponse(
    UUID id,
    UUID seasonId,
    Integer episodeNumber,
    String title,
    String description,
    LocalDate releaseDate,
    Integer durationSeconds,
    String thumbnailUrl
) {
    public static EpisodeResponse from(Episode e) {
        return new EpisodeResponse(
            e.getId(), e.getSeason().getId(), e.getEpisodeNumber(),
            e.getTitle(), e.getDescription(), e.getReleaseDate(),
            e.getDurationSeconds(), e.getThumbnailUrl()
        );
    }
}
```

Replace with:
```java
public record EpisodeResponse(
    UUID id,
    UUID seasonId,
    Integer episodeNumber,
    String slug,
    String title,
    String description,
    LocalDate releaseDate,
    Integer durationSeconds,
    String thumbnailUrl
) {
    public static EpisodeResponse from(Episode e) {
        return new EpisodeResponse(
            e.getId(), e.getSeason().getId(), e.getEpisodeNumber(), e.getSlug(),
            e.getTitle(), e.getDescription(), e.getReleaseDate(),
            e.getDurationSeconds(), e.getThumbnailUrl()
        );
    }
}
```

- [ ] **Step 6: Run the test**

Run: `./gradlew :api-service:test --tests EpisodeSlugTriggerTest`
Expected: PASS — all 3 tests.

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/resources/db/migration/V58__add_episode_slug.sql api-service/src/main/java/com/tinniestudio/api/shared/entity/Episode.java api-service/src/main/java/com/tinniestudio/api/modules/episode/dto/EpisodeResponse.java api-service/src/test/java/com/tinniestudio/api/modules/episode/repository/EpisodeSlugTriggerTest.java
git commit -m "feat: add trigger-generated slug to episodes, scoped per-season"
```

---

### Task 3: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full suite**

Run: `./gradlew :api-service:test`
Expected: `BUILD SUCCESSFUL`, modulo this repo's known pre-existing unrelated Testcontainers/environment failures (a `StorageService` test-bean wiring gap affecting a fixed set of `@SpringBootTest`-based classes — compare against a baseline run on the pre-Task-1 commit if there's any doubt about which failures are pre-existing; `SeasonSlugTriggerTest`/`EpisodeSlugTriggerTest` use `@DataJpaTest`, which doesn't hit this issue).

- [ ] **Step 2: No commit for this task** — verification only, nothing to stage.

---

## Self-Review Notes

- **Spec coverage:** §1 (season slug: computed, no collision loop; episode slug: slugified, collision loop, scoped per-season; DTOs gain `slug`; no new endpoints) — Tasks 1 & 2 cover every bullet.
- **Placeholder scan:** none — every migration is complete, runnable SQL; every Java find/replace block matches the real current file content read during planning.
- **Type consistency:** `slug` is a plain `String` on both entities and both DTOs, matching `Content.slug`'s existing type — no task treats it as anything else.
- **Ordering dependency respected:** Task 2's episode fixtures depend on Task 1's `Season.slug` existing only incidentally (both tasks construct a `Season` as a parent fixture) — no functional dependency between the two trigger implementations themselves; they could be reordered without breaking anything, but are kept in spec order for readability.
- **Environment note carried over:** this repo has a known, pre-existing `StorageService` bean-wiring gap that fails a fixed set of `@SpringBootTest`-based test classes in the current dev environment (unrelated to this work). Both new test classes here deliberately use `@DataJpaTest` instead, following `ContentSpecificationsTest`'s established precedent, specifically to avoid that issue.
