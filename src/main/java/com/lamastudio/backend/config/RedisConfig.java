package com.lamastudio.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for rate limiting and caching.
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * Configure RedisTemplate with string key serialization.
     *
     * @param connectionFactory Redis connection factory
     * @return configured RedisTemplate
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use StringRedisSerializer for keys
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

        // String serialization for keys
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // JSON serialization for values
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Validate Redis connectivity during application startup.
     * This is a non-fatal check to avoid breaking production if Redis is unavailable.
     */
    @Bean
    public ApplicationRunner redisConnectivityCheck(RedisConnectionFactory connectionFactory) {
        return args -> {
            try (RedisConnection connection = connectionFactory.getConnection()) {
                String ping = connection.ping();
                log.info("Redis connection established successfully. Ping response: {}", ping);
            } catch (Exception ex) {
                log.error("Failed to connect to Redis during startup. Rate limiting will degrade gracefully. Error: {}",
                        ex.getMessage());
                log.debug("Redis connection failure details", ex);
            }
        };
    }
}
