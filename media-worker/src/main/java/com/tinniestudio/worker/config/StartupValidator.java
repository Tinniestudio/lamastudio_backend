package com.tinniestudio.worker.config;

import com.tinniestudio.worker.ffmpeg.ProcessRunner;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupValidator {

    private final ProcessRunner processRunner;
    private final WorkerProperties workerProperties;

    @PostConstruct
    public void validateTools() {
        validateCommand(workerProperties.getFfmpeg().getPath(), "-version");
        validateCommand(workerProperties.getFfmpeg().getFfprobePath(), "-version");
        log.info("FFmpeg tools validated successfully");
    }

    private void validateCommand(String binaryPath, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(binaryPath);
            cmd.addAll(List.of(args));
            processRunner.run(cmd);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Required tool not found: " + binaryPath + " — " + e.getMessage(), e);
        }
    }
}
