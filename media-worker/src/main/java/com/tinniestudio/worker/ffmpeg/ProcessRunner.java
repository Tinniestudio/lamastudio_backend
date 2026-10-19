package com.tinniestudio.worker.ffmpeg;

import java.io.IOException;
import java.util.List;

public interface ProcessRunner {
    /**
     * Executes a command, waits up to timeoutSeconds for completion, returns stdout.
     * Throws RuntimeException if exit code != 0, or ProcessTimeoutException if the
     * process doesn't finish within timeoutSeconds.
     */
    String run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException;
}
