package com.tinniestudio.api.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.auth.dto.LoginRequest;
import com.tinniestudio.api.modules.auth.dto.RegisterRequest;
import com.tinniestudio.api.modules.user.dto.UpdateProfileRequest;
import com.tinniestudio.api.shared.cache.CacheService;
import com.tinniestudio.api.shared.ratelimit.RateLimiterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@ExtendWith(SpringExtension.class)
@DisplayName("UserProfile Integration Tests")
class UserProfileIntegrationTest {

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
        registry.add("stripe.secret-key", () -> "sk_test_stub");
        registry.add("stripe.webhook-secret", () -> "whsec_stub");
        registry.add("stripe.cdn-base-url", () -> "http://localhost:9000");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private com.tinniestudio.api.modules.auth.service.EmailService emailService;
    @MockBean private CacheService cacheService;
    @MockBean private RateLimiterService rateLimiterService;

    private static final String CONTEXT_PATH = "/api/v1";

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postCtx(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getCtx(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchCtx(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    @Test
    @DisplayName("unauthenticated GET /users/me returns 401")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(getCtx("/users/me"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("authenticated GET /users/me returns 200 with profile fields")
    void authenticated_returnsProfile() throws Exception {
        when(cacheService.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(cacheService.get(anyString())).thenReturn(Optional.empty());
        when(rateLimiterService.checkAndIncrement(anyString(), anyInt(), anyInt())).thenReturn(true);

        String accessToken = registerAndLogin("profile_user@example.com", "Password1!");

        mockMvc.perform(getCtx("/users/me")
                   .header("Authorization", "Bearer " + accessToken))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.email").value("profile_user@example.com"));
    }

    @Test
    @DisplayName("PATCH /users/me updates only provided fields")
    void patchProfile_updatesOnlyProvidedFields() throws Exception {
        when(cacheService.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(cacheService.get(anyString())).thenReturn(Optional.empty());
        when(rateLimiterService.checkAndIncrement(anyString(), anyInt(), anyInt())).thenReturn(true);

        String accessToken = registerAndLogin("patch_user@example.com", "Password1!");

        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setBio("My test bio");
        req.setCountryCode("NG");

        mockMvc.perform(patchCtx("/users/me")
                   .header("Authorization", "Bearer " + accessToken)
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(objectMapper.writeValueAsString(req)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.bio").value("My test bio"))
               .andExpect(jsonPath("$.data.countryCode").value("NG"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String registerAndLogin(String email, String password) throws Exception {
        // Register
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail(email);
        reg.setPassword(password);
        reg.setFirstName("Test");
        reg.setLastName("User");

        mockMvc.perform(postCtx("/auth/register")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(objectMapper.writeValueAsString(reg)))
               .andExpect(status().isCreated());

        // Login
        LoginRequest login = new LoginRequest();
        login.setEmail(email);
        login.setPassword(password);

        MvcResult result = mockMvc.perform(postCtx("/auth/login")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(objectMapper.writeValueAsString(login)))
               .andExpect(status().isOk())
               .andReturn();

        // Extract access_token cookie
        String cookie = result.getResponse().getHeader("Set-Cookie");
        if (cookie != null && cookie.contains("access_token=")) {
            return cookie.split("access_token=")[1].split(";")[0];
        }
        // Fallback: parse from body
        String body = result.getResponse().getContentAsString();
        return objectMapper.readTree(body).path("accessToken").asText("");
    }
}
