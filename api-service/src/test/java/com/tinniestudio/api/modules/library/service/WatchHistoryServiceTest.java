package com.tinniestudio.api.modules.library.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.library.dto.WatchHistoryResponse;
import com.tinniestudio.api.modules.library.repository.WatchHistoryRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import com.tinniestudio.api.shared.entity.WatchHistory;
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
class WatchHistoryServiceTest {

    @Mock
    private WatchHistoryRepository historyRepo;

    @Mock
    private ContentRepository contentRepo;

    @InjectMocks
    private WatchHistoryServiceImpl watchHistoryService;

    private UUID userId;
    private UUID contentId;
    private Content content;

    @BeforeEach
    void setUp() {
        userId    = UUID.randomUUID();
        contentId = UUID.randomUUID();

        content = new Content();
        ReflectionTestUtils.setField(content, "id", contentId);
        content.setTitle("Test Movie");
        content.setSlug("test-movie");
        content.setType(ContentType.MOVIE);
        content.setStatus(ContentStatus.PUBLISHED);
        content.setMaturityRating(MaturityRating.PG);
        content.setFeatured(false);
        content.setComingSoon(false);
        content.setViewCount(0L);
        content.setCreatedBy(UUID.randomUUID());
    }

    // ─── list() ──────────────────────────────────────────────────────────────

    @Nested
    class list {

        @Test
        @DisplayName("list: returns Page<WatchHistoryResponse> with content details batch-loaded")
        void list_returnsMappedPageWithContentDetails() {
            UUID historyId = UUID.randomUUID();
            WatchHistory entry = new WatchHistory();
            ReflectionTestUtils.setField(entry, "id", historyId);
            entry.setUserId(userId);
            entry.setContentId(contentId);
            entry.setProgressSeconds(300);
            entry.setDurationSeconds(3600);
            entry.setDeviceType("WEB");
            entry.setWatchedAt(Instant.now());

            Pageable pageable = PageRequest.of(0, 10);
            Page<WatchHistory> historyPage = new PageImpl<>(List.of(entry), pageable, 1);

            when(historyRepo.findByUserIdOrderByWatchedAtDesc(userId, pageable)).thenReturn(historyPage);
            when(contentRepo.findAllById(any())).thenReturn(List.of(content));

            Page<WatchHistoryResponse> result = watchHistoryService.list(userId, pageable);

            assertThat(result).hasSize(1);
            WatchHistoryResponse response = result.getContent().get(0);
            assertThat(response.id()).isEqualTo(historyId);
            assertThat(response.contentId()).isEqualTo(contentId);
            assertThat(response.progressSeconds()).isEqualTo(300);
            assertThat(response.deviceType()).isEqualTo("WEB");
            assertThat(response.content()).isNotNull();
            assertThat(response.content().title()).isEqualTo("Test Movie");
        }

        @Test
        @DisplayName("list: returns null content when content not found in batch")
        void list_returnsNullContentWhenNotBatchLoaded() {
            UUID historyId = UUID.randomUUID();
            UUID missingContentId = UUID.randomUUID();
            WatchHistory entry = new WatchHistory();
            ReflectionTestUtils.setField(entry, "id", historyId);
            entry.setUserId(userId);
            entry.setContentId(missingContentId);
            entry.setWatchedAt(Instant.now());

            Pageable pageable = PageRequest.of(0, 10);
            Page<WatchHistory> historyPage = new PageImpl<>(List.of(entry), pageable, 1);

            when(historyRepo.findByUserIdOrderByWatchedAtDesc(userId, pageable)).thenReturn(historyPage);
            when(contentRepo.findAllById(any())).thenReturn(List.of());

            Page<WatchHistoryResponse> result = watchHistoryService.list(userId, pageable);

            assertThat(result).hasSize(1);
            assertThat(result.getContent().get(0).content()).isNull();
        }
    }

    // ─── delete() ────────────────────────────────────────────────────────────

    @Nested
    class delete {

        @Test
        @DisplayName("delete: deletes entry when user owns it")
        void delete_deletesWhenOwned() {
            UUID historyId = UUID.randomUUID();
            WatchHistory entry = new WatchHistory();
            ReflectionTestUtils.setField(entry, "id", historyId);
            entry.setUserId(userId);
            entry.setContentId(contentId);

            when(historyRepo.findByIdAndUserId(historyId, userId)).thenReturn(Optional.of(entry));

            watchHistoryService.delete(userId, historyId);

            verify(historyRepo).delete(entry);
        }

        @Test
        @DisplayName("delete: throws 404 NOT_FOUND when entry not found or not owned by user")
        void delete_throwsNotFoundWhenMissing() {
            UUID historyId = UUID.randomUUID();
            when(historyRepo.findByIdAndUserId(historyId, userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> watchHistoryService.delete(userId, historyId))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));

            verify(historyRepo, never()).delete(any());
        }
    }

    // ─── deleteAll() ─────────────────────────────────────────────────────────

    @Nested
    class deleteAll {

        @Test
        @DisplayName("deleteAll: calls deleteAllByUserId with the given userId")
        void deleteAll_callsRepositoryMethod() {
            watchHistoryService.deleteAll(userId);

            verify(historyRepo).deleteAllByUserId(userId);
        }
    }
}
