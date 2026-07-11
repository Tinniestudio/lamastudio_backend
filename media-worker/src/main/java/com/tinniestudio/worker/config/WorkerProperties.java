package com.tinniestudio.worker.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "worker")
@Getter @Setter
public class WorkerProperties {
    private Processing processing = new Processing();
    private Ffmpeg ffmpeg = new Ffmpeg();

    @Getter @Setter
    public static class Processing {
        private int maxJobConcurrency = 2;
        private String tempDir = "/tmp/tinniestudio";
        private int maxDurationSeconds = 14400;
    }

    @Getter @Setter
    public static class Ffmpeg {
        private String path = "/usr/bin/ffmpeg";
        private String ffprobePath = "/usr/bin/ffprobe";
        private int hlsSegmentDuration = 6;
    }
}
