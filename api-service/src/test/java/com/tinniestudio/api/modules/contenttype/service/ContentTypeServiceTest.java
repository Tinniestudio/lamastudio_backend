package com.tinniestudio.api.modules.contenttype.service;

import com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse;
import com.tinniestudio.api.modules.contenttype.dto.CreateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.dto.UpdateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.shared.entity.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.StructuralKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContentTypeService")
class ContentTypeServiceTest {

    @Mock private ContentTypeRepository contentTypeRepository;
    @InjectMocks private ContentTypeService contentTypeService;

    private ContentType movie;
    private UUID movieId;

    @BeforeEach
    void setUp() {
        movieId = UUID.randomUUID();
        movie = new ContentType();
        movie.setId(movieId);
        movie.setName("Movie");
        movie.setSlug("movie");
        movie.setStructuralKind(StructuralKind.SINGLE_VIDEO);
        movie.setIsActive(true);
        movie.setDisplayOrder(0);
    }

    @Nested
    @DisplayName("listActive()")
    class ListActiveTests {
        @Test
        @DisplayName("returns only active types, ordered by displayOrder")
        void returnsActiveTypes() {
            when(contentTypeRepository.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(movie));

            List<ContentTypeResponse> result = contentTypeService.listActive();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).slug()).isEqualTo("movie");
        }
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {
        @Test
        @DisplayName("saves a new content type with the given structuralKind")
        void savesNewType() {
            CreateContentTypeRequest req = new CreateContentTypeRequest("Documentary", "A documentary film", StructuralKind.SINGLE_VIDEO, 2);
            when(contentTypeRepository.saveAndFlush(any(ContentType.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentTypeResponse result = contentTypeService.create(req);

            assertThat(result.name()).isEqualTo("Documentary");
            assertThat(result.structuralKind()).isEqualTo("SINGLE_VIDEO");
        }

        @Test
        @DisplayName("throws 409 when name already exists")
        void throwsConflictOnDuplicateName() {
            CreateContentTypeRequest req = new CreateContentTypeRequest("Movie", null, StructuralKind.SINGLE_VIDEO, 0);
            when(contentTypeRepository.saveAndFlush(any(ContentType.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

            assertThatThrownBy(() -> contentTypeService.create(req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
        }
    }

    @Nested
    @DisplayName("update()")
    class UpdateTests {
        @Test
        @DisplayName("updates only non-null fields, structuralKind stays fixed unless explicitly given")
        void updatesNonNullFields() {
            UpdateContentTypeRequest req = new UpdateContentTypeRequest(null, "Updated description", null, null, false);
            when(contentTypeRepository.findById(movieId)).thenReturn(Optional.of(movie));
            when(contentTypeRepository.save(any(ContentType.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentTypeResponse result = contentTypeService.update(movieId, req);

            assertThat(result.name()).isEqualTo("Movie"); // unchanged
            assertThat(result.isActive()).isFalse();
        }

        @Test
        @DisplayName("throws 404 when id not found")
        void throws404WhenNotFound() {
            UUID missingId = UUID.randomUUID();
            UpdateContentTypeRequest req = new UpdateContentTypeRequest("X", null, null, null, null);
            when(contentTypeRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> contentTypeService.update(missingId, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {
        @Test
        @DisplayName("throws 409 when referenced by existing content")
        void throwsConflictWhenReferenced() {
            when(contentTypeRepository.findById(movieId)).thenReturn(Optional.of(movie));
            doThrow(new DataIntegrityViolationException("fk violation"))
                .when(contentTypeRepository).flush();

            assertThatThrownBy(() -> contentTypeService.delete(movieId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
        }
    }
}
