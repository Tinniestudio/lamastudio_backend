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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PartnerVideoService {

    private final VideoAssetRepository videoAssetRepository;
    private final ContentRepository contentRepository;
    private final SeasonRepository seasonRepository;
    private final EpisodeRepository episodeRepository;
    private final VideoActivationService videoActivationService;

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

    private void assertOwnsContent(UUID userId, Content content) {
        if (!userId.equals(content.getCreatedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + content.getId());
        }
    }
}
