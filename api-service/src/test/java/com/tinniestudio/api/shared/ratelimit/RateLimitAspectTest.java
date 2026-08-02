package com.tinniestudio.api.shared.ratelimit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Regression test for the rate-limit key collision bug: two controller methods with the
 * same bare method name (e.g. AuthController#login and AdminAuthController#login) must not
 * collide onto the same Redis rate-limit key.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {

    @Mock
    private RateLimiterService rateLimiterService;

    @Mock
    private ProceedingJoinPoint userLoginJoinPoint;

    @Mock
    private Signature userLoginSignature;

    @Mock
    private ProceedingJoinPoint adminLoginJoinPoint;

    @Mock
    private Signature adminLoginSignature;

    private RateLimitAspect rateLimitAspect;

    @BeforeEach
    void setUp() {
        rateLimitAspect = new RateLimitAspect(rateLimiterService);
    }

    @Test
    @DisplayName("Same-named methods on different classes produce different rate-limit keys")
    void differentClassesWithSameMethodNameProduceDifferentKeys() throws Throwable {
        when(userLoginJoinPoint.getSignature()).thenReturn(userLoginSignature);
        when(userLoginSignature.getName()).thenReturn("login");
        when(userLoginSignature.getDeclaringTypeName())
                .thenReturn("com.tinniestudio.api.modules.auth.controller.AuthController");

        when(adminLoginJoinPoint.getSignature()).thenReturn(adminLoginSignature);
        when(adminLoginSignature.getName()).thenReturn("login");
        when(adminLoginSignature.getDeclaringTypeName())
                .thenReturn("com.tinniestudio.api.modules.auth.admin.controller.AdminAuthController");

        when(rateLimiterService.checkAndIncrement(anyString(), anyInt(), anyInt())).thenReturn(true);

        RateLimit rateLimit = buildRateLimit("IP_ONLY", 10, 15);

        rateLimitAspect.aroundRateLimit(userLoginJoinPoint, rateLimit);
        rateLimitAspect.aroundRateLimit(adminLoginJoinPoint, rateLimit);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(rateLimiterService, org.mockito.Mockito.times(2))
                .checkAndIncrement(keyCaptor.capture(), anyInt(), anyInt());

        String userKey = keyCaptor.getAllValues().get(0);
        String adminKey = keyCaptor.getAllValues().get(1);

        assertThat(userKey).isNotEqualTo(adminKey);
        assertThat(userKey).contains("AuthController");
        assertThat(adminKey).contains("AdminAuthController");
    }

    private RateLimit buildRateLimit(String keyStrategy, int maxRequests, int windowMinutes) {
        return new RateLimit() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return RateLimit.class;
            }

            @Override
            public int maxRequests() {
                return maxRequests;
            }

            @Override
            public int windowMinutes() {
                return windowMinutes;
            }

            @Override
            public String keyStrategy() {
                return keyStrategy;
            }

            @Override
            public String errorMessage() {
                return "";
            }
        };
    }
}
