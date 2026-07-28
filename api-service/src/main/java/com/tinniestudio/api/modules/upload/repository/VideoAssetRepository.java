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

    long countByProcessingStatus(ProcessingStatus status);

    long countByContent_CreatedByAndProcessingStatus(UUID createdBy, ProcessingStatus status);

    Page<VideoAsset> findByProcessingStatusInOrderByCreatedAtDesc(
            List<ProcessingStatus> statuses, Pageable pageable);

    /**
     * Find assets stuck in PROCESSING beyond the given cutoff (stale recovery).
     */
    @Query("SELECT a FROM VideoAsset a WHERE a.processingStatus = :status AND a.updatedAt < :cutoff")
    List<VideoAsset> findByProcessingStatusAndUpdatedAtBefore(
            @Param("status") ProcessingStatus status,
            @Param("cutoff") Instant cutoff);

    /**
     * Bulk-delete failed assets older than the given cutoff (7-day cleanup).
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM VideoAsset a WHERE a.processingStatus = :status AND a.updatedAt < :cutoff")
    int deleteByProcessingStatusAndUpdatedAtBefore(
            @Param("status") ProcessingStatus status,
            @Param("cutoff") Instant cutoff);
}
