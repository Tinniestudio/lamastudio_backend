package com.lamastudio.backend.exception;

/**
 * Exception thrown when rate limit is exceeded.
 */
public class RateLimitExceededException extends RuntimeException {
    private final int retryAfterSeconds;
    private final int maxRequests;
    private final int windowMinutes;

    public RateLimitExceededException(
            String message,
            int retryAfterSeconds,
            int maxRequests,
            int windowMinutes) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.maxRequests = maxRequests;
        this.windowMinutes = windowMinutes;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }
}
