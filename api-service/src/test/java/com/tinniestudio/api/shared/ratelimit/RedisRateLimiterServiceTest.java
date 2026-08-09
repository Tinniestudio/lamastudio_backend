package com.tinniestudio.api.shared.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for two bugs in {@link RedisRateLimiterService}:
 *
 * <p>1. The previous implementation did INCR and EXPIRE as two separate Redis round-trips.
 * If the process died (or threw) between them, the key was left permanently without a TTL —
 * since the "set TTL" branch only ever fires when count==1, that key would never get a TTL
 * again, locking that identity out until manual Redis intervention. The fix runs both commands
 * atomically, server-side, via a single Lua script (one round-trip, no gap).
 *
 * <p>2. The previous implementation had a blanket {@code catch (Exception e) { return true; }}
 * that failed OPEN (silently disabled rate limiting) for ANY exception, not just genuine Redis
 * connectivity loss. The fix narrows fail-open to {@link RedisConnectionFailureException} only;
 * any other exception now fails CLOSED (denies the request).
 */
class RedisRateLimiterServiceTest {

    // ── Part 1: real Redis — proves the atomic INCR+EXPIRE round-trip ─────────────────────────

    private static LettuceConnectionFactory connectionFactory;
    private static RedisTemplate<String, Object> realRedisTemplate;

    @BeforeAll
    static void startRedisConnection() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        realRedisTemplate = template;
    }

    @AfterAll
    static void stopRedisConnection() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("First increment atomically sets a TTL in the same round-trip")
    void firstIncrementAlwaysSetsTtl() {
        RedisRateLimiterService service = new RedisRateLimiterService(realRedisTemplate);
        String key = "test:" + UUID.randomUUID();

        try {
            boolean allowed = service.checkAndIncrement(key, 5, 1);

            assertThat(allowed).isTrue();

            Long ttl = realRedisTemplate.getExpire("ratelimit:" + key, TimeUnit.SECONDS);
            assertThat(ttl)
                    .as("TTL must be set immediately after the very first increment — this is exactly "
                            + "the case the old two-round-trip implementation could miss if it crashed "
                            + "between INCR and EXPIRE")
                    .isNotNull()
                    .isGreaterThan(0)
                    .isLessThanOrEqualTo(60L);
        } finally {
            realRedisTemplate.delete("ratelimit:" + key);
        }
    }

    @Test
    @DisplayName("Subsequent increments within the window do not reset the original TTL")
    void subsequentIncrementsPreserveOriginalTtl() throws InterruptedException {
        RedisRateLimiterService service = new RedisRateLimiterService(realRedisTemplate);
        String key = "test:" + UUID.randomUUID();

        try {
            service.checkAndIncrement(key, 5, 5); // window = 300s
            Thread.sleep(1100); // let time pass so a reset TTL would be detectable
            service.checkAndIncrement(key, 5, 5);

            Long ttl = realRedisTemplate.getExpire("ratelimit:" + key, TimeUnit.SECONDS);
            assertThat(ttl).isNotNull();
            assertThat(ttl).isLessThan(300L);
        } finally {
            realRedisTemplate.delete("ratelimit:" + key);
        }
    }

    @Test
    @DisplayName("Requests beyond maxRequests are denied")
    void deniesOnceLimitExceeded() {
        RedisRateLimiterService service = new RedisRateLimiterService(realRedisTemplate);
        String key = "test:" + UUID.randomUUID();

        try {
            for (int i = 0; i < 3; i++) {
                assertThat(service.checkAndIncrement(key, 3, 1)).isTrue();
            }
            assertThat(service.checkAndIncrement(key, 3, 1)).isFalse();
        } finally {
            realRedisTemplate.delete("ratelimit:" + key);
        }
    }

    // ── Part 2: mocked RedisTemplate — proves narrowed exception handling ──────────────────────

    @Test
    @DisplayName("Redis connectivity failure fails OPEN (request allowed) by design")
    @SuppressWarnings("unchecked")
    void connectivityFailureFailsOpen() {
        RedisTemplate<String, Object> mockRedisTemplate = mock(RedisTemplate.class);
        when(mockRedisTemplate.execute(any(RedisScript.class), any(), any(), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        RedisRateLimiterService service = new RedisRateLimiterService(mockRedisTemplate);

        boolean allowed = service.checkAndIncrement("some-key", 5, 1);

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("A non-connectivity exception (e.g. a serialization bug) fails CLOSED, not open")
    @SuppressWarnings("unchecked")
    void nonConnectivityExceptionFailsClosed() {
        RedisTemplate<String, Object> mockRedisTemplate = mock(RedisTemplate.class);
        when(mockRedisTemplate.execute(any(RedisScript.class), any(), any(), anyList(), any()))
                .thenThrow(new IllegalStateException("unrelated bug — not a connectivity failure"));

        RedisRateLimiterService service = new RedisRateLimiterService(mockRedisTemplate);

        boolean allowed = service.checkAndIncrement("some-key", 5, 1);

        assertThat(allowed)
                .as("A bug that has nothing to do with Redis connectivity must not be silently "
                        + "treated the same as 'Redis is down' — it must deny the request")
                .isFalse();
    }
}
