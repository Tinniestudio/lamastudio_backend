package com.tinniestudio.api.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.auth.dto.RegisterRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that {@code AuthController#register} is actually protected by {@code @RateLimit}
 * (batch 18 #4 called this out as a missing gap — register had no rate limit at all, unlike
 * login/forgot-password/resend-verification which were already covered).
 *
 * <p>Unlike {@link AuthIntegrationTest}, this test does NOT mock {@code RateLimiterService} —
 * it lets requests flow through the real {@code RedisRateLimiterService} (against the Redis
 * instance the other Redis-backed integration tests in this module already assume is reachable
 * at localhost:6379) so the {@code @RateLimit} annotation on {@code register} is genuinely
 * exercised end-to-end, the same way a real client hitting the limit would be.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@ExtendWith(SpringExtension.class)
class AuthRegisterRateLimitIntegrationTest {

    private static final String CONTEXT_PATH = "/api/v1";
    private static final String RATE_LIMIT_KEY =
            "ratelimit:com.tinniestudio.api.modules.auth.controller.AuthController.register:ip:127.0.0.1";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.data.redis.url", () -> "redis://localhost:6379");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Avoid real email API calls during tests
    @MockBean
    private com.tinniestudio.api.modules.auth.service.EmailService emailService;

    @BeforeEach
    @AfterEach
    void clearRateLimitCounter() {
        // Register uses IP_ONLY, so every request from this test's MockMvc client (127.0.0.1)
        // shares one key. Clear it before/after so runs don't bleed into each other.
        redisTemplate.delete(RATE_LIMIT_KEY);
    }

    @Test
    @DisplayName("register is rate limited: exceeding the configured max returns 429")
    void registerIsRateLimited() throws Exception {
        // Configured on AuthController#register as
        // @RateLimit(maxRequests = 5, windowMinutes = 15, keyStrategy = "IP_ONLY")
        int maxRequests = 5;

        for (int i = 0; i < maxRequests; i++) {
            mockMvc.perform(post(CONTEXT_PATH + "/auth/register")
                            .contextPath(CONTEXT_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRegisterRequest())))
                    .andReturn();
            // Response status is irrelevant here (a duplicate email would 409, a fresh one 201) —
            // what matters is that the rate limiter counts every attempt regardless of outcome.
        }

        // One more request beyond the limit must be rejected with 429.
        mockMvc.perform(post(CONTEXT_PATH + "/auth/register")
                        .contextPath(CONTEXT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRegisterRequest())))
                .andExpect(status().isTooManyRequests());
    }

    private RegisterRequest buildRegisterRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("ratelimit+" + UUID.randomUUID() + "@example.com");
        request.setPassword("Password1!");
        request.setFirstName("Rate");
        request.setLastName("Limited");
        return request;
    }
}
