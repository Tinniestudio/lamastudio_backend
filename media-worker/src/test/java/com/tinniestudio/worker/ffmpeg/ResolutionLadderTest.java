package com.tinniestudio.worker.ffmpeg;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ResolutionLadderTest {

    @Test
    void source1080pGeneratesAllFourResolutions() {
        List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(1080);
        assertThat(tiers).extracting(ResolutionLadder.Tier::label)
            .containsExactly("1080p", "720p", "480p", "360p");
    }

    @Test
    void source720pGeneratesThreeResolutions() {
        List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(720);
        assertThat(tiers).extracting(ResolutionLadder.Tier::label)
            .containsExactly("720p", "480p", "360p");
    }

    @Test
    void source480pGeneratesTwoResolutions() {
        List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(480);
        assertThat(tiers).extracting(ResolutionLadder.Tier::label)
            .containsExactly("480p", "360p");
    }

    @Test
    void sourceBelowThresholdGenerates360pOnly() {
        List<ResolutionLadder.Tier> tiers = ResolutionLadder.forSourceHeight(360);
        assertThat(tiers).extracting(ResolutionLadder.Tier::label)
            .containsExactly("360p");
    }

    @Test
    void tier1080pHasCorrectDimensions() {
        ResolutionLadder.Tier tier = ResolutionLadder.forSourceHeight(1080).get(0);
        assertThat(tier.width()).isEqualTo(1920);
        assertThat(tier.height()).isEqualTo(1080);
        assertThat(tier.videoBitrate()).isEqualTo("5000k");
        assertThat(tier.audioBitrate()).isEqualTo("192k");
    }
}
