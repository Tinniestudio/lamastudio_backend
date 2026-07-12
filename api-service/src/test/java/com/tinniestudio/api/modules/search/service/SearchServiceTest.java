package com.tinniestudio.api.modules.search.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.search.dto.SearchRequest;
import com.tinniestudio.api.modules.search.dto.SearchResponse;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchService")
class SearchServiceTest {

    @Mock private ContentRepository contentRepository;
    @InjectMocks private SearchServiceImpl searchService;

    private Content publishedMovie() {
        Content c = new Content();
        c.setId(UUID.randomUUID());
        c.setTitle("Interstellar");
        c.setSlug("interstellar");
        c.setType(ContentType.MOVIE);
        c.setStatus(ContentStatus.PUBLISHED);
        c.setMaturityRating(MaturityRating.PG);
        c.setFeatured(false);
        c.setComingSoon(false);
        c.setViewCount(1000L);
        c.setCreatedBy(UUID.randomUUID());
        c.setCategories(new java.util.HashSet<>());
        return c;
    }

    @Nested
    @DisplayName("search()")
    class SearchTests {

        @Test
        @DisplayName("throws 400 when query is blank")
        void throwsWhenQueryIsBlank() {
            SearchRequest req = new SearchRequest();
            req.setQ("  ");
            req.setLimit(20);
            req.setPage(0);

            assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        }

        @Test
        @DisplayName("throws 400 when query is shorter than 2 chars")
        void throwsWhenQueryTooShort() {
            SearchRequest req = new SearchRequest();
            req.setQ("a");
            req.setLimit(20);
            req.setPage(0);

            assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        }

        @Test
        @DisplayName("delegates to searchByRelevance when sort = RELEVANT")
        void delegatesToRelevance() {
            SearchRequest req = new SearchRequest();
            req.setQ("action movie");
            req.setSort(SearchRequest.SearchSort.RELEVANT);
            req.setPage(0);
            req.setLimit(20);

            when(contentRepository.searchByRelevance(
                    eq("action movie"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(publishedMovie())));

            SearchResponse resp = searchService.search(req);

            assertThat(resp.results()).hasSize(1);
            assertThat(resp.total()).isEqualTo(1L);
            assertThat(resp.page()).isEqualTo(0);
            assertThat(resp.limit()).isEqualTo(20);
            assertThat(resp.results().get(0).title()).isEqualTo("Interstellar");
        }

        @Test
        @DisplayName("delegates to searchByLatest when sort = LATEST")
        void delegatesToLatest() {
            SearchRequest req = new SearchRequest();
            req.setQ("action movie");
            req.setSort(SearchRequest.SearchSort.LATEST);
            req.setPage(0);
            req.setLimit(10);

            when(contentRepository.searchByLatest(
                    eq("action movie"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(publishedMovie())));

            SearchResponse resp = searchService.search(req);

            assertThat(resp.results()).hasSize(1);
            verify(contentRepository).searchByLatest(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("delegates to searchByPopular when sort = POPULAR")
        void delegatesToPopular() {
            SearchRequest req = new SearchRequest();
            req.setQ("action movie");
            req.setSort(SearchRequest.SearchSort.POPULAR);
            req.setPage(0);
            req.setLimit(10);

            when(contentRepository.searchByPopular(
                    eq("action movie"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(publishedMovie())));

            SearchResponse resp = searchService.search(req);

            assertThat(resp.results()).hasSize(1);
            verify(contentRepository).searchByPopular(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("passes type filter as string to native query")
        void passesTypeFilter() {
            SearchRequest req = new SearchRequest();
            req.setQ("interstellar");
            req.setType(ContentType.MOVIE);
            req.setSort(SearchRequest.SearchSort.RELEVANT);
            req.setPage(0);
            req.setLimit(20);

            when(contentRepository.searchByRelevance(
                    eq("interstellar"), eq("MOVIE"), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(publishedMovie())));

            SearchResponse resp = searchService.search(req);

            assertThat(resp.results()).hasSize(1);
        }

        @Test
        @DisplayName("returns empty results and zero total when no matches")
        void returnsEmptyWhenNoMatches() {
            SearchRequest req = new SearchRequest();
            req.setQ("xyzzy");
            req.setSort(SearchRequest.SearchSort.RELEVANT);
            req.setPage(0);
            req.setLimit(20);

            when(contentRepository.searchByRelevance(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

            SearchResponse resp = searchService.search(req);

            assertThat(resp.results()).isEmpty();
            assertThat(resp.total()).isEqualTo(0L);
            assertThat(resp.totalPages()).isEqualTo(0);
        }
    }
}
