package com.tinniestudio.worker.ffmpeg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FFprobeRunnerTest {

    @Mock
    private ProcessRunner processRunner;

    private FFprobeRunner runner;

    @BeforeEach
    void setUp() {
        runner = new FFprobeRunner(processRunner, "/usr/bin/ffprobe", 1800L);
    }

    @Nested
    class probe {
        @Test
        void parsesMetadataFromFfprobeJsonOutput() throws Exception {
            String json = """
                {
                  "streams": [
                    {"codec_type":"video","codec_name":"h264","width":1920,"height":1080,
                     "bit_rate":"5000000"},
                    {"codec_type":"audio","codec_name":"aac"}
                  ],
                  "format": {"duration":"120.5","size":"75000000"}
                }
                """;
            when(processRunner.run(anyList(), anyLong())).thenReturn(json);

            FFprobeRunner.VideoMetadata meta = runner.probe("/tmp/test.mp4", true);

            assertThat(meta.durationSeconds()).isEqualTo(120);
            assertThat(meta.width()).isEqualTo(1920);
            assertThat(meta.height()).isEqualTo(1080);
            assertThat(meta.codec()).isEqualTo("h264");
            assertThat(meta.bitrate()).isEqualTo(5000000L);
            assertThat(meta.hasAudio()).isTrue();
        }

        @Test
        void throwsValidationExceptionWhenNoVideoStream() throws Exception {
            String json = """
                {
                  "streams": [{"codec_type":"audio","codec_name":"aac"}],
                  "format": {"duration":"30.0","size":"1000000"}
                }
                """;
            when(processRunner.run(anyList(), anyLong())).thenReturn(json);

            assertThatThrownBy(() -> runner.probe("/tmp/audio-only.mp3", true))
                .isInstanceOf(FFprobeRunner.ValidationException.class)
                .hasMessageContaining("no video stream");
        }

        @Test
        void throwsValidationExceptionWhenNoStreamsArray() throws Exception {
            String json = """
                {
                  "format": {"duration":"30.0","size":"1000000"}
                }
                """;
            when(processRunner.run(anyList(), anyLong())).thenReturn(json);

            assertThatThrownBy(() -> runner.probe("/tmp/broken.bin", true))
                .isInstanceOf(FFprobeRunner.ValidationException.class)
                .hasMessageContaining("no streams array");
        }

        @Test
        void throwsValidationExceptionWhenNoAudioStreamAndAudioRequired() throws Exception {
            String json = """
                {
                  "streams": [{"codec_type":"video","codec_name":"h264","width":1280,"height":720,
                               "bit_rate":"2800000"}],
                  "format": {"duration":"60.0","size":"21000000"}
                }
                """;
            when(processRunner.run(anyList(), anyLong())).thenReturn(json);

            assertThatThrownBy(() -> runner.probe("/tmp/silent.mp4", true))
                .isInstanceOf(FFprobeRunner.ValidationException.class)
                .hasMessageContaining("no audio stream");
        }

        @Test
        void allowsNoAudioStreamWhenAudioNotRequired() throws Exception {
            String json = """
                {
                  "streams": [{"codec_type":"video","codec_name":"h264","width":1280,"height":720,
                               "bit_rate":"2800000"}],
                  "format": {"duration":"60.0","size":"21000000"}
                }
                """;
            when(processRunner.run(anyList(), anyLong())).thenReturn(json);

            FFprobeRunner.VideoMetadata meta = runner.probe("/tmp/silent-trailer.mp4", false);

            assertThat(meta.hasAudio()).isFalse();
            assertThat(meta.width()).isEqualTo(1280);
            assertThat(meta.height()).isEqualTo(720);
        }

        @Test
        void stillRequiresVideoStreamEvenWhenAudioNotRequired() throws Exception {
            String json = """
                {
                  "streams": [{"codec_type":"audio","codec_name":"aac"}],
                  "format": {"duration":"30.0","size":"1000000"}
                }
                """;
            when(processRunner.run(anyList(), anyLong())).thenReturn(json);

            assertThatThrownBy(() -> runner.probe("/tmp/audio-only.mp3", false))
                .isInstanceOf(FFprobeRunner.ValidationException.class)
                .hasMessageContaining("no video stream");
        }

        @Test
        void passesTheConfiguredTimeoutThroughToProcessRunner() throws Exception {
            String json = """
                {
                  "streams": [
                    {"codec_type":"video","codec_name":"h264","width":1920,"height":1080,
                     "bit_rate":"5000000"},
                    {"codec_type":"audio","codec_name":"aac"}
                  ],
                  "format": {"duration":"120.5","size":"75000000"}
                }
                """;
            when(processRunner.run(anyList(), eq(1800L))).thenReturn(json);

            runner.probe("/tmp/test.mp4", true);

            verify(processRunner).run(anyList(), eq(1800L));
        }
    }
}
