package com.tinniestudio.api.shared.ratelimit;

/**
 * Interface for rate limiting implementations.
 * Handles checking and incrementing request counts.
 */
public interface RateLimiterService {

    /**
     * Check if request is allowed and increment counter if allowed.
     *
     * @param key the unique rate limit key (based on strategy)
     * @param maxRequests maximum requests allowed in window
     * @param windowMinutes time window in minutes
     * @return true if allowed, false if limit exceeded
     */
    boolean checkAndIncrement(String key, int maxRequests, int windowMinutes);

    /**
     * Get the retry-after time in seconds for a key that has hit the limit.
     *
     * @param key the unique rate limit key
     * @param windowMinutes time window in minutes
     * @return seconds until next request can be made
     */
    int getRetryAfterSeconds(String key, int windowMinutes);

    /**
     * Get current request count for a key.
     *
     * @param key the unique rate limit key
     * @return current request count
     */
    int getCurrentCount(String key);
}
