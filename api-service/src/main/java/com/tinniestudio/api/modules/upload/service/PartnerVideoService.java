package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.modules.upload.dto.PartnerVideoAssetResponse;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.Episode;
import com.tinniestudio.api.shared.entity.Season;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.TargetEntityType;
import com.tinniestudio.api.shared.entity.DomainEnums.VideoAssetType;
import com.tinniestudio.api.shared.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerVideoService {

    private final VideoAssetRepository videoAssetRepository;
    private final ContentRepository contentRepository;
    private final SeasonRepository seasonRepository;
    private final EpisodeRepository episodeRepository;
    private final VideoActivationService videoActivationService;
    private final StorageService storageService;

    @Transactional(readOnly = true)
    public List<PartnerVideoAssetResponse> listForTarget(
            UUID userId, TargetEntityType targetEntityType, UUID targetEntityId, VideoAssetType assetType) {
        List<VideoAsset> assets = switch (targetEntityType) {
            case CONTENT -> {
                Content content = contentRepository.findById(targetEntityId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + targetEntityId));
                assertOwnsContent(userId, content);
                yield videoAssetRepository.findByContent_IdAndAssetTypeOrderByCreatedAtDesc(targetEntityId, assetType);
            }
            case SEASON -> {
                Season season = seasonRepository.findById(targetEntityId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + targetEntityId));
                assertOwnsContent(userId, season.getContent());
                yield videoAssetRepository.findBySeason_IdAndAssetTypeOrderByCreatedAtDesc(targetEntityId, assetType);
            }
            case EPISODE -> {
                Episode episode = episodeRepository.findById(targetEntityId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + targetEntityId));
                assertOwnsContent(userId, episode.getSeason().getContent());
                yield videoAssetRepository.findByEpisode_IdAndAssetTypeOrderByCreatedAtDesc(targetEntityId, assetType);
            }
            case VIDEO_ASSET -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "targetEntityType must be CONTENT, SEASON, or EPISODE");
        };
        return assets.stream().map(PartnerVideoAssetResponse::from).toList();
    }

    @Transactional
    public void activate(UUID userId, UUID videoAssetId) {
        VideoAsset asset = videoAssetRepository.findById(videoAssetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId));
        // Ownership by uploader, not by re-resolving content.getCreatedBy() — matches the
        // existing IDOR-guard precedent in UploadService.attachSubtitle(), avoids a null check
        // for the content-link case (checked separately, with its own clearer error, below).
        if (!userId.equals(asset.getUploadedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId);
        }
        if (asset.getProcessingStatus() != ProcessingStatus.READY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a READY video can be set as active");
        }
        if (asset.getContent() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "This video has no linked content and cannot be activated");
        }
        videoActivationService.activateAndRetireSiblings(asset);
    }

    /**
     * Sets processingStatus=CANCELLED immediately, regardless of what stage media-worker is
     * currently in — the worker discovers this asynchronously via its own best-effort
     * stage-boundary checks (VideoProcessingService.isCancelled) and never overwrites this value
     * once set. Only PENDING/PROCESSING assets can be cancelled; anything already terminal
     * (READY/FAILED/CANCELLED) must go through delete() instead.
     */
    @Transactional
    public void cancel(UUID userId, UUID videoAssetId) {
        VideoAsset asset = videoAssetRepository.findById(videoAssetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId));
        if (!userId.equals(asset.getUploadedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId);
        }
        if (asset.getProcessingStatus() != ProcessingStatus.PENDING
                && asset.getProcessingStatus() != ProcessingStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only a PENDING or PROCESSING video can be cancelled");
        }
        asset.setProcessingStatus(ProcessingStatus.CANCELLED);
        videoAssetRepository.save(asset);
    }

    /**
     * Permanently removes the VideoAsset row (cascading to its VideoVariant/Subtitle children via
     * the entity's existing CascadeType.ALL) and its raw storage object. Only reachable from a
     * terminal status — PENDING/PROCESSING must be cancel()'d first. Mirrors the exact deletion
     * pattern FailedVideoAssetCleanupJob already uses for FAILED assets: storage deletion is
     * best-effort (a storage-layer failure must not block the DB row from being removed, since an
     * orphaned object with no DB row is a much smaller problem than a stuck row the partner can
     * never clear from their history).
     */
    @Transactional
    public void delete(UUID userId, UUID videoAssetId) {
        VideoAsset asset = videoAssetRepository.findById(videoAssetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId));
        if (!userId.equals(asset.getUploadedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId);
        }
        if (asset.getProcessingStatus() == ProcessingStatus.PENDING
                || asset.getProcessingStatus() == ProcessingStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cancel the video before deleting it, or wait for it to reach a final status");
        }
        if (asset.getStorageKey() != null) {
            try {
                storageService.deleteObject(asset.getStorageKey());
            } catch (Exception e) {
                log.warn("Failed to delete storage object {} for asset {}: {}",
                    asset.getStorageKey(), asset.getId(), e.getMessage());
            }
        }
        videoAssetRepository.delete(asset);
    }

    private void assertOwnsContent(UUID userId, Content content) {
        if (!userId.equals(content.getCreatedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + content.getId());
        }
    }
}
