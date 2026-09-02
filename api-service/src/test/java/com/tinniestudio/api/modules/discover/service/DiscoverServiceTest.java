package com.tinniestudio.api.modules.discover.service;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.discover.dto.HomeSectionDto;
import com.tinniestudio.api.modules.homepage.repository.HomepageSectionRepository;
import com.tinniestudio.api.modules.playback.repository.WatchProgressRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.ContentType;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiscoverService")
class DiscoverServiceTest {

    @Mock private ContentRepository contentRepository;
    @Mock private HomepageSectionRepository sectionRepository;
    @Mock private WatchProgressRepository watchProgressRepository;

    @InjectMocks private DiscoverService discoverService;

    private Content publishedContent() {
        Content c = new Content();
        c.setId(UUID.randomUUID());
        c.setTitle("Interstellar");
        c.setSlug("interstellar");
        ContentType movieType = new ContentType();
        movieType.setSlug("movie");
        movieType.setStructuralKind(StructuralKind.SINGLE_VIDEO);
        c.setContentType(movieType);
        c.setStatus(ContentStatus.PUBLISHED);
        c.setMaturityRating(MaturityRating.PG);
        c.setFeatured(true);
        c.setComingSoon(false);
        c.setViewCount(1500L);
        c.setCreatedBy(UUID.randomUUID());
        c.setCategories(new java.util.HashSet<>());
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
    @DisplayName("comingSoon()")
    class ComingSoonTests {

        @Test
        @DisplayName("returns coming soon content")
        void returnsComingSoon() {
            Content cs = publishedContent();
            cs.setComingSoon(true);
            when(contentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cs)));

            List<ContentSummaryResponse> result = discoverService.comingSoon(20);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).comingSoon()).isTrue();
        }
    }

    @Nested
    @DisplayName("newReleases()")
    class NewReleasesTests {

        @Test
        @DisplayName("returns published non-coming-soon content")
        void returnsNewReleases() {
            when(contentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(publishedContent())));

            List<ContentSummaryResponse> result = discoverService.newReleases(20);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).comingSoon()).isFalse();
        }
    }

    @Nested
    @DisplayName("byCategory()")
    class ByCategoryTests {

        @Test
        @DisplayName("returns published content for given category slug")
        void returnsByCategory() {
            when(contentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(publishedContent())));

            List<ContentSummaryResponse> result = discoverService.byCategory("action", 20);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("Interstellar");
        }
    }

    @Nested
    @DisplayName("home()")
    class HomeTests {

        @Test
        @DisplayName("builds home sections from active homepage sections")
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
            assertThat(home.get(0).items()).hasSize(1);
        }

        @Test
        @DisplayName("CONTINUE_WATCHING section returns empty list")
        void continueWatchingReturnsEmpty() {
            HomepageSection section = new HomepageSection();
            section.setId(UUID.randomUUID());
            section.setTitle("Continue Watching");
            section.setSectionType(SectionType.CONTINUE_WATCHING);
            section.setIsActive(true);
            section.setDisplayOrder(1);

            when(sectionRepository.findByIsActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(section));

            List<HomeSectionDto> home = discoverService.home();

            assertThat(home).hasSize(1);
            assertThat(home.get(0).items()).isEmpty();
        }
    }

    @Nested
    @DisplayName("recommended()")
    class RecommendedTests {

        @Test
        @DisplayName("returns trending content when user has no watch history")
        void returnsTrendingWhenNoHistory() {
            UUID userId = UUID.randomUUID();

            when(watchProgressRepository.findRecentlyWatchedContentIds(eq(userId), any(Pageable.class)))
                .thenReturn(List.of());

            when(contentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(publishedContent())));

            List<ContentSummaryResponse> result = discoverService.recommended(userId);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("returns content from user's top categories, excluding already-watched")
        void returnsContentInTopCategories() {
            UUID userId = UUID.randomUUID();
            UUID watchedId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();

            Content watched = publishedContent();
            org.springframework.test.util.ReflectionTestUtils.setField(watched, "id", watchedId);

            com.tinniestudio.api.shared.entity.Category cat = new com.tinniestudio.api.shared.entity.Category();
            org.springframework.test.util.ReflectionTestUtils.setField(cat, "id", categoryId);
            cat.setName("Action");
            cat.setSlug("action");
            watched.setCategories(Set.of(cat));

            Content recommendation = publishedContent();

            when(watchProgressRepository.findRecentlyWatchedContentIds(eq(userId), any(Pageable.class)))
                .thenReturn(List.of(watchedId));
            when(contentRepository.findAllById(List.of(watchedId)))
                .thenReturn(List.of(watched));
            when(contentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(recommendation)));

            List<ContentSummaryResponse> result = discoverService.recommended(userId);

            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("caps result at 20 items")
        void capsAt20Items() {
            UUID userId = UUID.randomUUID();

            when(watchProgressRepository.findRecentlyWatchedContentIds(eq(userId), any(Pageable.class)))
                .thenReturn(List.of());

            // Return 25 items from trending — result should be capped at 20
            var manyItems = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> {
                    Content c = publishedContent();
                    org.springframework.test.util.ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
                    return c;
                })
                .toList();

            when(contentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(manyItems));

            List<ContentSummaryResponse> result = discoverService.recommended(userId);

            assertThat(result).hasSizeLessThanOrEqualTo(20);
        }
    }
}
