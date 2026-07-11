package com.tinniestudio.worker.ffmpeg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class FFprobeRunner {

    private final ProcessRunner processRunner;
    private final String ffprobePath;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FFprobeRunner(ProcessRunner processRunner,
                         @Value("${worker.ffmpeg.ffprobe-path:/usr/bin/ffprobe}") String ffprobePath) {
        this.processRunner = processRunner;
        this.ffprobePath = ffprobePath;
    }

    public VideoMetadata probe(String inputPath) throws Exception {
        List<String> command = List.of(
            ffprobePath, "-v", "quiet",
            "-print_format", "json",
            "-show_streams", "-show_format",
            inputPath
        );
        String output = processRunner.run(command);
        return parseOutput(output);
    }

    private VideoMetadata parseOutput(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode streams = root.get("streams");
        JsonNode format = root.get("format");

        if (streams == null || streams.isNull() || !streams.isArray()) {
            throw new ValidationException("Probe failed: no streams array in ffprobe output");
        }

        JsonNode videoStream = null;
        boolean hasAudio = false;

        for (JsonNode stream : streams) {
            String codecType = stream.path("codec_type").asText();
            if ("video".equals(codecType) && videoStream == null) {
                videoStream = stream;
            } else if ("audio".equals(codecType)) {
                hasAudio = true;
            }
        }

        if (videoStream == null) {
            throw new ValidationException("Probe failed: no video stream found in " + json);
        }
        if (!hasAudio) {
            throw new ValidationException("Probe failed: no audio stream found in " + json);
        }

        int durationSeconds = (int) Double.parseDouble(format.path("duration").asText("0"));
        int width = videoStream.path("width").asInt();
        int height = videoStream.path("height").asInt();
        String codec = videoStream.path("codec_name").asText();
        long bitrate = videoStream.path("bit_rate").asLong(0);
        if (bitrate == 0) {
            bitrate = format.path("bit_rate").asLong(0);
        }

        return new VideoMetadata(durationSeconds, width, height, codec, bitrate, true);
    }

    public record VideoMetadata(
        int durationSeconds,
        int width,
        int height,
        String codec,
        long bitrate,
        boolean hasAudio
    ) {}

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }
}
