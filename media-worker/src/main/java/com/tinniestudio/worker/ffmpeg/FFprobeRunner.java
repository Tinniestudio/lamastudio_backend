package com.tinniestudio.worker.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FFprobeRunner {

    // TODO (Batch 7): run ffprobe -v quiet -print_format json -show_streams -show_format
    // and parse duration, width, height, codec, bitrate, hasAudio
    public VideoMetadata probe(String inputPath) {
        throw new UnsupportedOperationException("FFprobe not yet implemented - coming in Batch 7");
    }

    public record VideoMetadata(
            int durationSeconds,
            int width,
            int height,
            String codec,
            long bitrate,
            boolean hasAudio
    ) {}
}
