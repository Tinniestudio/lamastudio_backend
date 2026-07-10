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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
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
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

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
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.create(req, createdBy);

            assertThat(result.categoryNames()).contains("Action");
            verify(categoryRepository).findAllById(List.of(catId));
        }
    }

    @Nested
    @DisplayName("getBySlug()")
    class GetBySlugTests {

        @Test
        @DisplayName("returns response when slug exists")
        void returnsResponseWhenFound() {
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
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("calls repository.delete(entity) when content found")
        void deletesWhenFound() {
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));

            contentService.delete(contentId);

            verify(contentRepository).delete(content);
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

            ContentResponse result = contentService.transitionStatus(contentId, ContentStatus.REVIEW);

            assertThat(result.status()).isEqualTo("REVIEW");
        }

        @Test
        @DisplayName("DRAFT to PUBLISHED throws 400 — invalid transition")
        void draftToPublishedThrows400() {
            content.setStatus(ContentStatus.DRAFT);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));

            assertThatThrownBy(() -> contentService.transitionStatus(contentId, ContentStatus.PUBLISHED))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        }

        @Test
        @DisplayName("PROCESSING to PUBLISHED sets publishedAt")
        void processingToPublishedSetsPublishedAt() {
            content.setStatus(ContentStatus.PROCESSING);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.transitionStatus(contentId, ContentStatus.PUBLISHED);

            assertThat(result.status()).isEqualTo("PUBLISHED");
            assertThat(result.publishedAt()).isNotNull();
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
}
