package com.tinniestudio.worker.ffmpeg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FFmpegRunnerTest {

    @Mock ProcessRunner processRunner;

    private FFmpegRunner runner;

    @BeforeEach
    void setUp() {
        runner = new FFmpegRunner(processRunner, "/usr/bin/ffmpeg", 6);
    }

    @Nested
    class transcode {
        @Test
        void buildsCorrectHlsCommandFor1080p() throws Exception {
            when(processRunner.run(anyList())).thenReturn("");

            runner.transcode("/tmp/input.mp4", "/tmp/jobs/abc/1080p",
                1920, 1080, "5000k", "192k");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processRunner).run(captor.capture());
            List<String> cmd = captor.getValue();

            assertThat(cmd).contains("/usr/bin/ffmpeg");
            assertThat(cmd).contains("-i", "/tmp/input.mp4");
            assertThat(cmd).contains("-b:v", "5000k");
            assertThat(cmd).contains("-b:a", "192k");
            assertThat(cmd).contains("-hls_time", "6");
            assertThat(cmd).contains("-hls_playlist_type", "vod");
            assertThat(cmd.get(cmd.size() - 1)).endsWith("playlist.m3u8");
        }

        @Test
        void buildsCorrectScaleFilterFor720p() throws Exception {
            when(processRunner.run(anyList())).thenReturn("");

            runner.transcode("/tmp/input.mp4", "/tmp/jobs/abc/720p",
                1280, 720, "2800k", "128k");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processRunner).run(captor.capture());
            List<String> cmd = captor.getValue();

            assertThat(cmd).contains("-vf", "scale=1280:720");
        }
    }

    @Nested
    class generateThumbnail {
        @Test
        void buildsCorrectThumbnailCommand() throws Exception {
            when(processRunner.run(anyList())).thenReturn("");

            runner.generateThumbnail("/tmp/input.mp4", "/tmp/jobs/abc/thumbnail.jpg");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processRunner).run(captor.capture());
            List<String> cmd = captor.getValue();

            assertThat(cmd).contains("/usr/bin/ffmpeg");
            assertThat(cmd).contains("-ss", "00:00:05");
            assertThat(cmd).contains("-vframes", "1");
            assertThat(cmd.get(cmd.size() - 1)).endsWith("thumbnail.jpg");
        }
    }
}
