package com.tinniestudio.api.modules.category.service;

import com.tinniestudio.api.modules.category.dto.CategoryResponse;
import com.tinniestudio.api.modules.category.dto.CreateCategoryRequest;
import com.tinniestudio.api.modules.category.dto.UpdateCategoryRequest;
import com.tinniestudio.api.modules.category.repository.CategoryRepository;
import com.tinniestudio.api.shared.entity.Category;
import com.tinniestudio.api.shared.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryService")
class CategoryServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private StorageService storageService;

    @InjectMocks private CategoryService categoryService;

    private Category category;

    @BeforeEach
    void setUp() {
        category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Action");
        category.setSlug("action");
        category.setIsActive(true);
        category.setDisplayOrder(1);
    }

    @Nested
    @DisplayName("listActive()")
    class ListActiveTests {

        @Test
        @DisplayName("returns only active categories ordered by displayOrder")
        void returnsActiveCategories() {
            when(categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(category));

            List<CategoryResponse> result = categoryService.listActive();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("Action");
            assertThat(result.get(0).slug()).isEqualTo("action");
        }
    }

    @Nested
    @DisplayName("getBySlug()")
    class GetBySlugTests {

        @Test
        @DisplayName("returns category when slug exists")
        void returnsCategory() {
            when(categoryRepository.findBySlug("action")).thenReturn(Optional.of(category));

            CategoryResponse result = categoryService.getBySlug("action");

            assertThat(result.name()).isEqualTo("Action");
        }

        @Test
        @DisplayName("throws 404 when slug not found")
        void throws404WhenNotFound() {
            when(categoryRepository.findBySlug("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> categoryService.getBySlug("missing"))
                .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("saves category with poster upload when poster provided")
        void savesWithPoster() throws Exception {
            CreateCategoryRequest req = new CreateCategoryRequest("Horror", "Scary stuff", 6);
            MockMultipartFile poster = new MockMultipartFile(
                "poster", "horror.jpg", "image/jpeg", new byte[]{1, 2, 3}
            );

            when(storageService.uploadFile(anyString(), any(), eq("image/jpeg")))
                .thenReturn("http://localhost:9000/tinniestudio/posters/categories/horror.jpg");
            when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

            CategoryResponse result = categoryService.create(req, poster);

            assertThat(result.name()).isEqualTo("Horror");
            assertThat(result.posterUrl()).contains("horror");
            verify(storageService).uploadFile(anyString(), eq(new byte[]{1, 2, 3}), eq("image/jpeg"));
        }

        @Test
        @DisplayName("saves category without poster when poster is null")
        void savesWithoutPoster() {
            CreateCategoryRequest req = new CreateCategoryRequest("Drama", null, 2);
            when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

            CategoryResponse result = categoryService.create(req, null);

            assertThat(result.name()).isEqualTo("Drama");
            assertThat(result.posterUrl()).isNull();
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("throws 409 when DB unique constraint is violated")
        void throwsConflictOnDuplicateName() {
            CreateCategoryRequest req = new CreateCategoryRequest("Action", null, 1);
            when(categoryRepository.save(any(Category.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

            assertThatThrownBy(() -> categoryService.create(req, null))
                .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("update()")
    class UpdateTests {

        @Test
        @DisplayName("updates only provided fields")
        void updatesPartialFields() {
            UpdateCategoryRequest req = new UpdateCategoryRequest(null, "Updated description", null, null);
            when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
            when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

            CategoryResponse result = categoryService.update(category.getId(), req, null);

            assertThat(result.name()).isEqualTo("Action"); // unchanged
            assertThat(result.description()).isEqualTo("Updated description");
        }

        @Test
        @DisplayName("updates poster URL when poster provided")
        void updatesPoster() throws Exception {
            UpdateCategoryRequest req = new UpdateCategoryRequest(null, null, null, null);
            MockMultipartFile poster = new MockMultipartFile(
                "poster", "action-new.jpg", "image/jpeg", new byte[]{4, 5, 6}
            );
            when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
            when(storageService.uploadFile(anyString(), any(), anyString()))
                .thenReturn("http://localhost:9000/tinniestudio/posters/categories/action-new.jpg");
            when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

            CategoryResponse result = categoryService.update(category.getId(), req, poster);

            assertThat(result.posterUrl()).contains("action-new");
            verify(storageService).uploadFile(anyString(), eq(new byte[]{4, 5, 6}), eq("image/jpeg"));
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("throws 404 when category not found")
        void throws404WhenNotFound() {
            UUID id = UUID.randomUUID();
            when(categoryRepository.existsById(id)).thenReturn(false);

            assertThatThrownBy(() -> categoryService.delete(id))
                .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("deletes category when found")
        void deletesWhenFound() {
            when(categoryRepository.existsById(category.getId())).thenReturn(true);

            categoryService.delete(category.getId());

            verify(categoryRepository).deleteById(category.getId());
        }
    }
}
