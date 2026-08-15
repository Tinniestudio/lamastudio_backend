package com.tinniestudio.worker.ffmpeg;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MasterPlaylistGeneratorTest {

    @Test
    void generatesValidM3u8WithAllVariants() {
        List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(1080);
        String m3u8 = MasterPlaylistGenerator.generate(tiers);

        assertThat(m3u8).startsWith("#EXTM3U");
        assertThat(m3u8).contains("#EXT-X-VERSION:3");
        assertThat(m3u8).contains("BANDWIDTH=5000000,RESOLUTION=1920x1080");
        assertThat(m3u8).contains("1080p/playlist.m3u8");
        assertThat(m3u8).contains("BANDWIDTH=2800000,RESOLUTION=1280x720");
        assertThat(m3u8).contains("720p/playlist.m3u8");
        assertThat(m3u8).contains("360p/playlist.m3u8");
    }

    @Test
    void generatesM3u8ForSingleVariant() {
        List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(360);
        String m3u8 = MasterPlaylistGenerator.generate(tiers);

        assertThat(m3u8).contains("360p/playlist.m3u8");
        assertThat(m3u8).doesNotContain("720p/playlist.m3u8");
    }
}
