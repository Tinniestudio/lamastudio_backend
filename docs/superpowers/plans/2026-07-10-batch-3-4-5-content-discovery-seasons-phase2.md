# Combined Batch 3+4+5 — Phase 2: Discovery, Seasons & Episodes

> **Continuation of:** `2026-07-10-batch-3-4-5-content-discovery-seasons.md` (Tasks 1–5)
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Tasks 6–9 depend on Tasks 1–5 being complete.

---

## Task 6: Discovery Module

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/discover/service/DiscoverService.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/discover/dto/HomeResponse.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/discover/dto/HomeSectionDto.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/discover/controller/DiscoverController.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/discover/service/DiscoverServiceTest.java`

All files in package `com.tinniestudio.api.modules.discover.*`

- [ ] **Step 1: Write failing tests for DiscoverService**

```java
package com.tinniestudio.api.modules.discover.service;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.content.repository.ContentSpecifications;
import com.tinniestudio.api.modules.discover.dto.HomeSectionDto;
import com.tinniestudio.api.modules.homepage.repository.HomepageSectionRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.HomepageSection;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiscoverService")
class DiscoverServiceTest {

    @Mock private ContentRepository contentRepository;
    @Mock private HomepageSectionRepository sectionRepository;

    @InjectMocks private DiscoverService discoverService;

    private Content publishedContent() {
        Content c = new Content();
        c.setId(UUID.randomUUID());
        c.setTitle("Interstellar");
        c.setSlug("interstellar");
        c.setType(ContentType.MOVIE);
        c.setStatus(ContentStatus.PUBLISHED);
        c.setMaturityRating(MaturityRating.PG);
        c.setFeatured(true);
        c.setComingSoon(false);
        c.setViewCount(1500L);
        c.setCreatedBy(UUID.randomUUID());
        return c;
    }

    @Nested
    @DisplayName("trending()")
    class TrendingTests {

        @Test
        @DisplayName("returns published content sorted by view_count DESC")
        void returnsTrending() {
            when(contentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(publishedContent())));

            List<ContentSummaryResponse> result = discoverService.trending(20);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("Interstellar");
        }
    }

    @Nested
    @DisplayName("featured()")
    class FeaturedTests {

        @Test
        @DisplayName("returns featured published content")
        void returnsFeatured() {
            when(contentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(publishedContent())));

            List<ContentSummaryResponse> result = discoverService.featured(10);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).featured()).isTrue();
        }
    }

    @Nested
    @DisplayName("home()")
    class HomeTests {

        @Test
        @DisplayName("builds home response from active sections")
        void buildsHomeSections() {
            HomepageSection section = new HomepageSection();
            section.setId(UUID.randomUUID());
            section.setTitle("Trending Now");
            section.setSectionType(SectionType.TRENDING);
            section.setIsActive(true);
            section.setDisplayOrder(1);

            when(sectionRepository.findByIsActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(section));
            when(contentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(publishedContent())));

            List<HomeSectionDto> home = discoverService.home();

            assertThat(home).hasSize(1);
            assertThat(home.get(0).title()).isEqualTo("Trending Now");
            assertThat(home.get(0).sectionType()).isEqualTo("TRENDING");
        }
    }
}
```

Run: `./gradlew :api-service:test --tests "*.DiscoverServiceTest" -i`
Expected: FAIL.

- [ ] **Step 2: Create DTOs**

`HomeSectionDto.java`:
```java
package com.tinniestudio.api.modules.discover.dto;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import java.util.List;

public record HomeSectionDto(
    String title,
    String sectionType,
    String categorySlug,
    List<ContentSummaryResponse> items
) {}
```

`HomeResponse.java`:
```java
package com.tinniestudio.api.modules.discover.dto;

import java.util.List;

public record HomeResponse(List<HomeSectionDto> sections) {}
```

- [ ] **Step 3: Create DiscoverService**

```java
package com.tinniestudio.api.modules.discover.service;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.content.repository.ContentSpecifications;
import com.tinniestudio.api.modules.discover.dto.HomeSectionDto;
import com.tinniestudio.api.modules.homepage.repository.HomepageSectionRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.SectionType;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DiscoverService {

    private final ContentRepository contentRepository;
    private final HomepageSectionRepository sectionRepository;

    @Cacheable(value = "discover", key = "'trending-' + #limit")
    public List<ContentSummaryResponse> trending(int limit) {
        return contentRepository.findAll(
            ContentSpecifications.isPublished(),
            PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "viewCount"))
        ).map(ContentSummaryResponse::from).toList();
    }

    @Cacheable(value = "discover", key = "'featured-' + #limit")
    public List<ContentSummaryResponse> featured(int limit) {
        return contentRepository.findAll(
            ContentSpecifications.isPublished().and(ContentSpecifications.isFeatured(true)),
            PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "publishedAt"))
        ).map(ContentSummaryResponse::from).toList();
    }

    @Cacheable(value = "discover", key = "'new-releases-' + #limit")
    public List<ContentSummaryResponse> newReleases(int limit) {
        return contentRepository.findAll(
            ContentSpecifications.isPublished().and(ContentSpecifications.isComingSoon(false)),
            PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "publishedAt"))
        ).map(ContentSummaryResponse::from).toList();
    }

    @Cacheable(value = "discover", key = "'coming-soon-' + #limit")
    public List<ContentSummaryResponse> comingSoon(int limit) {
        return contentRepository.findAll(
            ContentSpecifications.isComingSoon(true),
            PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "releaseDate"))
        ).map(ContentSummaryResponse::from).toList();
    }

    @Cacheable(value = "discover", key = "'by-category-' + #categorySlug + '-' + #limit")
    public List<ContentSummaryResponse> byCategory(String categorySlug, int limit) {
        return contentRepository.findAll(
            ContentSpecifications.isPublished().and(ContentSpecifications.hasCategory(categorySlug)),
            PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "publishedAt"))
        ).map(ContentSummaryResponse::from).toList();
    }

    @Cacheable(value = "discover", key = "'home'")
    public List<HomeSectionDto> home() {
        return sectionRepository.findByIsActiveTrueOrderByDisplayOrderAsc()
            .stream()
            .map(section -> {
                List<ContentSummaryResponse> items = resolveSection(section.getSectionType(),
                    section.getCategory() != null ? section.getCategory().getSlug() : null, 20);
                return new HomeSectionDto(
                    section.getTitle(),
                    section.getSectionType().name(),
                    section.getCategory() != null ? section.getCategory().getSlug() : null,
                    items
                );
            })
            .toList();
    }

    private List<ContentSummaryResponse> resolveSection(SectionType type, String categorySlug, int limit) {
        return switch (type) {
            case TRENDING         -> trending(limit);
            case FEATURED         -> featured(limit);
            case NEW_RELEASES     -> newReleases(limit);
            case COMING_SOON      -> comingSoon(limit);
            case CONTINUE_WATCHING-> List.of(); // populated in Batch 8
            case CATEGORY         -> categorySlug != null ? byCategory(categorySlug, limit) : List.of();
        };
    }
}
```

- [ ] **Step 4: Run DiscoverService tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.DiscoverServiceTest" -i
```

Expected: PASS.

- [ ] **Step 5: Create DiscoverController**

```java
package com.tinniestudio.api.modules.discover.controller;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.discover.dto.HomeSectionDto;
import com.tinniestudio.api.modules.discover.service.DiscoverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Discover", description = "Discovery and curated content feeds")
@RestController
@RequestMapping("/discover")
@RequiredArgsConstructor
public class DiscoverController {

    private final DiscoverService discoverService;

    @Operation(summary = "Homepage — all active sections with their content")
    @GetMapping("/home")
    public ResponseEntity<List<HomeSectionDto>> home() {
        return ResponseEntity.ok(discoverService.home());
    }

    @Operation(summary = "Trending content (highest view count)")
    @GetMapping("/trending")
    public ResponseEntity<List<ContentSummaryResponse>> trending(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(discoverService.trending(Math.min(limit, 50)));
    }

    @Operation(summary = "Featured content")
    @GetMapping("/featured")
    public ResponseEntity<List<ContentSummaryResponse>> featured(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(discoverService.featured(Math.min(limit, 50)));
    }

    @Operation(summary = "New releases (most recently published)")
    @GetMapping("/new-releases")
    public ResponseEntity<List<ContentSummaryResponse>> newReleases(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(discoverService.newReleases(Math.min(limit, 50)));
    }

    @Operation(summary = "Coming soon content")
    @GetMapping("/coming-soon")
    public ResponseEntity<List<ContentSummaryResponse>> comingSoon(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(discoverService.comingSoon(Math.min(limit, 50)));
    }

    @Operation(summary = "Content by category slug")
    @GetMapping("/category/{slug}")
    public ResponseEntity<List<ContentSummaryResponse>> byCategory(
            @PathVariable String slug,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(discoverService.byCategory(slug, Math.min(limit, 50)));
    }
}
```

- [ ] **Step 6: Add public discover endpoints to SecurityConfig**

In `SecurityConfig.java`, add to `PUBLIC_ENDPOINTS`:

```java
"/discover",
"/discover/**",
"/api/v1/discover",
"/api/v1/discover/**",
```

- [ ] **Step 7: Run all tests**

```bash
./gradlew :api-service:test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/discover/ \
        api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java \
        api-service/src/test/java/com/tinniestudio/api/modules/discover/
git commit -m "feat(discover): add discovery endpoints — trending, featured, new-releases, coming-soon, by-category, home"
```

---

## Task 7: Season Module

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/season/dto/CreateSeasonRequest.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/season/dto/UpdateSeasonRequest.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/season/dto/SeasonResponse.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/season/repository/SeasonRepository.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/season/service/SeasonService.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/season/controller/SeasonController.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/season/controller/AdminSeasonController.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/season/service/SeasonServiceTest.java`

- [ ] **Step 1: Write failing tests for SeasonService**

```java
package com.tinniestudio.api.modules.season.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.season.dto.CreateSeasonRequest;
import com.tinniestudio.api.modules.season.dto.SeasonResponse;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.Season;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SeasonService")
class SeasonServiceTest {

    @Mock private SeasonRepository seasonRepository;
    @Mock private ContentRepository contentRepository;

    @InjectMocks private SeasonService seasonService;

    private Content seriesContent;

    @BeforeEach
    void setUp() {
        seriesContent = new Content();
        seriesContent.setId(UUID.randomUUID());
        seriesContent.setTitle("Breaking Bad");
        seriesContent.setType(ContentType.SERIES);
        seriesContent.setStatus(ContentStatus.DRAFT);
        seriesContent.setCreatedBy(UUID.randomUUID());
        seriesContent.setComingSoon(false);
        seriesContent.setFeatured(false);
        seriesContent.setViewCount(0L);
        seriesContent.setMaturityRating(MaturityRating.NOT_RATED);
    }

    @Nested
    @DisplayName("listByContent()")
    class ListTests {

        @Test
        @DisplayName("returns seasons for given contentId in order")
        void returnsSeasonsInOrder() {
            Season season = new Season();
            season.setId(UUID.randomUUID());
            season.setSeasonNumber(1);
            season.setContent(seriesContent);

            when(seasonRepository.findByContentIdOrderBySeasonNumberAsc(seriesContent.getId()))
                .thenReturn(List.of(season));

            List<SeasonResponse> result = seasonService.listByContent(seriesContent.getId());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).seasonNumber()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("creates season with next available season number when not specified")
        void createsWithAutoNumber() {
            CreateSeasonRequest req = new CreateSeasonRequest(null, "Season 1", null, null, null, null);

            when(contentRepository.findById(seriesContent.getId())).thenReturn(Optional.of(seriesContent));
            when(seasonRepository.findMaxSeasonNumberByContentId(seriesContent.getId())).thenReturn(Optional.of(0));
            when(seasonRepository.save(any(Season.class))).thenAnswer(inv -> {
                Season s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            SeasonResponse result = seasonService.create(seriesContent.getId(), req);

            assertThat(result.seasonNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("throws 409 when season number already exists")
        void throwsConflictOnDuplicateNumber() {
            CreateSeasonRequest req = new CreateSeasonRequest(1, "Season 1 dup", null, null, null, null);
            when(contentRepository.findById(seriesContent.getId())).thenReturn(Optional.of(seriesContent));
            when(seasonRepository.existsByContentIdAndSeasonNumber(seriesContent.getId(), 1)).thenReturn(true);

            assertThatThrownBy(() -> seasonService.create(seriesContent.getId(), req))
                .hasMessageContaining("Season number 1 already exists");
        }
    }
}
```

Run: `./gradlew :api-service:test --tests "*.SeasonServiceTest" -i`
Expected: FAIL.

- [ ] **Step 2: Create DTOs**

`CreateSeasonRequest.java`:
```java
package com.tinniestudio.api.modules.season.dto;

import java.time.LocalDate;

public record CreateSeasonRequest(
    Integer seasonNumber,
    String title,
    String description,
    LocalDate releaseDate,
    String posterUrl,
    String thumbnailUrl
) {}
```

`UpdateSeasonRequest.java`:
```java
package com.tinniestudio.api.modules.season.dto;

import java.time.LocalDate;

public record UpdateSeasonRequest(
    String title,
    String description,
    LocalDate releaseDate,
    String posterUrl,
    String thumbnailUrl
) {}
```

`SeasonResponse.java`:
```java
package com.tinniestudio.api.modules.season.dto;

import com.tinniestudio.api.shared.entity.Season;
import java.time.LocalDate;
import java.util.UUID;

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

- [ ] **Step 3: Create SeasonRepository**

```java
package com.tinniestudio.api.modules.season.repository;

import com.tinniestudio.api.shared.entity.Season;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeasonRepository extends JpaRepository<Season, UUID> {
    List<Season> findByContentIdOrderBySeasonNumberAsc(UUID contentId);
    boolean existsByContentIdAndSeasonNumber(UUID contentId, int seasonNumber);

    @Query("SELECT MAX(s.seasonNumber) FROM Season s WHERE s.content.id = :contentId")
    Optional<Integer> findMaxSeasonNumberByContentId(@Param("contentId") UUID contentId);
}
```

- [ ] **Step 4: Create SeasonService**

```java
package com.tinniestudio.api.modules.season.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.season.dto.CreateSeasonRequest;
import com.tinniestudio.api.modules.season.dto.SeasonResponse;
import com.tinniestudio.api.modules.season.dto.UpdateSeasonRequest;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.Season;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SeasonService {

    private final SeasonRepository seasonRepository;
    private final ContentRepository contentRepository;

    public List<SeasonResponse> listByContent(UUID contentId) {
        return seasonRepository.findByContentIdOrderBySeasonNumberAsc(contentId)
                .stream().map(SeasonResponse::from).toList();
    }

    public SeasonResponse getById(UUID id) {
        return seasonRepository.findById(id)
            .map(SeasonResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + id));
    }

    @Transactional
    public SeasonResponse create(UUID contentId, CreateSeasonRequest req) {
        Content content = contentRepository.findById(contentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + contentId));

        int seasonNumber;
        if (req.seasonNumber() != null) {
            if (seasonRepository.existsByContentIdAndSeasonNumber(contentId, req.seasonNumber())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Season number " + req.seasonNumber() + " already exists for this content");
            }
            seasonNumber = req.seasonNumber();
        } else {
            seasonNumber = seasonRepository.findMaxSeasonNumberByContentId(contentId).orElse(0) + 1;
        }

        Season season = new Season();
        season.setContent(content);
        season.setSeasonNumber(seasonNumber);
        season.setTitle(req.title());
        season.setDescription(req.description());
        season.setReleaseDate(req.releaseDate());
        season.setPosterUrl(req.posterUrl());
        season.setThumbnailUrl(req.thumbnailUrl());
        return SeasonResponse.from(seasonRepository.save(season));
    }

    @Transactional
    public SeasonResponse update(UUID id, UpdateSeasonRequest req) {
        Season season = seasonRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + id));
        if (req.title() != null)        season.setTitle(req.title());
        if (req.description() != null)  season.setDescription(req.description());
        if (req.releaseDate() != null)  season.setReleaseDate(req.releaseDate());
        if (req.posterUrl() != null)    season.setPosterUrl(req.posterUrl());
        if (req.thumbnailUrl() != null) season.setThumbnailUrl(req.thumbnailUrl());
        return SeasonResponse.from(seasonRepository.save(season));
    }

    @Transactional
    public void delete(UUID id) {
        if (!seasonRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + id);
        }
        seasonRepository.deleteById(id);
    }
}
```

- [ ] **Step 5: Run SeasonService tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.SeasonServiceTest" -i
```

Expected: PASS.

- [ ] **Step 6: Create SeasonController (public)**

```java
package com.tinniestudio.api.modules.season.controller;

import com.tinniestudio.api.modules.season.dto.SeasonResponse;
import com.tinniestudio.api.modules.season.service.SeasonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Seasons", description = "Browse series seasons")
@RestController
@RequestMapping("/contents/{contentId}/seasons")
@RequiredArgsConstructor
public class SeasonController {

    private final SeasonService seasonService;

    @Operation(summary = "List all seasons for a series content")
    @GetMapping
    public ResponseEntity<List<SeasonResponse>> list(@PathVariable UUID contentId) {
        return ResponseEntity.ok(seasonService.listByContent(contentId));
    }

    @Operation(summary = "Get a specific season")
    @GetMapping("/{id}")
    public ResponseEntity<SeasonResponse> get(@PathVariable UUID contentId, @PathVariable UUID id) {
        return ResponseEntity.ok(seasonService.getById(id));
    }
}
```

- [ ] **Step 7: Create AdminSeasonController**

```java
package com.tinniestudio.api.modules.season.controller;

import com.tinniestudio.api.modules.season.dto.CreateSeasonRequest;
import com.tinniestudio.api.modules.season.dto.SeasonResponse;
import com.tinniestudio.api.modules.season.dto.UpdateSeasonRequest;
import com.tinniestudio.api.modules.season.service.SeasonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Admin - Seasons", description = "Manage series seasons")
@RestController
@RequestMapping("/admin/contents/{contentId}/seasons")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasRole('PARTNER')")
public class AdminSeasonController {

    private final SeasonService seasonService;

    @Operation(summary = "Create a season (auto-numbered if seasonNumber omitted)")
    @PostMapping
    public ResponseEntity<SeasonResponse> create(
            @PathVariable UUID contentId,
            @RequestBody CreateSeasonRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(seasonService.create(contentId, req));
    }

    @Operation(summary = "Update season metadata")
    @PatchMapping("/{id}")
    public ResponseEntity<SeasonResponse> update(
            @PathVariable UUID contentId,
            @PathVariable UUID id,
            @RequestBody UpdateSeasonRequest req) {
        return ResponseEntity.ok(seasonService.update(id, req));
    }

    @Operation(summary = "Delete a season and all its episodes")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID contentId, @PathVariable UUID id) {
        seasonService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 8: Add public season endpoints to SecurityConfig**

In `SecurityConfig.java`, add to `PUBLIC_ENDPOINTS`:

```java
"/contents/*/seasons",
"/contents/*/seasons/**",
"/api/v1/contents/*/seasons",
"/api/v1/contents/*/seasons/**",
```

- [ ] **Step 9: Run all tests**

```bash
./gradlew :api-service:test
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/season/ \
        api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java \
        api-service/src/test/java/com/tinniestudio/api/modules/season/
git commit -m "feat(season): add season module with auto-numbering and partner/admin CRUD"
```

---

## Task 8: Episode Module

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/episode/dto/CreateEpisodeRequest.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/episode/dto/UpdateEpisodeRequest.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/episode/dto/EpisodeResponse.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/episode/dto/ReorderEpisodesRequest.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/episode/repository/EpisodeRepository.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/episode/service/EpisodeService.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/episode/controller/EpisodeController.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/episode/controller/AdminEpisodeController.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/Episode.java` — add durationSeconds (already in entity? check first)
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/episode/service/EpisodeServiceTest.java`

Note: `Episode.java` already has `durationSeconds` in the plan but NOT in the current entity file. Add it now during this task.

- [ ] **Step 1: Add durationSeconds to Episode entity**

In `Episode.java`, add:

```java
private Integer durationSeconds;
```

- [ ] **Step 2: Write failing tests for EpisodeService**

```java
package com.tinniestudio.api.modules.episode.service;

import com.tinniestudio.api.modules.episode.dto.CreateEpisodeRequest;
import com.tinniestudio.api.modules.episode.dto.EpisodeResponse;
import com.tinniestudio.api.modules.episode.dto.ReorderEpisodesRequest;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.shared.entity.Episode;
import com.tinniestudio.api.shared.entity.Season;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EpisodeService")
class EpisodeServiceTest {

    @Mock private EpisodeRepository episodeRepository;
    @Mock private SeasonRepository seasonRepository;

    @InjectMocks private EpisodeService episodeService;

    private Season season;

    @BeforeEach
    void setUp() {
        Content content = new Content();
        content.setId(UUID.randomUUID());
        content.setTitle("Breaking Bad");
        content.setType(ContentType.SERIES);
        content.setStatus(ContentStatus.DRAFT);
        content.setCreatedBy(UUID.randomUUID());
        content.setComingSoon(false);
        content.setFeatured(false);
        content.setViewCount(0L);
        content.setMaturityRating(MaturityRating.NOT_RATED);

        season = new Season();
        season.setId(UUID.randomUUID());
        season.setSeasonNumber(1);
        season.setContent(content);
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("auto-numbers episode when episodeNumber not specified")
        void autoNumbersEpisode() {
            CreateEpisodeRequest req = new CreateEpisodeRequest(null, "Pilot", null, null, null, null);

            when(seasonRepository.findById(season.getId())).thenReturn(Optional.of(season));
            when(episodeRepository.findMaxEpisodeNumberBySeasonId(season.getId())).thenReturn(Optional.of(0));
            when(episodeRepository.save(any(Episode.class))).thenAnswer(inv -> {
                Episode ep = inv.getArgument(0);
                ep.setId(UUID.randomUUID());
                return ep;
            });

            EpisodeResponse result = episodeService.create(season.getId(), req);

            assertThat(result.episodeNumber()).isEqualTo(1);
            assertThat(result.title()).isEqualTo("Pilot");
        }

        @Test
        @DisplayName("throws 409 when episode number already exists in season")
        void throwsConflictOnDuplicateNumber() {
            CreateEpisodeRequest req = new CreateEpisodeRequest(1, "Duplicate", null, null, null, null);
            when(seasonRepository.findById(season.getId())).thenReturn(Optional.of(season));
            when(episodeRepository.existsBySeasonIdAndEpisodeNumber(season.getId(), 1)).thenReturn(true);

            assertThatThrownBy(() -> episodeService.create(season.getId(), req))
                .hasMessageContaining("Episode number 1 already exists");
        }
    }

    @Nested
    @DisplayName("reorder()")
    class ReorderTests {

        @Test
        @DisplayName("reassigns episode numbers according to provided order")
        void reassignsNumbers() {
            UUID ep1Id = UUID.randomUUID();
            UUID ep2Id = UUID.randomUUID();

            Episode ep1 = new Episode();
            ep1.setId(ep1Id);
            ep1.setEpisodeNumber(2);
            ep1.setTitle("Ep A");
            ep1.setSeason(season);

            Episode ep2 = new Episode();
            ep2.setId(ep2Id);
            ep2.setEpisodeNumber(1);
            ep2.setTitle("Ep B");
            ep2.setSeason(season);

            when(episodeRepository.findById(ep1Id)).thenReturn(Optional.of(ep1));
            when(episodeRepository.findById(ep2Id)).thenReturn(Optional.of(ep2));
            when(episodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            episodeService.reorder(season.getId(), new ReorderEpisodesRequest(List.of(ep1Id, ep2Id)));

            verify(episodeRepository, times(2)).save(any(Episode.class));
            assertThat(ep1.getEpisodeNumber()).isEqualTo(1);
            assertThat(ep2.getEpisodeNumber()).isEqualTo(2);
        }
    }
}
```

Run: `./gradlew :api-service:test --tests "*.EpisodeServiceTest" -i`
Expected: FAIL.

- [ ] **Step 3: Create DTOs**

`CreateEpisodeRequest.java`:
```java
package com.tinniestudio.api.modules.episode.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record CreateEpisodeRequest(
    Integer episodeNumber,
    @NotBlank String title,
    String description,
    LocalDate releaseDate,
    Integer durationSeconds,
    String thumbnailUrl
) {}
```

`UpdateEpisodeRequest.java`:
```java
package com.tinniestudio.api.modules.episode.dto;

import java.time.LocalDate;

public record UpdateEpisodeRequest(
    String title,
    String description,
    LocalDate releaseDate,
    Integer durationSeconds,
    String thumbnailUrl
) {}
```

`EpisodeResponse.java`:
```java
package com.tinniestudio.api.modules.episode.dto;

import com.tinniestudio.api.shared.entity.Episode;
import java.time.LocalDate;
import java.util.UUID;

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

`ReorderEpisodesRequest.java`:
```java
package com.tinniestudio.api.modules.episode.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record ReorderEpisodesRequest(
    @NotEmpty List<UUID> episodeIds
) {}
```

- [ ] **Step 4: Create EpisodeRepository**

```java
package com.tinniestudio.api.modules.episode.repository;

import com.tinniestudio.api.shared.entity.Episode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EpisodeRepository extends JpaRepository<Episode, UUID> {
    List<Episode> findBySeasonIdOrderByEpisodeNumberAsc(UUID seasonId);
    boolean existsBySeasonIdAndEpisodeNumber(UUID seasonId, int episodeNumber);

    @Query("SELECT MAX(e.episodeNumber) FROM Episode e WHERE e.season.id = :seasonId")
    Optional<Integer> findMaxEpisodeNumberBySeasonId(@Param("seasonId") UUID seasonId);
}
```

- [ ] **Step 5: Create EpisodeService**

```java
package com.tinniestudio.api.modules.episode.service;

import com.tinniestudio.api.modules.episode.dto.*;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.shared.entity.Episode;
import com.tinniestudio.api.shared.entity.Season;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class EpisodeService {

    private final EpisodeRepository episodeRepository;
    private final SeasonRepository seasonRepository;

    public List<EpisodeResponse> listBySeason(UUID seasonId) {
        return episodeRepository.findBySeasonIdOrderByEpisodeNumberAsc(seasonId)
                .stream().map(EpisodeResponse::from).toList();
    }

    public EpisodeResponse getById(UUID id) {
        return episodeRepository.findById(id)
            .map(EpisodeResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id));
    }

    @Transactional
    public EpisodeResponse create(UUID seasonId, CreateEpisodeRequest req) {
        Season season = seasonRepository.findById(seasonId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + seasonId));

        int episodeNumber;
        if (req.episodeNumber() != null) {
            if (episodeRepository.existsBySeasonIdAndEpisodeNumber(seasonId, req.episodeNumber())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Episode number " + req.episodeNumber() + " already exists in this season");
            }
            episodeNumber = req.episodeNumber();
        } else {
            episodeNumber = episodeRepository.findMaxEpisodeNumberBySeasonId(seasonId).orElse(0) + 1;
        }

        Episode episode = new Episode();
        episode.setSeason(season);
        episode.setEpisodeNumber(episodeNumber);
        episode.setTitle(req.title());
        episode.setDescription(req.description());
        episode.setReleaseDate(req.releaseDate());
        episode.setDurationSeconds(req.durationSeconds());
        episode.setThumbnailUrl(req.thumbnailUrl());
        return EpisodeResponse.from(episodeRepository.save(episode));
    }

    @Transactional
    public EpisodeResponse update(UUID id, UpdateEpisodeRequest req) {
        Episode episode = episodeRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id));
        if (req.title() != null)           episode.setTitle(req.title());
        if (req.description() != null)     episode.setDescription(req.description());
        if (req.releaseDate() != null)     episode.setReleaseDate(req.releaseDate());
        if (req.durationSeconds() != null) episode.setDurationSeconds(req.durationSeconds());
        if (req.thumbnailUrl() != null)    episode.setThumbnailUrl(req.thumbnailUrl());
        return EpisodeResponse.from(episodeRepository.save(episode));
    }

    @Transactional
    public void delete(UUID id) {
        if (!episodeRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id);
        }
        episodeRepository.deleteById(id);
    }

    /**
     * Reassigns episode numbers 1..N in the provided order.
     * Uses a temporary negative offset to avoid unique constraint conflicts mid-reorder.
     */
    @Transactional
    public void reorder(UUID seasonId, ReorderEpisodesRequest req) {
        List<UUID> orderedIds = req.episodeIds();
        AtomicInteger tempOffset = new AtomicInteger(-orderedIds.size() * 1000);

        // Phase 1: move to temp negative numbers to avoid collisions
        for (UUID epId : orderedIds) {
            Episode ep = episodeRepository.findById(epId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + epId));
            if (!ep.getSeason().getId().equals(seasonId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Episode " + epId + " does not belong to season " + seasonId);
            }
            ep.setEpisodeNumber(tempOffset.getAndIncrement());
            episodeRepository.save(ep);
        }

        // Phase 2: assign final 1..N order
        int number = 1;
        for (UUID epId : orderedIds) {
            Episode ep = episodeRepository.findById(epId).orElseThrow();
            ep.setEpisodeNumber(number++);
            episodeRepository.save(ep);
        }
    }
}
```

- [ ] **Step 6: Run EpisodeService tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.EpisodeServiceTest" -i
```

Expected: PASS.

- [ ] **Step 7: Create EpisodeController (public)**

```java
package com.tinniestudio.api.modules.episode.controller;

import com.tinniestudio.api.modules.episode.dto.EpisodeResponse;
import com.tinniestudio.api.modules.episode.service.EpisodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Episodes", description = "Browse season episodes")
@RestController
@RequestMapping("/seasons/{seasonId}/episodes")
@RequiredArgsConstructor
public class EpisodeController {

    private final EpisodeService episodeService;

    @Operation(summary = "List all episodes for a season in episode order")
    @GetMapping
    public ResponseEntity<List<EpisodeResponse>> list(@PathVariable UUID seasonId) {
        return ResponseEntity.ok(episodeService.listBySeason(seasonId));
    }

    @Operation(summary = "Get a specific episode")
    @GetMapping("/{id}")
    public ResponseEntity<EpisodeResponse> get(@PathVariable UUID seasonId, @PathVariable UUID id) {
        return ResponseEntity.ok(episodeService.getById(id));
    }
}
```

- [ ] **Step 8: Create AdminEpisodeController**

```java
package com.tinniestudio.api.modules.episode.controller;

import com.tinniestudio.api.modules.episode.dto.*;
import com.tinniestudio.api.modules.episode.service.EpisodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Admin - Episodes", description = "Manage season episodes")
@RestController
@RequestMapping("/admin/seasons/{seasonId}/episodes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasRole('PARTNER')")
public class AdminEpisodeController {

    private final EpisodeService episodeService;

    @Operation(summary = "Create episode (auto-numbered if episodeNumber omitted)")
    @PostMapping
    public ResponseEntity<EpisodeResponse> create(
            @PathVariable UUID seasonId,
            @Valid @RequestBody CreateEpisodeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(episodeService.create(seasonId, req));
    }

    @Operation(summary = "Update episode metadata")
    @PatchMapping("/{id}")
    public ResponseEntity<EpisodeResponse> update(
            @PathVariable UUID seasonId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEpisodeRequest req) {
        return ResponseEntity.ok(episodeService.update(id, req));
    }

    @Operation(summary = "Delete an episode")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID seasonId, @PathVariable UUID id) {
        episodeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reorder episodes — provide episodeIds in desired 1..N order")
    @PatchMapping("/reorder")
    public ResponseEntity<Void> reorder(
            @PathVariable UUID seasonId,
            @Valid @RequestBody ReorderEpisodesRequest req) {
        episodeService.reorder(seasonId, req);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 9: Add public episode endpoints to SecurityConfig**

In `SecurityConfig.java`, add to `PUBLIC_ENDPOINTS`:

```java
"/seasons/*/episodes",
"/seasons/*/episodes/**",
"/api/v1/seasons/*/episodes",
"/api/v1/seasons/*/episodes/**",
```

- [ ] **Step 10: Run full test suite**

```bash
./gradlew :api-service:test
```

Expected: all tests PASS, BUILD SUCCESSFUL.

- [ ] **Step 11: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/episode/ \
        api-service/src/main/java/com/tinniestudio/api/shared/entity/Episode.java \
        api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java \
        api-service/src/test/java/com/tinniestudio/api/modules/episode/
git commit -m "feat(episode): add episode module with auto-numbering and drag-and-drop reorder"
```

---

## Final Build Verification

- [ ] **Run full test suite**

```bash
./gradlew :api-service:test
```

Expected: all tests PASS.

- [ ] **Compile check**

```bash
./gradlew :api-service:compileJava
```

Expected: BUILD SUCCESSFUL, zero warnings on new code.

- [ ] **Final commit if needed**

If any uncommitted changes remain after the above tasks:

```bash
git status
git add -p   # review and stage selectively
git commit -m "chore: finalize batch 3-4-5 combined implementation"
```

---

## Architecture Rules to Enforce (Reviewers Check These)

1. **No `S3Client` / `S3Presigner` / `SdkException` imports** outside `MinioStorageService` and `StorageServiceConfig`.
2. **No `@Value`** in new classes — use `@ConfigurationProperties` or constructor injection.
3. **All public content GET endpoints** accessible without auth (in `PUBLIC_ENDPOINTS`): `/categories/**`, `/contents/**`, `/discover/**`, `/contents/*/seasons/**`, `/seasons/*/episodes/**`, `/homepage-sections`.
4. **Admin endpoints** (`/admin/**`) require `hasRole('ADMIN')` or `hasRole('PARTNER')` via `@PreAuthorize`.
5. **Status transitions** go through `ContentService.transitionStatus()` — never set `content.setStatus()` directly in a controller.
6. **Slug assignment** is entirely driven by the DB trigger — never set `category.setSlug()` or `content.setSlug()` in Java code.
7. **Cache eviction** on every write method in `CategoryService`, `HomepageSectionService`, `ContentService` — no stale reads after mutations.
8. **`uploadFile` return value** is the public URL (`endpoint/bucket/key`) — callers must not construct URLs themselves.
