package com.tinniestudio.api.modules.library.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.library.dto.FavoriteResponse;
import com.tinniestudio.api.modules.library.repository.FavoriteRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import com.tinniestudio.api.shared.entity.Favorite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.*;

@ExtendWith(MockitoExtension.class)
class FavoriteServiceTest {

    @Mock
    private FavoriteRepository favoriteRepo;

    @Mock
    private ContentRepository contentRepo;

    @InjectMocks
    private FavoriteServiceImpl favoriteService;

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

    // ─── add() ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("add: saves Favorite when content exists and not already favorited")
    void add_savesWhenValid() {
        when(favoriteRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(false);
        when(favoriteRepo.countByUserId(userId)).thenReturn(0L);
        when(contentRepo.findById(contentId)).thenReturn(Optional.of(content));

        favoriteService.add(userId, contentId);

        ArgumentCaptor<Favorite> captor = ArgumentCaptor.forClass(Favorite.class);
        verify(favoriteRepo).save(captor.capture());
        Favorite saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getContentId()).isEqualTo(contentId);
    }

    @Test
    @DisplayName("add: throws 409 CONFLICT when already favorited")
    void add_throwsConflictWhenAlreadyFavorited() {
        when(favoriteRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(true);

        assertThatThrownBy(() -> favoriteService.add(userId, contentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(CONFLICT));

        verify(favoriteRepo, never()).save(any());
    }

    @Test
    @DisplayName("add: throws 400 BAD_REQUEST when user has reached 500 favorites")
    void add_throwsBadRequestWhenLimitReached() {
        when(favoriteRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(false);
        when(favoriteRepo.countByUserId(userId)).thenReturn(500L);

        assertThatThrownBy(() -> favoriteService.add(userId, contentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(BAD_REQUEST));

        verify(favoriteRepo, never()).save(any());
    }

    @Test
    @DisplayName("add: throws 404 NOT_FOUND when content does not exist")
    void add_throwsNotFoundWhenContentMissing() {
        when(favoriteRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(false);
        when(favoriteRepo.countByUserId(userId)).thenReturn(0L);
        when(contentRepo.findById(contentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> favoriteService.add(userId, contentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(NOT_FOUND));

        verify(favoriteRepo, never()).save(any());
    }

    // ─── remove() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("remove: deletes favorite when it exists")
    void remove_deletesFavoriteWhenFound() {
        Favorite fav = new Favorite();
        ReflectionTestUtils.setField(fav, "id", UUID.randomUUID());
        fav.setUserId(userId);
        fav.setContentId(contentId);

        when(favoriteRepo.findByUserIdAndContentId(userId, contentId)).thenReturn(Optional.of(fav));

        favoriteService.remove(userId, contentId);

        verify(favoriteRepo).delete(fav);
    }

    @Test
    @DisplayName("remove: throws 404 NOT_FOUND when favorite does not exist")
    void remove_throwsNotFoundWhenMissing() {
        when(favoriteRepo.findByUserIdAndContentId(userId, contentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> favoriteService.remove(userId, contentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(NOT_FOUND));

        verify(favoriteRepo, never()).delete(any());
    }

    // ─── list() ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list: returns Page<FavoriteResponse> with batch-loaded content details")
    void list_returnsMappedPageWithContentDetails() {
        Favorite fav = new Favorite();
        UUID favId = UUID.randomUUID();
        ReflectionTestUtils.setField(fav, "id", favId);
        fav.setUserId(userId);
        fav.setContentId(contentId);
        fav.setCreatedAt(Instant.now());

        Pageable pageable = PageRequest.of(0, 10);
        Page<Favorite> favPage = new PageImpl<>(List.of(fav), pageable, 1);

        when(favoriteRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)).thenReturn(favPage);
        when(contentRepo.findAllById(List.of(contentId))).thenReturn(List.of(content));

        Page<FavoriteResponse> result = favoriteService.list(userId, pageable);

        assertThat(result).hasSize(1);
        FavoriteResponse response = result.getContent().get(0);
        assertThat(response.id()).isEqualTo(favId);
        assertThat(response.contentId()).isEqualTo(contentId);
        assertThat(response.content()).isNotNull();
        assertThat(response.content().title()).isEqualTo("Test Movie");
    }

    @Test
    @DisplayName("list: returns FavoriteResponse with null content when content not found in batch")
    void list_returnsNullContentWhenNotBatchLoaded() {
        Favorite fav = new Favorite();
        UUID favId = UUID.randomUUID();
        UUID missingContentId = UUID.randomUUID();
        ReflectionTestUtils.setField(fav, "id", favId);
        fav.setUserId(userId);
        fav.setContentId(missingContentId);
        fav.setCreatedAt(Instant.now());

        Pageable pageable = PageRequest.of(0, 10);
        Page<Favorite> favPage = new PageImpl<>(List.of(fav), pageable, 1);

        when(favoriteRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)).thenReturn(favPage);
        when(contentRepo.findAllById(List.of(missingContentId))).thenReturn(List.of());

        Page<FavoriteResponse> result = favoriteService.list(userId, pageable);

        assertThat(result).hasSize(1);
        FavoriteResponse response = result.getContent().get(0);
        assertThat(response.content()).isNull();
    }
}
