package com.tinniestudio.api.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;
import org.springframework.core.env.Environment;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import io.lettuce.core.RedisURI;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis configuration for rate limiting and caching.
 *
 * Key design principles:
 * - SSL driven by rediss:// URL scheme only (no duplicate ssl.enabled flag)
 * - Single RedisTemplate shared by rate limiter and app cache
 * - Single CacheManager with 30min default TTL (@EnableCaching)
 * - Lettuce with commons-pool2 connection pool
 * - Graceful degradation: Redis failure does NOT crash the app
 * - Fail fast: REDIS_URL has no empty default
 * - Startup logging: clearly log resolved config
 */
@Slf4j
@Configuration
@EnableCaching
public class RedisConfig {

    private static final long DEFAULT_CACHE_TTL_MINUTES = 30;

    /**
     * GenericJackson2JsonRedisSerializer's default ObjectMapper embeds polymorphic type info
     * PROPERTY-style ({@code "@class": "..."} on each JSON object). That works for a single
     * object but cannot represent a top-level JSON array's type (there's no property slot on an
     * array token) — so serializing a bare List writes it with no outer type wrapper, and the
     * very next read of that same value fails ("expected VALUE_STRING ... got START_OBJECT")
     * because the reader expects type info it never got. Any @Cacheable method returning a List
     * (categories, content-list, discover, search, recommendations) hit this on every cache read
     * following a cache write, deterministically. WRAPPER_ARRAY style wraps collections as
     * {@code ["java.util.ArrayList", [...]]}, which round-trips correctly.
     */
    static ObjectMapper cacheObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        // allowIfSubType(Object.class) would accept every class in existence — equivalent to no
        // validation at all, and a polymorphic-deserialization gadget risk if Redis is ever
        // compromised or an attacker-influenced value ends up cached. Scope to our own DTOs plus
        // the JDK types DefaultTyping.EVERYTHING actually wraps: collections/maps, java.time
        // values, UUID, and the boxed scalar types (EVERYTHING — unlike NON_FINAL — wraps these
        // too, since they're technically final-but-still-typed under this policy). Nothing else
        // is ever legitimately cached here.
        BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.tinniestudio.")
                .allowIfSubType(java.util.Collection.class)
                .allowIfSubType(java.util.Map.class)
                .allowIfSubType(java.time.temporal.Temporal.class)
                .allowIfSubType(java.util.UUID.class)
                .allowIfSubType(Number.class)
                .allowIfSubType(Boolean.class)
                .allowIfSubType(String.class)
                .allowIfSubType(Character.class)
                .build();
        mapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.WRAPPER_ARRAY);
        return mapper;
    }

    /**
     * Configure RedisTemplate with String keys and JSON values.
     * Shared by rate limiting and general app caching.
     *
     * @param connectionFactory Redis connection factory
     * @return configured RedisTemplate
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(cacheObjectMapper());

        // String keys for clarity and interoperability
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // JSON values for complex objects (rate limit counters, cached objects)
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        log.debug("RedisTemplate configured with String keys and JSON values");
        return template;
    }

    /**
     * Configure CacheManager with 30-minute default TTL.
     * Keeps it simple: no per-cache TTL maps, no cluster config.
     *
     * @param connectionFactory Redis connection factory
     * @return CacheManager
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(DEFAULT_CACHE_TTL_MINUTES))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer(cacheObjectMapper())));

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put("categories",        config.entryTtl(Duration.ofMinutes(10)));
        cacheConfigs.put("content-types",     config.entryTtl(Duration.ofMinutes(10)));
        cacheConfigs.put("homepage-sections", config.entryTtl(Duration.ofMinutes(5)));
        cacheConfigs.put("content-list",      config.entryTtl(Duration.ofMinutes(2)));
        cacheConfigs.put("content-detail",    config.entryTtl(Duration.ofMinutes(5)));
        cacheConfigs.put("discover",          config.entryTtl(Duration.ofMinutes(2)));
        cacheConfigs.put("search",            config.entryTtl(Duration.ofSeconds(60)));
        cacheConfigs.put("recommendations",   config.entryTtl(Duration.ofMinutes(10)));

        log.info("CacheManager configured with default TTL: {} minutes + per-cache overrides",
                DEFAULT_CACHE_TTL_MINUTES);
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }

    /**
     * Startup connectivity check and config logging.
     * Non-fatal: Redis failure does NOT crash the app. Rate limiter and cache
     * degrade gracefully.
     *
     * @param connectionFactory Redis connection factory
     * @param environment       Spring Environment for reading env vars
     * @return ApplicationRunner
     */
    @Bean
    public ApplicationRunner redisConnectivityCheck(
            RedisConnectionFactory connectionFactory,
            Environment environment) {
        return args -> {
            logResolvedRedisConfig(environment);
            testRedisConnection(connectionFactory);
        };
    }

    /**
     * Test Redis connection during startup. Logs success or failure without
     * throwing.
     */
    private void testRedisConnection(RedisConnectionFactory connectionFactory) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String ping = connection.ping();
            log.info("✓ Redis connection established successfully. Ping response: {}", ping);
        } catch (Exception ex) {
            log.warn("✗ Failed to connect to Redis during startup. " +
                    "Rate limiting and caching will degrade gracefully. " +
                    "Error: {}", ex.getMessage());
            log.debug("Redis connection failure details", ex);
        }
    }

    /**
     * Log the resolved Redis configuration from REDIS_URL env var.
     * Extracts host, port, SSL info from the URL without duplicating ssl.enabled.
     */
    private void logResolvedRedisConfig(Environment environment) {
        String redisUrl = environment.getProperty("spring.data.redis.url");

        if (!StringUtils.hasText(redisUrl)) {
            log.error("✗ REDIS_URL is not set. The application requires spring.data.redis.url to be configured. " +
                    "Set the REDIS_URL environment variable.");
            return;
        }

        try {
            RedisURI uri = RedisURI.create(redisUrl);
            String scheme = uri.isSsl() ? "rediss://" : "redis://";
            String host = uri.getHost();
            int port = uri.getPort();
            boolean ssl = uri.isSsl();

            log.info("✓ Redis config resolved from spring.data.redis.url: {}{}:{} (SSL: {})",
                    scheme, host, port, ssl);

            if (!ssl) {
                log.debug("Redis running without SSL - OK for self-hosted or non-TLS providers.");
            }
        } catch (Exception ex) {
            log.error("✗ Failed to parse spring.data.redis.url. Invalid format: {}. Error: {}", redisUrl,
                    ex.getMessage());
            log.debug("Redis URL parsing failure details", ex);
        }
    }
}
