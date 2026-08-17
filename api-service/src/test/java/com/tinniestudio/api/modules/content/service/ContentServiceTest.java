package com.tinniestudio.api.modules.content.service;

import com.tinniestudio.api.modules.category.repository.CategoryRepository;
import com.tinniestudio.api.modules.content.dto.ContentResponse;
import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.dto.CreateContentRequest;
import com.tinniestudio.api.modules.content.dto.UpdateContentRequest;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.shared.entity.Category;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContentService")
class ContentServiceTest {

    @Mock private ContentRepository contentRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private RabbitTemplate rabbitTemplate;

    @InjectMocks private ContentService contentService;

    private Content content;
    private UUID contentId;
    private UUID createdBy;

    @BeforeEach
    void setUp() {
        contentId = UUID.randomUUID();
        createdBy = UUID.randomUUID();

        content = new Content();
        content.setId(contentId);
        content.setTitle("Test Movie");
        content.setSlug("test-movie");
        content.setType(ContentType.MOVIE);
        content.setStatus(ContentStatus.DRAFT);
        content.setMaturityRating(MaturityRating.NOT_RATED);
        content.setFeatured(false);
        content.setComingSoon(false);
        content.setViewCount(0L);
        content.setCreatedBy(createdBy);
        content.setCategories(new HashSet<>());
    }

    @Nested
    @DisplayName("list()")
    class ListTests {

        @Test
        @DisplayName("returns paginated summaries of published content")
        void returnsPaginatedSummaries() {
            Page<Content> page = new PageImpl<>(List.of(content));
            when(contentRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            Page<ContentSummaryResponse> result = contentService.list(null, null, null, null, Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).title()).isEqualTo("Test Movie");
            verify(contentRepository).findAll(any(Specification.class), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("sets DRAFT status and createdBy when creating content with no categories")
        void setsDraftStatusAndCreatedBy() {
            CreateContentRequest req = new CreateContentRequest(
                "New Movie", ContentType.MOVIE, null, null, null, null, null, null, null
            );
            when(contentRepository.saveAndFlush(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.create(req, createdBy);

            assertThat(result.status()).isEqualTo("DRAFT");
            assertThat(result.type()).isEqualTo("MOVIE");
            verify(categoryRepository, never()).findAllById(any());
        }

        @Test
        @DisplayName("fetches categories from repository when categoryIds provided")
        void fetchesCategoriesWhenProvided() {
            UUID catId = UUID.randomUUID();
            Category cat = new Category();
            cat.setId(catId);
            cat.setName("Action");
            cat.setSlug("action");
            cat.setIsActive(true);

            CreateContentRequest req = new CreateContentRequest(
                "Action Movie", ContentType.MOVIE, null, null, null, null, null, null, List.of(catId)
            );
            when(categoryRepository.findAllById(List.of(catId))).thenReturn(List.of(cat));
            when(contentRepository.saveAndFlush(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.create(req, createdBy);

            assertThat(result.categoryNames()).contains("Action");
            verify(categoryRepository).findAllById(List.of(catId));
        }

        @Test
        @DisplayName("throws 409 when title slug already exists")
        void throwsConflictOnDuplicateSlug() {
            UUID creatorId = UUID.randomUUID();
            CreateContentRequest req = new CreateContentRequest(
                "Inception", ContentType.MOVIE, MaturityRating.PG_13,
                null, null, null, false, null, null
            );
            when(contentRepository.saveAndFlush(any(Content.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("slug unique violation"));

            assertThatThrownBy(() -> contentService.create(req, creatorId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
        }
    }

    @Nested
    @DisplayName("getBySlug()")
    class GetBySlugTests {

        @Test
        @DisplayName("returns response when slug exists and content is published")
        void returnsResponseWhenFound() {
            content.setStatus(ContentStatus.PUBLISHED);
            when(contentRepository.findBySlug("test-movie")).thenReturn(Optional.of(content));

            ContentResponse result = contentService.getBySlug("test-movie");

            assertThat(result.title()).isEqualTo("Test Movie");
            assertThat(result.slug()).isEqualTo("test-movie");
        }

        @Test
        @DisplayName("throws 404 when slug not found")
        void throws404WhenNotFound() {
            when(contentRepository.findBySlug("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> contentService.getBySlug("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test
        @DisplayName("throws 404 when content is not published (DRAFT/REVIEW/PROCESSING/REJECTED/ARCHIVED not publicly visible)")
        void throws404WhenNotPublished() {
            content.setStatus(ContentStatus.DRAFT);
            when(contentRepository.findBySlug("test-movie")).thenReturn(Optional.of(content));

            assertThatThrownBy(() -> contentService.getBySlug("test-movie"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("getById()")
    class GetByIdTests {

        @Test
        @DisplayName("returns response when id exists and content is published")
        void returnsResponseWhenFound() {
            content.setStatus(ContentStatus.PUBLISHED);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));

            ContentResponse result = contentService.getById(contentId);

            assertThat(result.title()).isEqualTo("Test Movie");
        }

        @Test
        @DisplayName("throws 404 when id not found")
        void throws404WhenNotFound() {
            UUID missingId = UUID.randomUUID();
            when(contentRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> contentService.getById(missingId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test
        @DisplayName("throws 404 when content is not published")
        void throws404WhenNotPublished() {
            content.setStatus(ContentStatus.REJECTED);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));

            assertThatThrownBy(() -> contentService.getById(contentId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("update()")
    class UpdateTests {

        @Test
        @DisplayName("updates only non-null fields")
        void updatesOnlyNonNullFields() {
            UpdateContentRequest req = new UpdateContentRequest(
                "Updated Title", null, null, null, null, null, null, null, null, null, null, null
            );
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.update(contentId, req);

            assertThat(result.title()).isEqualTo("Updated Title");
            assertThat(result.type()).isEqualTo("MOVIE"); // unchanged
        }

        @Test
        @DisplayName("clears all categories when categoryIds is empty list")
        void clearsCategoriesOnEmptyList() {
            // pre-populate a category on the content
            com.tinniestudio.api.shared.entity.Category existing = new com.tinniestudio.api.shared.entity.Category();
            existing.setId(UUID.randomUUID());
            existing.setName("Action");
            existing.setSlug("action");
            existing.setIsActive(true);
            existing.setDisplayOrder(1);
            content.setCategories(new java.util.HashSet<>(java.util.Set.of(existing)));

            UpdateContentRequest req = new UpdateContentRequest(
                null, null, null, null, null, null, null, null, null, null, null,
                java.util.List.of() // empty — should clear categories
            );
            when(contentRepository.findById(content.getId())).thenReturn(Optional.of(content));
            when(categoryRepository.findAllById(java.util.List.of())).thenReturn(java.util.List.of());
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.update(content.getId(), req);

            assertThat(result.categoryNames()).isEmpty();
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("soft-deletes: sets status to DELETED and deletedAt, never hard-deletes the row")
        void softDeletesWhenFound() {
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            contentService.delete(contentId);

            verify(contentRepository, never()).delete(any(Content.class));
            verify(contentRepository, never()).deleteById(any());
            ArgumentCaptor<Content> captor = ArgumentCaptor.forClass(Content.class);
            verify(contentRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ContentStatus.DELETED);
            assertThat(captor.getValue().getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws 404 when content not found")
        void throws404WhenNotFound() {
            UUID missingId = UUID.randomUUID();
            when(contentRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> contentService.delete(missingId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("transitionStatus()")
    class TransitionStatusTests {

        @Test
        @DisplayName("DRAFT to REVIEW succeeds")
        void draftToReviewSucceeds() {
            content.setStatus(ContentStatus.DRAFT);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.transitionStatus(contentId, ContentStatus.REVIEW, null);

            assertThat(result.status()).isEqualTo("REVIEW");
        }

        @Test
        @DisplayName("DRAFT to PUBLISHED throws 400 — invalid transition")
        void draftToPublishedThrows400() {
            content.setStatus(ContentStatus.DRAFT);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));

            assertThatThrownBy(() -> contentService.transitionStatus(contentId, ContentStatus.PUBLISHED, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        }

        @Test
        @DisplayName("PROCESSING to PUBLISHED sets publishedAt")
        void processingToPublishedSetsPublishedAt() {
            content.setStatus(ContentStatus.PROCESSING);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.transitionStatus(contentId, ContentStatus.PUBLISHED, null);

            assertThat(result.status()).isEqualTo("PUBLISHED");
            assertThat(result.publishedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws 400 when transitioning out of ARCHIVED state")
        void throwsOnArchivedTransition() {
            content.setStatus(ContentStatus.ARCHIVED);
            when(contentRepository.findById(content.getId())).thenReturn(Optional.of(content));

            assertThatThrownBy(() -> contentService.transitionStatus(content.getId(), ContentStatus.DRAFT, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(400));
        }

        @Test
        @DisplayName("REVIEW to REJECTED stores the rejection reason")
        void reviewToRejectedStoresReason() {
            content.setStatus(ContentStatus.REVIEW);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.transitionStatus(contentId, ContentStatus.REJECTED, "Poster violates guidelines");

            assertThat(result.status()).isEqualTo("REJECTED");
            assertThat(result.rejectionReason()).isEqualTo("Poster violates guidelines");
        }

        @Test
        @DisplayName("REJECTED to DRAFT clears the stale rejection reason")
        void rejectedToDraftClearsReason() {
            content.setStatus(ContentStatus.REJECTED);
            content.setRejectionReason("Old reason from a previous round");
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.transitionStatus(contentId, ContentStatus.DRAFT, null);

            assertThat(result.status()).isEqualTo("DRAFT");
            assertThat(result.rejectionReason()).isNull();
        }
    }

    @Nested
    @DisplayName("toggleFeatured()")
    class ToggleFeaturedTests {

        @Test
        @DisplayName("flips featured boolean from false to true")
        void flipsFeaturedBoolean() {
            content.setFeatured(false);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.toggleFeatured(contentId);

            assertThat(result.featured()).isTrue();
        }

        @Test
        @DisplayName("flips featured boolean from true to false")
        void flipsFeaturedBooleanBack() {
            content.setFeatured(true);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.toggleFeatured(contentId);

            assertThat(result.featured()).isFalse();
        }
    }

    @Nested
    @DisplayName("recordView() — Gap 3 (Batch 16 #7): anonymous view tracking")
    class RecordViewTests {

        @Test
        @DisplayName("publishes a VIEW_EVENT with userId=null for an unauthenticated (anonymous) caller")
        void anonymousCaller_publishesViewEventWithNullUserId() {
            content.setStatus(ContentStatus.PUBLISHED);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));

            contentService.recordView(contentId, null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(rabbitTemplate).convertAndSend(eq("analytics.ingest"), captor.capture());

            Map<String, Object> message = captor.getValue();
            assertThat(message.get("type")).isEqualTo("VIEW_EVENT");
            assertThat(message.get("userId")).isEqualTo("");
            assertThat(message.get("contentId")).isEqualTo(contentId.toString());
        }

        @Test
        @DisplayName("publishes a VIEW_EVENT with the real userId for an authenticated caller")
        void authenticatedCaller_publishesViewEventWithRealUserId() {
            content.setStatus(ContentStatus.PUBLISHED);
            UUID userId = UUID.randomUUID();
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));

            contentService.recordView(contentId, userId);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(rabbitTemplate).convertAndSend(eq("analytics.ingest"), captor.capture());
            assertThat(captor.getValue().get("userId")).isEqualTo(userId.toString());
        }

        @Test
        @DisplayName("throws 404 for unpublished content (no view recorded, no publish attempted)")
        void unpublishedContent_throws404_doesNotPublish() {
            content.setStatus(ContentStatus.DRAFT);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));

            assertThatThrownBy(() -> contentService.recordView(contentId, null))
                    .isInstanceOf(ResponseStatusException.class);

            verifyNoInteractions(rabbitTemplate);
        }

        @Test
        @DisplayName("does not propagate an exception when the broker publish fails (best-effort)")
        void publishFailure_isSwallowed() {
            content.setStatus(ContentStatus.PUBLISHED);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            doThrow(new RuntimeException("broker down")).when(rabbitTemplate).convertAndSend(anyString(), any(Map.class));

            // Should not throw despite the broker failure.
            contentService.recordView(contentId, null);
        }
    }
}
