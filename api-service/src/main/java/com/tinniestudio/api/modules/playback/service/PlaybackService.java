package com.tinniestudio.api.modules.playback.service;

import com.tinniestudio.api.modules.playback.dto.*;
import java.util.List;
import java.util.UUID;

public interface PlaybackService {
    AccessCheckResponse checkAccess(UUID userId, UUID contentId);
    PlaybackManifestResponse getContentManifest(UUID userId, UUID contentId);
    PlaybackManifestResponse getEpisodeManifest(UUID userId, UUID episodeId);
    void recordProgress(UUID userId, ProgressRequest request);
    List<ContinueWatchingItem> getContinueWatching(UUID userId);
}
