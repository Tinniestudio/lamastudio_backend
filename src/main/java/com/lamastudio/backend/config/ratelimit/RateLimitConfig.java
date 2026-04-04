package com.lamastudio.backend.config.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Configuration for rate limiting.
 * Enables AOP and conditionally configures Redis-based rate limiter.
 */
@Configuration
@EnableAspectJAutoProxy
public class RateLimitConfig {

    /**
     * Register RateLimitAspect bean to enable @RateLimit annotation processing.
     *
     * @param rateLimiterService the rate limiter service
     * @return RateLimitAspect
     */
    @Bean
    public RateLimitAspect rateLimitAspect(RateLimiterService rateLimiterService) {
        return new RateLimitAspect(rateLimiterService);
    }
}
