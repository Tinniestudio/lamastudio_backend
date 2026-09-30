package com.tinniestudio.api.modules.content.repository;

import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.MainCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB regression test for the trg_content_slug Postgres trigger (V18) actually being visible
 * to the Java entity after save — a pure Mockito unit test can't verify this, since mocking
 * ContentRepository.save() never executes real SQL, and the bug this guards against is
 * specifically about Hibernate's persistence-context cache masking a real trigger-set value.
 * Uses @DataJpaTest (JPA slice only) rather than @SpringBootTest so this doesn't depend on beans
 * unrelated to persistence (e.g. StorageService) being wired — same rationale as
 * ContentSpecificationsTest/SeasonSlugTriggerTest/EpisodeSlugTriggerTest.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureTestEntityManager
@Testcontainers
@ActiveProfiles("test")
class ContentSlugTriggerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_content_slug_test")
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

    private Content newContent(String title) {
        ContentType movieType = contentTypeRepository.findBySlug("movie")
            .orElseThrow(() -> new IllegalStateException("V53 seed row 'movie' not found"));
        Content content = new Content();
        content.setTitle(title);
        content.setContentType(movieType);
        content.setStatus(ContentStatus.DRAFT);
        content.setMainCategory(MainCategory.MOVIES);
        content.setCreatedBy(UUID.randomUUID());
        return content;
    }

    @Test
    void setsSlugFromTitleOnInsert() {
        Content saved = contentRepository.saveAndFlush(newContent("Test Movie Title Xyz"));

        Content reloaded = contentRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSlug()).isEqualTo("test-movie-title-xyz");
    }

    @Test
    void appendsSuffixOnTitleCollision() {
        String title = "Duplicate Title Xyz";

        Content savedFirst = contentRepository.saveAndFlush(newContent(title));
        Content savedSecond = contentRepository.saveAndFlush(newContent(title));

        assertThat(contentRepository.findById(savedFirst.getId()).orElseThrow().getSlug()).isEqualTo("duplicate-title-xyz");
        assertThat(contentRepository.findById(savedSecond.getId()).orElseThrow().getSlug()).isEqualTo("duplicate-title-xyz-2");
    }

    @Test
    void updatingTitleRecomputesSlug() {
        Content saved = contentRepository.saveAndFlush(newContent("Original Title Xyz"));

        saved.setTitle("Renamed Title Xyz");
        contentRepository.saveAndFlush(saved);

        Content reloaded = contentRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSlug()).isEqualTo("renamed-title-xyz");
    }
}
