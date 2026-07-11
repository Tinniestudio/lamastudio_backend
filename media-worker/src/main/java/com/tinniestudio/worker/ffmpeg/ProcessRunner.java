package com.tinniestudio.worker.ffmpeg;

import java.io.IOException;
import java.util.List;

public interface ProcessRunner {
    /**
     * Executes a command, waits for completion, returns stdout.
     * Throws RuntimeException if exit code != 0.
     */
    String run(List<String> command) throws IOException, InterruptedException;
}
