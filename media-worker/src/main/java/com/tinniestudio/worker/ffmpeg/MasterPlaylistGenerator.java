package com.tinniestudio.worker.ffmpeg;

import java.util.List;

public final class MasterPlaylistGenerator {

    private MasterPlaylistGenerator() {}

    public static String generate(List<ResolutionLadder.Tier> tiers) {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n\n");
        for (ResolutionLadder.Tier tier : tiers) {
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(tier.bitrateValue())
              .append(",RESOLUTION=").append(tier.width()).append("x").append(tier.height())
              .append("\n");
            sb.append(tier.label()).append("/playlist.m3u8\n");
        }
        return sb.toString();
    }
}
