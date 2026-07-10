package com.tinniestudio.api.modules.homepage.service;

import com.tinniestudio.api.modules.category.repository.CategoryRepository;
import com.tinniestudio.api.modules.homepage.dto.CreateSectionRequest;
import com.tinniestudio.api.modules.homepage.dto.SectionResponse;
import com.tinniestudio.api.modules.homepage.dto.UpdateSectionRequest;
import com.tinniestudio.api.modules.homepage.repository.HomepageSectionRepository;
import com.tinniestudio.api.shared.entity.Category;
import com.tinniestudio.api.shared.entity.HomepageSection;
import com.tinniestudio.api.shared.entity.DomainEnums.SectionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HomepageSectionService")
class HomepageSectionServiceTest {

    @Mock private HomepageSectionRepository repository;
    @Mock private CategoryRepository categoryRepository;

    @InjectMocks private HomepageSectionService service;

    private HomepageSection section;

    @BeforeEach
    void setUp() {
        section = new HomepageSection();
        section.setId(UUID.randomUUID());
        section.setTitle("Trending Now");
        section.setSectionType(SectionType.TRENDING);
        section.setDisplayOrder(1);
        section.setIsActive(true);
    }

    @Nested
    @DisplayName("listActive()")
    class ListActiveTests {

        @Test
        @DisplayName("returns only active sections ordered by displayOrder")
        void returnsActiveSections() {
            when(repository.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(section));

            List<SectionResponse> result = service.listActive();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("Trending Now");
            assertThat(result.get(0).sectionType()).isEqualTo("TRENDING");
        }
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("creates section with sectionType and displayOrder")
        void createsSection() {
            CreateSectionRequest req = new CreateSectionRequest("Featured", SectionType.FEATURED, null, 2);
            when(repository.save(any(HomepageSection.class))).thenAnswer(inv -> {
                HomepageSection s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            SectionResponse result = service.create(req);

            assertThat(result.title()).isEqualTo("Featured");
            assertThat(result.sectionType()).isEqualTo("FEATURED");
            assertThat(result.displayOrder()).isEqualTo(2);
        }

        @Test
        @DisplayName("throws 404 when categoryId provided but not found")
        void throws404WhenCategoryNotFound() {
            UUID catId = UUID.randomUUID();
            CreateSectionRequest req = new CreateSectionRequest("Genre", SectionType.CATEGORY, catId, 5);
            when(categoryRepository.findById(catId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("creates section and sets category when valid categoryId provided")
        void createsSectionWithCategory() {
            UUID catId = UUID.randomUUID();
            Category category = new Category();
            category.setId(catId);
            category.setName("Action");
            category.setSlug("action");
            category.setIsActive(true);
            category.setDisplayOrder(1);

            CreateSectionRequest req = new CreateSectionRequest("Action Row", SectionType.CATEGORY, catId, 3);
            when(categoryRepository.findById(catId)).thenReturn(Optional.of(category));
            when(repository.save(any(HomepageSection.class))).thenAnswer(inv -> {
                HomepageSection s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                return s;
            });

            SectionResponse result = service.create(req);

            assertThat(result.categorySlug()).isEqualTo("action");
            assertThat(result.sectionType()).isEqualTo("CATEGORY");
        }
    }

    @Nested
    @DisplayName("update()")
    class UpdateTests {

        @Test
        @DisplayName("updates only provided fields")
        void updatesPartialFields() {
            UpdateSectionRequest req = new UpdateSectionRequest(null, null, null, null, 3, false);
            when(repository.findById(section.getId())).thenReturn(Optional.of(section));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SectionResponse result = service.update(section.getId(), req);

            assertThat(result.displayOrder()).isEqualTo(3);
            assertThat(result.isActive()).isFalse();
            assertThat(result.title()).isEqualTo("Trending Now"); // unchanged
        }

        @Test
        @DisplayName("throws 404 when section not found")
        void throws404WhenNotFound() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(id, new UpdateSectionRequest(null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("sets category when valid categoryId provided")
        void updatesSetsCategory() {
            UUID catId = UUID.randomUUID();
            Category category = new Category();
            category.setId(catId);
            category.setName("Drama");
            category.setSlug("drama");
            category.setIsActive(true);
            category.setDisplayOrder(2);

            UpdateSectionRequest req = new UpdateSectionRequest(null, null, catId, null, null, null);
            when(repository.findById(section.getId())).thenReturn(Optional.of(section));
            when(categoryRepository.findById(catId)).thenReturn(Optional.of(category));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SectionResponse result = service.update(section.getId(), req);

            assertThat(result.categorySlug()).isEqualTo("drama");
        }

        @Test
        @DisplayName("detaches category when removeCategory is true")
        void detachesCategory() {
            section.setCategory(null);
            UpdateSectionRequest req = new UpdateSectionRequest(null, null, null, true, null, null);
            when(repository.findById(section.getId())).thenReturn(Optional.of(section));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SectionResponse result = service.update(section.getId(), req);

            assertThat(result.categoryId()).isNull();
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("deletes section when found")
        void deletesSection() {
            when(repository.findById(section.getId())).thenReturn(Optional.of(section));

            service.delete(section.getId());

            verify(repository).delete(section);
        }

        @Test
        @DisplayName("throws 404 when section not found")
        void throws404WhenNotFound() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Nested
    @DisplayName("listAll()")
    class ListAllTests {

        @Test
        @DisplayName("returns all sections including inactive")
        void returnsAllSections() {
            section.setIsActive(false);
            when(repository.findAll()).thenReturn(List.of(section));

            List<SectionResponse> result = service.listAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).isActive()).isFalse();
        }
    }
}
