package com.tinniestudio.worker.ffmpeg;

/**
 * Thrown when an external process (ffmpeg/ffprobe) does not complete within
 * the configured timeout and had to be forcibly killed. Treated as a
 * retryable processing failure by {@code VideoProcessingService}.
 */
public class ProcessTimeoutException extends RuntimeException {
    public ProcessTimeoutException(String message) {
        super(message);
    }
}
