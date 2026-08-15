package com.tinniestudio.api.shared.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import java.util.Collections;
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
     * Atomically increments the counter and, on the very first increment, sets its TTL —
     * both in a single Redis round-trip. Lua scripts run atomically on the Redis server, so
     * there is no window between INCR and EXPIRE where a crash (or any exception) could leave
     * the key incremented but without a TTL. That gap was the root cause of a permanent-lockout
     * bug: since the TTL is only ever (re)set when count==1, a key that missed it once would
     * NEVER get a TTL again, capping that identity out until manual Redis intervention.
     */
    private static final RedisScript<Long> INCREMENT_AND_EXPIRE_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]) " +
            "if current == 1 then " +
            "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
            "end " +
            "return current",
            Long.class);

    // The template's default value serializer is JSON (see RedisConfig) — reusing it for script
    // args (RedisTemplate#execute(script, keys, args) does this by default) would wrap our plain
    // integer TTL argument in JSON quotes, e.g. "900" instead of 900, which Lua's EXPIRE then
    // rejects as "not an integer". Script args/result need plain Redis wire types instead.
    private static final RedisSerializer<String> SCRIPT_ARGS_SERIALIZER = new StringRedisSerializer();
    private static final RedisSerializer<Long> SCRIPT_RESULT_SERIALIZER = new GenericToStringSerializer<>(Long.class);

    /**
     * Check and increment request count using Redis.
     * Algorithm: fixed window with an atomic Redis-side INCR + conditional EXPIRE.
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
            Long count = redisTemplate.execute(INCREMENT_AND_EXPIRE_SCRIPT,
                    SCRIPT_ARGS_SERIALIZER, SCRIPT_RESULT_SERIALIZER,
                    Collections.singletonList(redisKey), String.valueOf(windowSeconds));

            // Check if limit exceeded
            if (count != null && count > maxRequests) {
                log.warn("Rate limit exceeded for key: {} (count: {}/{}, window: {} min)",
                        key, count, maxRequests, windowMinutes);
                return false;
            }

            return true;
        } catch (RedisConnectionFailureException e) {
            // Redis is genuinely unreachable. Per RedisConfig's graceful-degradation design,
            // rate limiting must not take the app down — fail OPEN here, deliberately and only
            // for this specific failure mode.
            log.error("Redis connection failure while checking rate limit for key: {}. Failing open.", key, e);
            return true;
        } catch (Exception e) {
            // Anything else (script error, serialization bug, unexpected runtime exception) is
            // NOT a connectivity problem. Treating it the same as "Redis is down" would silently
            // disable rate limiting for any latent bug. Fail CLOSED instead — deny the request.
            log.error("Unexpected error checking rate limit for key: {}. Failing closed (denying request).",
                    key, e);
            return false;
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
