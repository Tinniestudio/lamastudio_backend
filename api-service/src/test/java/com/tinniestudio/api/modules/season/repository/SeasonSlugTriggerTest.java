package com.tinniestudio.api.modules.season.repository;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.MainCategory;
import com.tinniestudio.api.shared.entity.Season;
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
 * Real-DB regression test for the trg_season_slug Postgres trigger (V57) — a pure Mockito unit
 * test can't verify DB-trigger behavior since mocking SeasonRepository.save() never executes real
 * SQL. Uses @DataJpaTest (JPA slice only) rather than @SpringBootTest so this doesn't depend on
 * beans unrelated to persistence (e.g. StorageService) being wired — same rationale as
 * ContentSpecificationsTest.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureTestEntityManager
@Testcontainers
@ActiveProfiles("test")
class SeasonSlugTriggerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_season_slug_test")
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

    @Autowired private SeasonRepository seasonRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentTypeRepository contentTypeRepository;

    private Content newContent(String title) {
        ContentType movieType = contentTypeRepository.findBySlug("movie")
            .orElseThrow(() -> new IllegalStateException("V53 seed row 'movie' not found"));
        Content content = new Content();
        content.setTitle(title);
        content.setContentType(movieType);
        content.setStatus(ContentStatus.DRAFT);
        content.setMainCategory(MainCategory.TV_SHOWS);
        content.setCreatedBy(UUID.randomUUID());
        return contentRepository.saveAndFlush(content);
    }

    @Test
    void setsSlugFromSeasonNumberOnInsert() {
        Content content = newContent("Slug Test Show " + System.nanoTime());
        Season season = new Season();
        season.setContent(content);
        season.setSeasonNumber(1);
        Season saved = seasonRepository.saveAndFlush(season);

        Season reloaded = seasonRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSlug()).isEqualTo("season-1");
    }

    @Test
    void differentContentsCanEachHaveSeasonOne() {
        Content contentA = newContent("Show A " + System.nanoTime());
        Content contentB = newContent("Show B " + System.nanoTime());

        Season seasonA = new Season();
        seasonA.setContent(contentA);
        seasonA.setSeasonNumber(1);
        Season savedA = seasonRepository.saveAndFlush(seasonA);

        Season seasonB = new Season();
        seasonB.setContent(contentB);
        seasonB.setSeasonNumber(1);
        Season savedB = seasonRepository.saveAndFlush(seasonB);

        assertThat(seasonRepository.findById(savedA.getId()).orElseThrow().getSlug()).isEqualTo("season-1");
        assertThat(seasonRepository.findById(savedB.getId()).orElseThrow().getSlug()).isEqualTo("season-1");
    }

    @Test
    void updatingSeasonNumberRecomputesSlug() {
        Content content = newContent("Renumber Show " + System.nanoTime());
        Season season = new Season();
        season.setContent(content);
        season.setSeasonNumber(1);
        Season saved = seasonRepository.saveAndFlush(season);

        saved.setSeasonNumber(2);
        seasonRepository.saveAndFlush(saved);

        Season reloaded = seasonRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSlug()).isEqualTo("season-2");
    }
}
