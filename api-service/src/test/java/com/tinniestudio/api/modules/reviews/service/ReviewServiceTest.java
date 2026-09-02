package com.tinniestudio.api.modules.reviews.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.reviews.dto.CreateReviewRequest;
import com.tinniestudio.api.modules.reviews.dto.ReviewResponse;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewRequest;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewStatusRequest;
import com.tinniestudio.api.modules.reviews.repository.ReviewRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.ContentReview;
import com.tinniestudio.api.shared.entity.DomainEnums.ReviewStatus;
import com.tinniestudio.api.shared.entity.User;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepo;

    @Mock
    private ContentRepository contentRepo;

    @Mock
    private UserRepository userRepo;

    @InjectMocks
    private ReviewServiceImpl reviewService;

    private UUID userId;
    private UUID contentId;
    private UUID reviewId;

    @BeforeEach
    void setUp() {
        userId    = UUID.randomUUID();
        contentId = UUID.randomUUID();
        reviewId  = UUID.randomUUID();
    }

    // ─── list() ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list: returns Page<ReviewResponse> of APPROVED reviews")
    void list_returnsApprovedReviews() {
        ContentReview review = new ContentReview();
        ReflectionTestUtils.setField(review, "id", reviewId);
        review.setUserId(userId);
        review.setContentId(contentId);
        review.setRating((short) 4);
        review.setBody("Great content!");
        review.setStatus(ReviewStatus.APPROVED);

        Pageable pageable = PageRequest.of(0, 10);
        Page<ContentReview> page = new PageImpl<>(List.of(review), pageable, 1);

        when(reviewRepo.findByContentIdAndStatusOrderByCreatedAtDesc(contentId, ReviewStatus.APPROVED, pageable))
                .thenReturn(page);

        Page<ReviewResponse> result = reviewService.list(contentId, pageable);

        assertThat(result).hasSize(1);
        ReviewResponse resp = result.getContent().get(0);
        assertThat(resp.id()).isEqualTo(reviewId);
        assertThat(resp.userId()).isEqualTo(userId);
        assertThat(resp.contentId()).isEqualTo(contentId);
        assertThat(resp.rating()).isEqualTo((short) 4);
        assertThat(resp.body()).isEqualTo("Great content!");
        assertThat(resp.status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("list: populates author displayName/avatarUrl from a batch user lookup")
    void list_populatesAuthorFromBatchLookup() {
        ContentReview review = new ContentReview();
        ReflectionTestUtils.setField(review, "id", reviewId);
        review.setUserId(userId);
        review.setContentId(contentId);
        review.setRating((short) 4);
        review.setStatus(ReviewStatus.APPROVED);

        User author = new User();
        ReflectionTestUtils.setField(author, "id", userId);
        author.setDisplayName("Jane D.");
        author.setAvatarUrl("avatars/jane.jpg");

        Pageable pageable = PageRequest.of(0, 10);
        Page<ContentReview> page = new PageImpl<>(List.of(review), pageable, 1);

        when(reviewRepo.findByContentIdAndStatusOrderByCreatedAtDesc(contentId, ReviewStatus.APPROVED, pageable))
                .thenReturn(page);
        when(userRepo.findAllById(List.of(userId))).thenReturn(List.of(author));

        Page<ReviewResponse> result = reviewService.list(contentId, pageable);

        assertThat(result.getContent().get(0).author().displayName()).isEqualTo("Jane D.");
        assertThat(result.getContent().get(0).author().avatarUrl()).isEqualTo("avatars/jane.jpg");
    }

    @Test
    @DisplayName("list: falls back to a generic label when the author has no displayName or firstName")
    void list_fallsBackToGenericLabelWhenNoName() {
        ContentReview review = new ContentReview();
        ReflectionTestUtils.setField(review, "id", reviewId);
        review.setUserId(userId);
        review.setContentId(contentId);
        review.setRating((short) 3);
        review.setStatus(ReviewStatus.APPROVED);

        User author = new User();
        ReflectionTestUtils.setField(author, "id", userId);
        // displayName and firstName both left null

        Pageable pageable = PageRequest.of(0, 10);
        Page<ContentReview> page = new PageImpl<>(List.of(review), pageable, 1);

        when(reviewRepo.findByContentIdAndStatusOrderByCreatedAtDesc(contentId, ReviewStatus.APPROVED, pageable))
                .thenReturn(page);
        when(userRepo.findAllById(List.of(userId))).thenReturn(List.of(author));

        Page<ReviewResponse> result = reviewService.list(contentId, pageable);

        assertThat(result.getContent().get(0).author().displayName()).isEqualTo("Member");
    }

    @Test
    @DisplayName("create: leaves author null — mutation responses are always about the caller's own review")
    void create_leavesAuthorNull() {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating((short) 5);

        when(contentRepo.existsById(contentId)).thenReturn(true);
        when(reviewRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(false);

        ContentReview savedReview = new ContentReview();
        ReflectionTestUtils.setField(savedReview, "id", reviewId);
        savedReview.setUserId(userId);
        savedReview.setContentId(contentId);
        savedReview.setRating((short) 5);
        savedReview.setStatus(ReviewStatus.APPROVED);

        when(reviewRepo.saveAndFlush(any(ContentReview.class))).thenReturn(savedReview);

        ReviewResponse result = reviewService.create(userId, contentId, request);

        assertThat(result.author()).isNull();
        verifyNoInteractions(userRepo);
    }

    // ─── create() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create: saves review with APPROVED status and returns response")
    void create_savesReviewWithCorrectFields() {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating((short) 5);
        request.setBody("Excellent!");

        when(contentRepo.existsById(contentId)).thenReturn(true);
        when(reviewRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(false);

        ContentReview savedReview = new ContentReview();
        ReflectionTestUtils.setField(savedReview, "id", reviewId);
        savedReview.setUserId(userId);
        savedReview.setContentId(contentId);
        savedReview.setRating((short) 5);
        savedReview.setBody("Excellent!");
        savedReview.setStatus(ReviewStatus.APPROVED);

        when(reviewRepo.saveAndFlush(any(ContentReview.class))).thenReturn(savedReview);

        ReviewResponse result = reviewService.create(userId, contentId, request);

        ArgumentCaptor<ContentReview> captor = ArgumentCaptor.forClass(ContentReview.class);
        verify(reviewRepo).saveAndFlush(captor.capture());
        ContentReview captured = captor.getValue();
        assertThat(captured.getUserId()).isEqualTo(userId);
        assertThat(captured.getContentId()).isEqualTo(contentId);
        assertThat(captured.getRating()).isEqualTo((short) 5);
        assertThat(captured.getBody()).isEqualTo("Excellent!");
        assertThat(captured.getStatus()).isEqualTo(ReviewStatus.APPROVED);

        assertThat(result.id()).isEqualTo(reviewId);
        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("create: throws 409 CONFLICT when user already reviewed this content")
    void create_throwsConflictOnDuplicate() {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating((short) 3);

        when(contentRepo.existsById(contentId)).thenReturn(true);
        when(reviewRepo.existsByUserIdAndContentId(userId, contentId)).thenReturn(true);

        assertThatThrownBy(() -> reviewService.create(userId, contentId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(CONFLICT));

        verify(reviewRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("create: throws 404 NOT_FOUND when content does not exist")
    void create_throwsNotFoundWhenContentMissing() {
        CreateReviewRequest request = new CreateReviewRequest();
        request.setRating((short) 3);

        when(contentRepo.existsById(contentId)).thenReturn(false);

        assertThatThrownBy(() -> reviewService.create(userId, contentId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(NOT_FOUND));

        verify(reviewRepo, never()).saveAndFlush(any());
    }

    // ─── update() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("update: applies non-null fields and saves when user owns the review")
    void update_updatesFieldsWhenOwned() {
        ContentReview existing = new ContentReview();
        ReflectionTestUtils.setField(existing, "id", reviewId);
        existing.setUserId(userId);
        existing.setContentId(contentId);
        existing.setRating((short) 2);
        existing.setBody("Okay");
        existing.setStatus(ReviewStatus.APPROVED);

        UpdateReviewRequest request = new UpdateReviewRequest();
        request.setRating((short) 4);
        request.setBody("Better than expected");

        when(reviewRepo.findByIdAndUserId(reviewId, userId)).thenReturn(Optional.of(existing));
        when(reviewRepo.save(any(ContentReview.class))).thenReturn(existing);

        ReviewResponse result = reviewService.update(userId, reviewId, request);

        assertThat(existing.getRating()).isEqualTo((short) 4);
        assertThat(existing.getBody()).isEqualTo("Better than expected");
        assertThat(existing.getStatus()).isEqualTo(ReviewStatus.PENDING);
        verify(reviewRepo).save(existing);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("update: only updates non-null fields (partial update)")
    void update_appliesOnlyNonNullFields() {
        ContentReview existing = new ContentReview();
        ReflectionTestUtils.setField(existing, "id", reviewId);
        existing.setUserId(userId);
        existing.setContentId(contentId);
        existing.setRating((short) 3);
        existing.setBody("Original body");
        existing.setStatus(ReviewStatus.APPROVED);

        UpdateReviewRequest request = new UpdateReviewRequest();
        request.setRating((short) 5);
        // body is null — should not overwrite

        when(reviewRepo.findByIdAndUserId(reviewId, userId)).thenReturn(Optional.of(existing));
        when(reviewRepo.save(any(ContentReview.class))).thenReturn(existing);

        reviewService.update(userId, reviewId, request);

        assertThat(existing.getRating()).isEqualTo((short) 5);
        assertThat(existing.getBody()).isEqualTo("Original body");
        assertThat(existing.getStatus()).isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    @DisplayName("update: throws 404 NOT_FOUND when review is not owned by the user")
    void update_throwsNotFoundWhenNotOwned() {
        UpdateReviewRequest request = new UpdateReviewRequest();
        request.setRating((short) 1);

        when(reviewRepo.findByIdAndUserId(reviewId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.update(userId, reviewId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(NOT_FOUND));

        verify(reviewRepo, never()).save(any());
    }

    // ─── delete() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete: deletes the review when the user owns it")
    void delete_deletesWhenOwned() {
        ContentReview existing = new ContentReview();
        ReflectionTestUtils.setField(existing, "id", reviewId);
        existing.setUserId(userId);

        when(reviewRepo.findByIdAndUserId(reviewId, userId)).thenReturn(Optional.of(existing));

        reviewService.delete(userId, reviewId);

        verify(reviewRepo).delete(existing);
    }

    @Test
    @DisplayName("delete: throws 404 NOT_FOUND when review not owned by user")
    void delete_throwsNotFoundWhenNotOwned() {
        when(reviewRepo.findByIdAndUserId(reviewId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.delete(userId, reviewId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(NOT_FOUND));

        verify(reviewRepo, never()).delete(any());
    }

    // ─── moderateStatus() ────────────────────────────────────────────────────

    @Test
    @DisplayName("moderateStatus: changes status and saves when review exists")
    void moderateStatus_changesStatusWhenFound() {
        ContentReview existing = new ContentReview();
        ReflectionTestUtils.setField(existing, "id", reviewId);
        existing.setUserId(userId);
        existing.setContentId(contentId);
        existing.setRating((short) 3);
        existing.setStatus(ReviewStatus.PENDING);

        UpdateReviewStatusRequest request = new UpdateReviewStatusRequest();
        request.setStatus(ReviewStatus.APPROVED);

        when(reviewRepo.findById(reviewId)).thenReturn(Optional.of(existing));
        when(reviewRepo.save(any(ContentReview.class))).thenReturn(existing);

        ReviewResponse result = reviewService.moderateStatus(reviewId, request);

        assertThat(existing.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        verify(reviewRepo).save(existing);
        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("moderateStatus: throws 404 NOT_FOUND when review does not exist")
    void moderateStatus_throwsNotFoundWhenMissing() {
        UpdateReviewStatusRequest request = new UpdateReviewStatusRequest();
        request.setStatus(ReviewStatus.REJECTED);

        when(reviewRepo.findById(reviewId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.moderateStatus(reviewId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(NOT_FOUND));

        verify(reviewRepo, never()).save(any());
    }

    // ─── getMine() ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getMine: returns the review regardless of its status when it exists")
    void getMine_returnsReviewOfAnyStatus() {
        ContentReview pending = new ContentReview();
        ReflectionTestUtils.setField(pending, "id", reviewId);
        pending.setUserId(userId);
        pending.setContentId(contentId);
        pending.setRating((short) 4);
        pending.setStatus(ReviewStatus.PENDING);

        when(reviewRepo.findByUserIdAndContentId(userId, contentId)).thenReturn(Optional.of(pending));

        ReviewResponse result = reviewService.getMine(userId, contentId);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.author()).isNull();
    }

    @Test
    @DisplayName("getMine: throws 404 when the user has no review on this content")
    void getMine_throwsNotFoundWhenNoneExists() {
        when(reviewRepo.findByUserIdAndContentId(userId, contentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.getMine(userId, contentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(NOT_FOUND));
    }
}
