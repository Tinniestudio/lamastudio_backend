package com.tinniestudio.api.modules.content.repository;

import com.tinniestudio.api.modules.category.repository.CategoryRepository;
import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.shared.entity.Category;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB regression test for {@link ContentSpecifications#hasCategories(List)} — the AND-join
 * logic (one INNER JOIN per slug) that a pure Mockito unit test can't verify, since mocking
 * {@code ContentRepository.findAll(Specification, Pageable)} never actually evaluates the
 * Specification against real data. {@code ContentServiceTest} only proves {@code list()}
 * delegates through the repository without choking on commas; this is the test that proves the
 * join itself produces AND semantics, not OR or a Cartesian-product duplicate-row bug.
 *
 * <p>Uses {@code @DataJpaTest} (JPA slice only) rather than {@code @SpringBootTest} so this test
 * doesn't depend on beans unrelated to persistence (e.g. {@code StorageService}) being wired.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureTestEntityManager
@Testcontainers
@ActiveProfiles("test")
class ContentSpecificationsTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_specs_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentTypeRepository contentTypeRepository;
    @Autowired private CategoryRepository categoryRepository;

    private Category sermons;
    private Category bibleStudy;
    private Category worship;

    private Content bothTags;
    private Content oneTagOnly;
    private Content neitherTag;

    @BeforeEach
    void setUp() {
        ContentType movieType = contentTypeRepository.findBySlug("movie")
            .orElseThrow(() -> new IllegalStateException("V53 seed row 'movie' not found"));

        sermons = saveCategory("Sermons " + System.nanoTime());
        bibleStudy = saveCategory("Bible Study " + System.nanoTime());
        worship = saveCategory("Worship " + System.nanoTime());

        bothTags = saveContent("Both Tags", movieType, Set.of(sermons, bibleStudy));
        oneTagOnly = saveContent("One Tag Only", movieType, Set.of(sermons));
        neitherTag = saveContent("Neither Tag", movieType, Set.of(worship));
    }

    private Category saveCategory(String name) {
        Category category = new Category();
        category.setName(name);
        category.setSlug(name.toLowerCase().replace(" ", "-"));
        category.setIsActive(true);
        category.setDisplayOrder(0);
        return categoryRepository.saveAndFlush(category);
    }

    private Content saveContent(String title, ContentType contentType, Set<Category> categories) {
        Content content = new Content();
        content.setTitle(title);
        content.setSlug(title.toLowerCase().replace(" ", "-") + "-" + System.nanoTime());
        content.setContentType(contentType);
        content.setStatus(ContentStatus.PUBLISHED);
        content.setMaturityRating(MaturityRating.PG);
        content.setComingSoon(false);
        content.setFeatured(false);
        content.setViewCount(0L);
        content.setCreatedBy(UUID.randomUUID());
        content.setCategories(new HashSet<>(categories));
        return contentRepository.saveAndFlush(content);
    }

    @Test
    @DisplayName("hasCategories() with one slug matches any content tagged with that category")
    void singleSlugMatchesAnyTagged() {
        Page<Content> result = contentRepository.findAll(
            ContentSpecifications.hasCategories(List.of(sermons.getSlug())),
            PageRequest.of(0, 10));

        assertThat(result.getContent())
            .extracting(Content::getId)
            .containsExactlyInAnyOrder(bothTags.getId(), oneTagOnly.getId());
    }

    @Test
    @DisplayName("hasCategories() with two slugs requires ALL of them (AND, not OR)")
    void multipleSlugsRequireAllTags() {
        Page<Content> result = contentRepository.findAll(
            ContentSpecifications.hasCategories(List.of(sermons.getSlug(), bibleStudy.getSlug())),
            PageRequest.of(0, 10));

        assertThat(result.getContent())
            .extracting(Content::getId)
            .containsExactly(bothTags.getId());
    }

    @Test
    @DisplayName("hasCategories() does not duplicate rows for content matching multiple joined slugs")
    void doesNotDuplicateMatchingRows() {
        Page<Content> result = contentRepository.findAll(
            ContentSpecifications.hasCategories(List.of(sermons.getSlug(), bibleStudy.getSlug())),
            PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("hasCategories() with a slug nothing has tagged returns no content")
    void unmatchedSlugReturnsEmpty() {
        Page<Content> result = contentRepository.findAll(
            ContentSpecifications.hasCategories(List.of("nonexistent-slug")),
            PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("hasCategories() with an empty list matches everything (no filter)")
    void emptyListMatchesEverything() {
        Page<Content> result = contentRepository.findAll(
            ContentSpecifications.hasCategories(List.of()),
            PageRequest.of(0, 10));

        assertThat(result.getContent())
            .extracting(Content::getId)
            .containsExactlyInAnyOrder(bothTags.getId(), oneTagOnly.getId(), neitherTag.getId());
    }
}
