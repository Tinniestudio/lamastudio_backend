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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;

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
    // Record progress
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void recordProgress(UUID userId, ProgressRequest req) {
        if (req.getContentId() == null && req.getEpisodeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentId or episodeId required");
        }

        WatchProgress progress;
        if (req.getEpisodeId() != null) {
            progress = watchProgressRepo.findByUserIdAndEpisodeId(userId, req.getEpisodeId())
                .orElseGet(() -> {
                    WatchProgress w = new WatchProgress();
                    w.setUserId(userId);
                    w.setEpisodeId(req.getEpisodeId());
                    return w;
                });
        } else {
            progress = watchProgressRepo.findMovieProgress(userId, req.getContentId())
                .orElseGet(() -> {
                    WatchProgress w = new WatchProgress();
                    w.setUserId(userId);
                    w.setContentId(req.getContentId());
                    return w;
                });
        }

        progress.setProgressSeconds(req.getProgressSeconds());
        progress.setDurationSeconds(req.getDurationSeconds());
        progress.setDeviceType(req.getDeviceType());
        progress.setLastWatchedAt(Instant.now());

        BigDecimal percentage = BigDecimal.valueOf(req.getProgressSeconds())
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(req.getDurationSeconds()), 2, RoundingMode.HALF_UP);
        progress.setCompletionPercentage(percentage);
        progress.setCompleted(percentage.compareTo(BigDecimal.valueOf(90)) >= 0);

        watchProgressRepo.save(progress);

        // Best-effort analytics publish
        try {
            rabbitTemplate.convertAndSend("analytics.ingest", Map.of(
                "type", "PROGRESS_TRACKED",
                "userId", userId.toString(),
                "contentId", req.getContentId() != null ? req.getContentId().toString() : "",
                "episodeId", req.getEpisodeId() != null ? req.getEpisodeId().toString() : "",
                "progressSeconds", req.getProgressSeconds(),
                "durationSeconds", req.getDurationSeconds()
            ));
        } catch (Exception e) {
            log.warn("Analytics publish failed (non-critical): {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Continue watching
    // -------------------------------------------------------------------------

    @Override
    public List<ContinueWatchingItem> getContinueWatching(UUID userId) {
        List<WatchProgress> progresses = watchProgressRepo
            .findByUserIdAndCompletedFalseOrderByLastWatchedAtDesc(userId, PageRequest.of(0, 20));

        Set<UUID> contentIds = progresses.stream()
            .filter(p -> p.getContentId() != null && p.getEpisodeId() == null)
            .map(WatchProgress::getContentId)
            .collect(Collectors.toSet());

        Set<UUID> episodeIds = progresses.stream()
            .filter(p -> p.getEpisodeId() != null)
            .map(WatchProgress::getEpisodeId)
            .collect(Collectors.toSet());

        Map<UUID, Content> contentMap = contentRepo.findAllById(contentIds).stream()
            .collect(Collectors.toMap(Content::getId, c -> c));
        Map<UUID, Episode> episodeMap = episodeRepo.findAllById(episodeIds).stream()
            .collect(Collectors.toMap(Episode::getId, e -> e));

        return progresses.stream()
            .map(p -> {
                String title;
                if (p.getEpisodeId() != null) {
                    Episode ep = episodeMap.get(p.getEpisodeId());
                    title = ep != null ? ep.getTitle() : "Unknown Episode";
                } else {
                    Content c = contentMap.get(p.getContentId());
                    title = c != null ? c.getTitle() : "Unknown Content";
                }
                return new ContinueWatchingItem(
                    p.getContentId(),
                    p.getEpisodeId(),
                    title,
                    null,  // thumbnailUrl — enriched in Batch 12
                    p.getProgressSeconds() != null ? p.getProgressSeconds() : 0,
                    p.getDurationSeconds() != null ? p.getDurationSeconds() : 0,
                    p.getCompletionPercentage(),
                    p.getLastWatchedAt()
                );
            })
            .collect(Collectors.toList());
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
