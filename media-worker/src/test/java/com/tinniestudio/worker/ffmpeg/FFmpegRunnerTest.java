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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
            when(processRunner.run(anyList(), anyLong())).thenReturn("");

            runner.transcode("/tmp/input.mp4", "/tmp/jobs/abc/1080p",
                1920, 1080, "5000k", "192k", 1800);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processRunner).run(captor.capture(), eq(1800L));
            List<String> cmd = captor.getValue();

            assertThat(cmd).contains("/usr/bin/ffmpeg");
            assertThat(cmd).contains("-i", "/tmp/input.mp4");
            assertThat(cmd).contains("-preset", "veryfast");
            assertThat(cmd).contains("-b:v", "5000k");
            assertThat(cmd).contains("-b:a", "192k");
            assertThat(cmd).contains("-hls_time", "6");
            assertThat(cmd).contains("-hls_playlist_type", "vod");
            assertThat(cmd.get(cmd.size() - 1)).endsWith("playlist.m3u8");
        }

        @Test
        void buildsCorrectScaleFilterFor720p() throws Exception {
            when(processRunner.run(anyList(), anyLong())).thenReturn("");

            runner.transcode("/tmp/input.mp4", "/tmp/jobs/abc/720p",
                1280, 720, "2800k", "128k", 1800);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processRunner).run(captor.capture(), anyLong());
            List<String> cmd = captor.getValue();

            assertThat(cmd).contains("-vf", "scale=1280:720");
        }

        @Test
        void passesTheGivenTimeoutThroughToProcessRunner() throws Exception {
            when(processRunner.run(anyList(), anyLong())).thenReturn("");

            runner.transcode("/tmp/input.mp4", "/tmp/jobs/abc/1080p",
                1920, 1080, "5000k", "192k", 5400);

            verify(processRunner).run(anyList(), eq(5400L));
        }
    }

    @Nested
    class generateThumbnail {
        @Test
        void buildsCorrectThumbnailCommand() throws Exception {
            when(processRunner.run(anyList(), anyLong())).thenReturn("");

            runner.generateThumbnail("/tmp/input.mp4", "/tmp/jobs/abc/thumbnail.jpg", 1800);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processRunner).run(captor.capture(), eq(1800L));
            List<String> cmd = captor.getValue();

            assertThat(cmd).contains("/usr/bin/ffmpeg");
            assertThat(cmd).contains("-ss", "00:00:05");
            assertThat(cmd).contains("-vframes", "1");
            assertThat(cmd.get(cmd.size() - 1)).endsWith("thumbnail.jpg");
        }
    }
}
