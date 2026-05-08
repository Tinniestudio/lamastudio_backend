package com.lamastudio.backend.shared.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.lamastudio.backend.shared.exception.RateLimitExceededException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * AOP aspect for rate limiting.
 * Intercepts methods annotated with @RateLimit and enforces rate limits.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RateLimiterService rateLimiterService;

    @Around("@annotation(rateLimit)")
    public Object aroundRateLimit(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        String endpoint = pjp.getSignature().getName();
        String keyStrategy = rateLimit.keyStrategy();
        int maxRequests = rateLimit.maxRequests();
        int windowMinutes = rateLimit.windowMinutes();

        // Build rate limit key based on strategy
        String rateLimitKey = buildRateLimitKey(endpoint, keyStrategy);

        log.debug("Rate limit check: endpoint={}, key={}, max={}, window={}min",
                endpoint, rateLimitKey, maxRequests, windowMinutes);

        // Check and increment
        boolean allowed = rateLimiterService.checkAndIncrement(rateLimitKey, maxRequests, windowMinutes);

        if (!allowed) {
            int retryAfter = rateLimiterService.getRetryAfterSeconds(rateLimitKey, windowMinutes);
            String message = rateLimit.errorMessage();
            if (message.isEmpty()) {
                message = String.format("Rate limit exceeded. Maximum %d requests per %d minute(s) allowed.",
                        maxRequests, windowMinutes);
            }

            log.warn("Rate limit exceeded: endpoint={}, key={}, retryAfter={}s",
                    endpoint, rateLimitKey, retryAfter);

            throw new RateLimitExceededException(message, retryAfter, maxRequests, windowMinutes);
        }

        // Proceed with method execution
        return pjp.proceed();
    }

    /**
     * Build rate limit key based on strategy.
     *
     * @param endpoint the endpoint name
     * @param strategyName the key strategy name
     * @return the rate limit key
     */
    private String buildRateLimitKey(String endpoint, String strategyName) {
        KeyStrategy strategy = KeyStrategy.valueOf(strategyName);

        return switch (strategy) {
            case USER_OR_IP -> {
                // Use userId if authenticated, IP if not
                String userId = getAuthenticatedUserId();
                if (userId != null) {
                    yield endpoint + ":user:" + userId;
                } else {
                    yield endpoint + ":ip:" + getClientIp();
                }
            }
            case IP_ONLY -> endpoint + ":ip:" + getClientIp();
            case USER_ONLY -> {
                String userId = getAuthenticatedUserId();
                if (userId == null) {
                    throw new IllegalStateException("USER_ONLY strategy requires authentication");
                }
                yield endpoint + ":user:" + userId;
            }
            case ENDPOINT_ONLY -> endpoint + ":global";
        };
    }

    /**
     * Extract authenticated user ID from SecurityContext.
     *
     * @return userId or null if not authenticated
     */
    private String getAuthenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            Object principal = auth.getPrincipal();
            if (principal instanceof org.springframework.security.core.userdetails.UserDetails) {
                // For JWT auth, the principal is the username (which we could parse to userId)
                // For now, use the username
                return auth.getName();
            }
        }
        return null;
    }

    /**
     * Extract client IP address from request.
     * Respects X-Forwarded-For and X-Real-IP headers (for proxies).
     *
     * @return client IP address or "unknown" if not available
     */
    private String getClientIp() {
        HttpServletRequest request = null;
        ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attr != null) {
            request = attr.getRequest();
        }

        if (request == null) {
            return "unknown";
        }

        // Check X-Forwarded-For header (for proxies)
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        // Check X-Real-IP header
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        // Fallback to direct remote address
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : "unknown";
    }
}
