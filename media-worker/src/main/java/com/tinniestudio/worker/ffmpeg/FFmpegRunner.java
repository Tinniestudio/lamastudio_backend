package com.tinniestudio.worker.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class FFmpegRunner {

    private final ProcessRunner processRunner;
    private final String ffmpegPath;
    private final int hlsSegmentDuration;

    public FFmpegRunner(ProcessRunner processRunner,
                        @Value("${worker.ffmpeg.path:/usr/bin/ffmpeg}") String ffmpegPath,
                        @Value("${worker.ffmpeg.hls-segment-duration:6}") int hlsSegmentDuration) {
        this.processRunner = processRunner;
        this.ffmpegPath = ffmpegPath;
        this.hlsSegmentDuration = hlsSegmentDuration;
    }

    public void transcode(String inputPath, String outputDir,
                          int width, int height,
                          String videoBitrate, String audioBitrate) throws Exception {
        String segmentPattern = outputDir + "/segment_%04d.ts";
        String playlistPath   = outputDir + "/playlist.m3u8";

        List<String> cmd = new ArrayList<>(List.of(
            ffmpegPath,
            "-i", inputPath,
            "-vf", "scale=" + width + ":" + height,
            "-c:v", "libx264",
            "-b:v", videoBitrate,
            "-maxrate", videoBitrate,
            "-bufsize", String.valueOf(parseBitrateKbps(videoBitrate) * 2) + "k",
            "-c:a", "aac",
            "-b:a", audioBitrate,
            "-hls_time", String.valueOf(hlsSegmentDuration),
            "-hls_playlist_type", "vod",
            "-hls_segment_filename", segmentPattern,
            playlistPath
        ));
        log.info("Transcoding → {} at {}x{} {}", outputDir, width, height, videoBitrate);
        processRunner.run(cmd);
    }

    public void generateThumbnail(String inputPath, String outputPath) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
            ffmpegPath,
            "-i", inputPath,
            "-ss", "00:00:05",
            "-vframes", "1",
            "-q:v", "2",
            outputPath
        ));
        log.info("Generating thumbnail → {}", outputPath);
        processRunner.run(cmd);
    }

    private int parseBitrateKbps(String bitrate) {
        return Integer.parseInt(bitrate.replace("k", ""));
    }
}
