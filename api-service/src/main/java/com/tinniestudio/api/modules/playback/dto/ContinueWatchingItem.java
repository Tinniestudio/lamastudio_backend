package com.tinniestudio.api.modules.playback.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class ContinueWatchingItem {
    private final UUID contentId;
    private final UUID episodeId;
    private final String title;
    private final String thumbnailUrl;
    private final int progressSeconds;
    private final int durationSeconds;
    private final BigDecimal completionPercentage;
    private final Instant lastWatchedAt;
}
