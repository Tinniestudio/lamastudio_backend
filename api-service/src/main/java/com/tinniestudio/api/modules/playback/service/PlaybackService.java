package com.tinniestudio.api.modules.playback.service;

import com.tinniestudio.api.modules.playback.dto.*;
import java.util.List;
import java.util.UUID;

public interface PlaybackService {
    AccessCheckResponse checkAccess(org.springframework.security.core.userdetails.UserDetails principal, UUID contentId);
    PlaybackManifestResponse getContentManifest(org.springframework.security.core.userdetails.UserDetails principal, UUID contentId);
    PlaybackManifestResponse getEpisodeManifest(org.springframework.security.core.userdetails.UserDetails principal, UUID episodeId);
    PlaybackManifestResponse getTrailerManifest(UUID contentId);
    void recordProgress(UUID userId, ProgressRequest request);
    List<ContinueWatchingItem> getContinueWatching(UUID userId);
}
