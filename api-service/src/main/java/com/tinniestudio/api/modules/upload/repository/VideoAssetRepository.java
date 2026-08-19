package com.tinniestudio.api.modules.upload.repository;

import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.VideoAssetType;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VideoAssetRepository extends JpaRepository<VideoAsset, UUID> {
    Optional<VideoAsset> findByUploadSessionId(UUID uploadSessionId);

    Optional<VideoAsset> findTopByContent_IdAndAssetTypeAndProcessingStatus(
            UUID contentId, VideoAssetType assetType, ProcessingStatus processingStatus);

    Optional<VideoAsset> findTopByEpisode_IdAndAssetTypeAndProcessingStatus(
            UUID episodeId, VideoAssetType assetType, ProcessingStatus processingStatus);

    Optional<VideoAsset> findByContent_IdAndAssetTypeAndIsActiveTrue(UUID contentId, VideoAssetType assetType);

    Optional<VideoAsset> findByEpisode_IdAndAssetTypeAndIsActiveTrue(UUID episodeId, VideoAssetType assetType);

    List<VideoAsset> findByContent_IdAndAssetTypeOrderByCreatedAtDesc(UUID contentId, VideoAssetType assetType);

    List<VideoAsset> findBySeason_IdAndAssetTypeOrderByCreatedAtDesc(UUID seasonId, VideoAssetType assetType);

    List<VideoAsset> findByEpisode_IdAndAssetTypeOrderByCreatedAtDesc(UUID episodeId, VideoAssetType assetType);

    /**
     * Deactivates every VideoAsset sharing (content, assetType) with the given asset, excluding
     * the asset itself — the "retire siblings" half of the isActive lifecycle. Callers are
     * responsible for setting the target asset's own isActive=true afterward (this method never
     * touches it, in case excludeId doesn't actually belong to the given contentId — a caller bug
     * that would otherwise silently deactivate everything including the intended new active one).
     */
    @Transactional
    @Modifying
    @Query("UPDATE VideoAsset a SET a.isActive = false " +
           "WHERE a.content.id = :contentId AND a.assetType = :assetType AND a.id <> :excludeId AND a.isActive = true")
    void deactivateOtherAssets(@Param("contentId") UUID contentId, @Param("assetType") VideoAssetType assetType, @Param("excludeId") UUID excludeId);

    long countByProcessingStatus(ProcessingStatus status);

    long countByContent_CreatedByAndProcessingStatus(UUID createdBy, ProcessingStatus status);

    Page<VideoAsset> findByProcessingStatusInOrderByCreatedAtDesc(
            List<ProcessingStatus> statuses, Pageable pageable);

    /**
     * Find assets in a given status beyond the cutoff. Used by
     * FailedVideoAssetCleanupJob to read storage keys of FAILED assets before
     * their storage objects and DB rows are deleted (Batch 17 item 5).
     */
    @Query("SELECT a FROM VideoAsset a WHERE a.processingStatus = :status AND a.updatedAt < :cutoff")
    List<VideoAsset> findByProcessingStatusAndUpdatedAtBefore(
            @Param("status") ProcessingStatus status,
            @Param("cutoff") Instant cutoff);

    /**
     * Atomically transition assets still in {@code fromStatus} beyond the cutoff to
     * {@code toStatus}. The WHERE clause re-checks fromStatus at write time (not just at
     * an earlier read time), so a concurrent worker transition to COMPLETED/FAILED between
     * this job's read and write cannot be clobbered by a blind overwrite — a row is only
     * touched if it is STILL in fromStatus at the moment of the UPDATE (Batch 17 item 4).
     */
    @Transactional
    @Modifying
    @Query("UPDATE VideoAsset a SET a.processingStatus = :toStatus, a.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE a.processingStatus = :fromStatus AND a.updatedAt < :cutoff")
    int transitionStaleProcessingAssets(
            @Param("fromStatus") ProcessingStatus fromStatus,
            @Param("toStatus") ProcessingStatus toStatus,
            @Param("cutoff") Instant cutoff);
}
