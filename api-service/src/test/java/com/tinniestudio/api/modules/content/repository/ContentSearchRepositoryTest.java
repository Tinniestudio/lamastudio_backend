package com.tinniestudio.api.modules.content.repository;

import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression test: ContentRepository.searchBy{Relevance,Latest,Popular} are native queries that
 * explicitly enumerate Content's mapped columns. V34 added average_rating/review_count to the
 * Content entity but these three queries were never updated — every real search request threw
 * "Unable to find column position by name: average_rating" (Hibernate can't hydrate an entity
 * missing a mapped column from a native-query result set). SearchServiceTest mocks
 * ContentRepository, so it never caught this; only a real DB round-trip does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@ExtendWith(SpringExtension.class)
class ContentSearchRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_search_test")
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

    private Content seedPublishedContent(String title) {
        Content content = new Content();
        content.setTitle(title);
        content.setSlug(title.toLowerCase().replace(" ", "-") + "-" + System.nanoTime());
        content.setType(ContentType.MOVIE);
        content.setStatus(ContentStatus.PUBLISHED);
        content.setMaturityRating(MaturityRating.PG);
        content.setDescription("A searchable description");
        content.setShortDescription("Short desc");
        content.setComingSoon(false);
        content.setFeatured(false);
        content.setViewCount(0L);
        content.setCreatedBy(java.util.UUID.randomUUID());
        content.setCategories(new HashSet<>());
        return contentRepository.saveAndFlush(content);
    }

    @Test
    void searchByRelevance_doesNotThrow_andReturnsMatchingContent() {
        seedPublishedContent("Searchable Relevance Title");

        assertThatCode(() -> {
            Page<Content> result = contentRepository.searchByRelevance(
                    "Relevance", null, null, null, null, PageRequest.of(0, 10));
            assertThat(result.getTotalElements()).isGreaterThanOrEqualTo(1);
        }).doesNotThrowAnyException();
    }

    @Test
    void searchByLatest_doesNotThrow() {
        seedPublishedContent("Searchable Latest Title");

        assertThatCode(() -> contentRepository.searchByLatest(
                "Latest", null, null, null, null, PageRequest.of(0, 10)))
                .doesNotThrowAnyException();
    }

    @Test
    void searchByPopular_doesNotThrow() {
        seedPublishedContent("Searchable Popular Title");

        assertThatCode(() -> contentRepository.searchByPopular(
                "Popular", null, null, null, null, PageRequest.of(0, 10)))
                .doesNotThrowAnyException();
    }
}
