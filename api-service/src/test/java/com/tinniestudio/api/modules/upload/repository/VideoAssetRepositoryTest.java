package com.tinniestudio.api.modules.upload.repository;

import com.tinniestudio.api.shared.entity.DomainEnums.AuthProvider;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.VideoAssetType;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.entity.VideoAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for Bug 1: StaleVideoAssetJob used to recover stuck PROCESSING
 * VideoAssets via a read-then-save loop with no @Version and no conditional update.
 * Between the SELECT and the save, the media-processing worker could legitimately
 * transition the same asset to READY/FAILED, and the job's blind overwrite would
 * clobber that real update.
 *
 * <p>The fix (VideoAssetRepository#transitionStaleProcessingAssets) is a single
 * atomic conditional UPDATE guarded by "WHERE processing_status = PROCESSING" at
 * write time. This test proves that guard: two rows share the same stale
 * updatedAt, but only the one still in PROCESSING is touched — a row that has
 * already moved to READY is left completely alone, exactly as if a concurrent
 * worker had won the race.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureTestEntityManager
@Testcontainers
@ActiveProfiles("test")
@ExtendWith(SpringExtension.class)
class VideoAssetRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_video_asset_test")
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

    @Autowired private VideoAssetRepository videoAssetRepository;
    @Autowired private TestEntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID uploaderId;

    @BeforeEach
    void seedUser() {
        User user = new User();
        user.setEmail("uploader-" + System.nanoTime() + "@example.com");
        user.setProvider(AuthProvider.LOCAL);
        user = entityManager.persistAndFlush(user);
        uploaderId = user.getId();
    }

    private VideoAsset seedAsset(ProcessingStatus status) {
        VideoAsset asset = new VideoAsset();
        asset.setAssetType(VideoAssetType.MAIN_VIDEO);
        asset.setOriginalFilename("movie.mp4");
        asset.setStorageKey("raw/" + UUID.randomUUID() + ".mp4");
        asset.setProcessingStatus(status);
        asset.setUploadedBy(uploaderId);
        return entityManager.persistAndFlush(asset);
    }

    /**
     * updatedAt is Hibernate-managed (@UpdateTimestamp), so it's stamped to "now"
     * on every persist/flush. Backdate it directly via JDBC to simulate a row
     * that has been stale for a while, without going through the entity manager.
     */
    private void backdateUpdatedAt(UUID id, Instant updatedAt) {
        jdbcTemplate.update("UPDATE video_assets SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(updatedAt), id);
    }

    @Test
    void transitionStaleProcessingAssets_onlyTouchesRowsStillInProcessing() {
        Instant staleTimestamp = Instant.now().minus(90, ChronoUnit.MINUTES);
        Instant cutoff = Instant.now().minus(60, ChronoUnit.MINUTES);

        VideoAsset stillProcessing = seedAsset(ProcessingStatus.PROCESSING);
        backdateUpdatedAt(stillProcessing.getId(), staleTimestamp);

        // Simulates the worker having legitimately finished processing this asset
        // between the job's read and write — it must NOT be clobbered back to FAILED.
        VideoAsset alreadyCompleted = seedAsset(ProcessingStatus.READY);
        backdateUpdatedAt(alreadyCompleted.getId(), staleTimestamp);

        entityManager.clear();

        int updatedCount = videoAssetRepository.transitionStaleProcessingAssets(
                ProcessingStatus.PROCESSING, ProcessingStatus.FAILED, cutoff);

        assertThat(updatedCount).isEqualTo(1);

        VideoAsset reloadedProcessing = videoAssetRepository.findById(stillProcessing.getId()).orElseThrow();
        assertThat(reloadedProcessing.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);

        VideoAsset reloadedCompleted = videoAssetRepository.findById(alreadyCompleted.getId()).orElseThrow();
        assertThat(reloadedCompleted.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
    }

    @Test
    void transitionStaleProcessingAssets_leavesRecentProcessingRowsAlone() {
        VideoAsset recentlyUpdated = seedAsset(ProcessingStatus.PROCESSING);
        // Not backdated: updatedAt is "now", so it's not older than the cutoff.

        entityManager.clear();

        Instant cutoff = Instant.now().minus(60, ChronoUnit.MINUTES);
        int updatedCount = videoAssetRepository.transitionStaleProcessingAssets(
                ProcessingStatus.PROCESSING, ProcessingStatus.FAILED, cutoff);

        assertThat(updatedCount).isEqualTo(0);
        VideoAsset reloaded = videoAssetRepository.findById(recentlyUpdated.getId()).orElseThrow();
        assertThat(reloaded.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
    }
}
