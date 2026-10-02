package com.tinniestudio.api.modules.upload.repository;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.AuthProvider;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.MainCategory;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.VideoAssetType;
import com.tinniestudio.api.shared.entity.Season;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.entity.VideoAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-DB regression test for the trailer-manifest 500: a content-level trailer and a
 * season-level trailer can both be isActive=true while sharing the same content_id (content is
 * denormalized onto every asset — see VideoActivationService javadoc). The original
 * findByContent_IdAndAssetTypeAndIsActiveTrue query matched both rows and threw
 * IncorrectResultSizeDataAccessException. This proves the new scoped queries don't.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureTestEntityManager
@Testcontainers
@ActiveProfiles("test")
class VideoAssetRepositoryTrailerScopeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_trailer_scope_test")
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

    @Autowired private VideoAssetRepository videoAssetRepo;
    @Autowired private SeasonRepository seasonRepo;
    @Autowired private ContentRepository contentRepo;
    @Autowired private ContentTypeRepository contentTypeRepo;
    @Autowired private UserRepository userRepo;

    private UUID uploaderId;

    // video_assets.uploaded_by is NOT NULL and has an FK to users(id) (V27) — a random UUID
    // violates that FK, so every asset in this test is attributed to one real seeded uploader.
    @BeforeEach
    void seedUploader() {
        User user = new User();
        user.setEmail("trailer-scope-uploader-" + System.nanoTime() + "@example.com");
        user.setProvider(AuthProvider.LOCAL);
        uploaderId = userRepo.saveAndFlush(user).getId();
    }

    private Content newContent() {
        ContentType showType = contentTypeRepo.findBySlug("tv-show")
            .orElseGet(() -> contentTypeRepo.findBySlug("movie")
                .orElseThrow(() -> new IllegalStateException("No seeded ContentType found")));
        Content content = new Content();
        content.setTitle("Trailer Scope Test Show " + System.nanoTime());
        content.setContentType(showType);
        content.setStatus(ContentStatus.PUBLISHED);
        content.setMainCategory(MainCategory.TV_SHOWS);
        content.setCreatedBy(UUID.randomUUID());
        return contentRepo.saveAndFlush(content);
    }

    private VideoAsset newTrailerAsset(Content content, Season season, boolean active) {
        VideoAsset asset = new VideoAsset();
        asset.setContent(content);
        asset.setSeason(season);
        asset.setAssetType(VideoAssetType.TRAILER);
        asset.setOriginalFilename("trailer.mp4");
        asset.setStorageKey("raw/" + UUID.randomUUID() + "/trailer.mp4");
        asset.setUploadedBy(uploaderId);
        asset.setActive(active);
        // processingStatus is NOT NULL at the DB level (V27) and has no Java-side default on the
        // entity (see VideoAsset.java) — every other direct `new VideoAsset()` test in this repo
        // (e.g. VideoAssetRepositoryTest) sets it explicitly for the same reason.
        asset.setProcessingStatus(ProcessingStatus.READY);
        return videoAssetRepo.saveAndFlush(asset);
    }

    @Test
    void contentLevelQueryIgnoresActiveSeasonTrailerSharingTheSameContentId() {
        Content content = newContent();
        Season season = new Season();
        season.setContent(content);
        season.setSeasonNumber(1);
        Season savedSeason = seasonRepo.saveAndFlush(season);

        // Content-level trailer (season = null) and a season-level trailer, both active, both
        // sharing content.getId() — this is exactly the state that caused the 500.
        VideoAsset contentTrailer = newTrailerAsset(content, null, true);
        newTrailerAsset(content, savedSeason, true);

        VideoAsset found = videoAssetRepo
            .findByContent_IdAndSeasonIsNullAndEpisodeIsNullAndAssetTypeAndIsActiveTrue(
                content.getId(), VideoAssetType.TRAILER)
            .orElseThrow();

        assertThat(found.getId()).isEqualTo(contentTrailer.getId());
    }

    @Test
    void seasonLevelQueryOnlyMatchesItsOwnSeason() {
        Content content = newContent();
        Season season = new Season();
        season.setContent(content);
        season.setSeasonNumber(1);
        Season savedSeason = seasonRepo.saveAndFlush(season);

        newTrailerAsset(content, null, true); // content-level trailer, same content_id
        VideoAsset seasonTrailer = newTrailerAsset(content, savedSeason, true);

        VideoAsset found = videoAssetRepo
            .findBySeason_IdAndAssetTypeAndIsActiveTrue(savedSeason.getId(), VideoAssetType.TRAILER)
            .orElseThrow();

        assertThat(found.getId()).isEqualTo(seasonTrailer.getId());
    }

    @Test
    void oldUnscopedQueryStillThrowsOnTheSameData_documentingTheBug() {
        Content content = newContent();
        Season season = new Season();
        season.setContent(content);
        season.setSeasonNumber(1);
        Season savedSeason = seasonRepo.saveAndFlush(season);

        newTrailerAsset(content, null, true);
        newTrailerAsset(content, savedSeason, true);

        assertThatThrownBy(() ->
            videoAssetRepo.findByContent_IdAndAssetTypeAndIsActiveTrue(content.getId(), VideoAssetType.TRAILER))
            .isInstanceOf(IncorrectResultSizeDataAccessException.class);
    }
}
