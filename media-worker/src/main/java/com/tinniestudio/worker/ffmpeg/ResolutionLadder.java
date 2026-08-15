package com.tinniestudio.worker.ffmpeg;

import java.util.ArrayList;
import java.util.List;

public final class ResolutionLadder {

    private static final List<Tier> ALL_TIERS = List.of(
        new Tier("1080p", 1920, 1080, "5000k", "192k", 5_000_000L),
        new Tier("720p",  1280,  720, "2800k", "128k", 2_800_000L),
        new Tier("480p",   854,  480, "1400k", "128k", 1_400_000L),
        new Tier("360p",   640,  360,  "800k",  "96k",   800_000L)
    );

    private ResolutionLadder() {}

    public static List<Tier> forSourceHeight(int sourceHeight) {
        List<Tier> result = new ArrayList<>();
        for (Tier tier : ALL_TIERS) {
            if (sourceHeight >= tier.height()) {
                result.add(tier);
            }
        }
        if (result.isEmpty()) {
            result.add(ALL_TIERS.get(ALL_TIERS.size() - 1));
        }
        return result;
    }

    public record Tier(
        String label,
        int width,
        int height,
        String videoBitrate,
        String audioBitrate,
        long bitrateValue
    ) {}
}
