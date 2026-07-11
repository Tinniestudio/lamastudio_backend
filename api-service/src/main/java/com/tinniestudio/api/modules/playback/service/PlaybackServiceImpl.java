package com.tinniestudio.api.modules.playback.service;

import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.playback.dto.*;
import com.tinniestudio.api.modules.playback.repository.WatchProgressRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybackServiceImpl implements PlaybackService {

    private final ContentRepository contentRepo;
    private final UserSubscriptionRepository subscriptionRepo;
    private final VideoAssetRepository videoAssetRepo;
    private final WatchProgressRepository watchProgressRepo;
    private final EpisodeRepository episodeRepo;
    private final RabbitTemplate rabbitTemplate;
    private final AppProperties appProperties;

    // -------------------------------------------------------------------------
    // Access check
    // -------------------------------------------------------------------------

    @Override
    public AccessCheckResponse checkAccess(UUID userId, UUID contentId) {
        Content content = contentRepo.findById(contentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found"));

        if (content.getStatus() != ContentStatus.PUBLISHED) {
            return AccessCheckResponse.denied("CONTENT_NOT_PUBLISHED");
        }

        boolean hasSubscription = subscriptionRepo
            .findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
            .isPresent();

        if (!hasSubscription) {
            return AccessCheckResponse.denied("NO_ACTIVE_SUBSCRIPTION");
        }

        return AccessCheckResponse.granted();
    }

    // -------------------------------------------------------------------------
    // Movie manifest
    // -------------------------------------------------------------------------

    @Override
    public PlaybackManifestResponse getContentManifest(UUID userId, UUID contentId) {
        AccessCheckResponse access = checkAccess(userId, contentId);
        if (!access.isHasAccess()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, access.getReason());
        }

        VideoAsset asset = videoAssetRepo
            .findTopByContent_IdAndAssetTypeAndProcessingStatus(
                contentId, VideoAssetType.MAIN_VIDEO, ProcessingStatus.READY)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No ready video asset"));

        Integer resumeAt = watchProgressRepo.findMovieProgress(userId, contentId)
            .map(WatchProgress::getProgressSeconds)
            .orElse(null);

        return buildManifestResponse(asset, resumeAt);
    }

    // -------------------------------------------------------------------------
    // Episode manifest
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PlaybackManifestResponse getEpisodeManifest(UUID userId, UUID episodeId) {
        Episode episode = episodeRepo.findById(episodeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found"));

        UUID contentId = episode.getSeason().getContent().getId();

        AccessCheckResponse access = checkAccess(userId, contentId);
        if (!access.isHasAccess()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, access.getReason());
        }

        VideoAsset asset = videoAssetRepo
            .findTopByEpisode_IdAndAssetTypeAndProcessingStatus(
                episodeId, VideoAssetType.MAIN_VIDEO, ProcessingStatus.READY)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No ready video asset for episode"));

        Integer resumeAt = watchProgressRepo.findByUserIdAndEpisodeId(userId, episodeId)
            .map(WatchProgress::getProgressSeconds)
            .orElse(null);

        return buildManifestResponse(asset, resumeAt);
    }

    // -------------------------------------------------------------------------
    // Stubs — implemented in Task 4
    // -------------------------------------------------------------------------

    @Override
    public void recordProgress(UUID userId, ProgressRequest request) {
        throw new UnsupportedOperationException("implemented in Task 4");
    }

    @Override
    public List<ContinueWatchingItem> getContinueWatching(UUID userId) {
        throw new UnsupportedOperationException("implemented in Task 4");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PlaybackManifestResponse buildManifestResponse(VideoAsset asset, Integer resumeAt) {
        String manifestUrl = appProperties.getCdn().getBaseUrl() + "/" + asset.getManifestUrl();

        List<SubtitleDto> subtitles = asset.getSubtitles().stream()
            .map(s -> new SubtitleDto(
                s.getLanguageCode(),
                s.getLabel(),
                s.getFileUrl(),
                Boolean.TRUE.equals(s.getIsDefault())   // Boolean field: getIsDefault()
            ))
            .collect(Collectors.toList());

        return new PlaybackManifestResponse(manifestUrl, subtitles, resumeAt, asset.getDurationSeconds());
    }
}
