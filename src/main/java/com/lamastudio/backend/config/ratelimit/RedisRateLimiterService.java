package com.lamastudio.backend.config.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redis-based rate limiter service.
 * Uses Redis INCR with TTL for distributed rate limiting.
 * Works across multiple instances.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisRateLimiterService implements RateLimiterService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String RATE_LIMIT_PREFIX = "ratelimit:";

    /**
     * Check and increment request count using Redis.
     * Algorithm: sliding window with Redis INCR and TTL.
     *
     * @param key the unique rate limit key
     * @param maxRequests maximum requests allowed
     * @param windowMinutes time window in minutes
     * @return true if allowed, false if limit exceeded
     */
    @Override
    public boolean checkAndIncrement(String key, int maxRequests, int windowMinutes) {
        String redisKey = RATE_LIMIT_PREFIX + key;
        long windowSeconds = (long) windowMinutes * 60;

        try {
            // Increment the counter
            Long count = redisTemplate.opsForValue().increment(redisKey);

            // Set TTL on first increment
            if (count != null && count == 1) {
                redisTemplate.expire(redisKey, windowSeconds, TimeUnit.SECONDS);
            }

            // Check if limit exceeded
            if (count != null && count > maxRequests) {
                log.warn("Rate limit exceeded for key: {} (count: {}/{}, window: {} min)",
                        key, count, maxRequests, windowMinutes);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.error("Error checking rate limit for key: {}", key, e);
            // Fail open - allow request if Redis fails
            return true;
        }
    }

    /**
     * Calculate retry-after seconds based on TTL remaining.
     *
     * @param key the unique rate limit key
     * @param windowMinutes time window in minutes
     * @return seconds until next request allowed
     */
    @Override
    public int getRetryAfterSeconds(String key, int windowMinutes) {
        String redisKey = RATE_LIMIT_PREFIX + key;

        try {
            Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
            if (ttl != null && ttl > 0) {
                return Math.max(1, ttl.intValue());
            }
            // If no TTL, return window duration
            return windowMinutes * 60;
        } catch (Exception e) {
            log.error("Error getting retry-after for key: {}", key, e);
            return windowMinutes * 60;
        }
    }

    /**
     * Get current request count from Redis.
     *
     * @param key the unique rate limit key
     * @return current count
     */
    @Override
    public int getCurrentCount(String key) {
        String redisKey = RATE_LIMIT_PREFIX + key;

        try {
            Object count = redisTemplate.opsForValue().get(redisKey);
            if (count instanceof Number) {
                return ((Number) count).intValue();
            }
            return 0;
        } catch (Exception e) {
            log.error("Error getting current count for key: {}", key, e);
            return 0;
        }
    }
}
