package com.tinniestudio.api.modules.episode.repository;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.MainCategory;
import com.tinniestudio.api.shared.entity.Episode;
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
 * Real-DB regression test for the trg_episode_slug Postgres trigger (V58) — same rationale as
 * SeasonSlugTriggerTest: a pure Mockito unit test can't exercise a DB trigger.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureTestEntityManager
@Testcontainers
@ActiveProfiles("test")
class EpisodeSlugTriggerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_episode_slug_test")
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

    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private SeasonRepository seasonRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentTypeRepository contentTypeRepository;

    private Season newSeason(int seasonNumber) {
        ContentType movieType = contentTypeRepository.findBySlug("movie")
            .orElseThrow(() -> new IllegalStateException("V53 seed row 'movie' not found"));
        Content content = new Content();
        content.setTitle("Episode Slug Test Show " + System.nanoTime());
        content.setContentType(movieType);
        content.setStatus(ContentStatus.DRAFT);
        content.setMainCategory(MainCategory.TV_SHOWS);
        content.setCreatedBy(UUID.randomUUID());
        Content savedContent = contentRepository.saveAndFlush(content);

        Season season = new Season();
        season.setContent(savedContent);
        season.setSeasonNumber(seasonNumber);
        return seasonRepository.saveAndFlush(season);
    }

    @Test
    void slugifiesTitleOnInsert() {
        Season season = newSeason(1);
        Episode episode = new Episode();
        episode.setSeason(season);
        episode.setEpisodeNumber(1);
        episode.setTitle("The Pilot Episode");
        Episode saved = episodeRepository.saveAndFlush(episode);

        Episode reloaded = episodeRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSlug()).isEqualTo("the-pilot-episode");
    }

    @Test
    void appendsSuffixOnTitleCollisionWithinSameSeason() {
        Season season = newSeason(1);

        Episode first = new Episode();
        first.setSeason(season);
        first.setEpisodeNumber(1);
        first.setTitle("Pilot");
        Episode savedFirst = episodeRepository.saveAndFlush(first);

        Episode second = new Episode();
        second.setSeason(season);
        second.setEpisodeNumber(2);
        second.setTitle("Pilot");
        Episode savedSecond = episodeRepository.saveAndFlush(second);

        assertThat(episodeRepository.findById(savedFirst.getId()).orElseThrow().getSlug()).isEqualTo("pilot");
        assertThat(episodeRepository.findById(savedSecond.getId()).orElseThrow().getSlug()).isEqualTo("pilot-2");
    }

    @Test
    void differentSeasonsCanReuseTheSameEpisodeTitleWithoutCollision() {
        Season seasonA = newSeason(1);
        Season seasonB = newSeason(1); // different content, same season number — independent scope

        Episode episodeA = new Episode();
        episodeA.setSeason(seasonA);
        episodeA.setEpisodeNumber(1);
        episodeA.setTitle("Pilot");
        Episode savedA = episodeRepository.saveAndFlush(episodeA);

        Episode episodeB = new Episode();
        episodeB.setSeason(seasonB);
        episodeB.setEpisodeNumber(1);
        episodeB.setTitle("Pilot");
        Episode savedB = episodeRepository.saveAndFlush(episodeB);

        assertThat(episodeRepository.findById(savedA.getId()).orElseThrow().getSlug()).isEqualTo("pilot");
        assertThat(episodeRepository.findById(savedB.getId()).orElseThrow().getSlug()).isEqualTo("pilot");
    }
}
