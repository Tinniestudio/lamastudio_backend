package com.tinniestudio.worker.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
public class FFmpegRunner {

    // TODO (Batch 7): implement HLS transcoding command builder and executor
    public void transcode(String inputPath, String outputDir, int width, int height,
                          String videoBitrate, String audioBitrate) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("FFmpeg transcoding not yet implemented - coming in Batch 7");
    }

    private void execute(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg exited with code: " + exitCode);
        }
    }
}
