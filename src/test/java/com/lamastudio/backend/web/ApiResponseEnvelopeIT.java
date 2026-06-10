package com.lamastudio.backend.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lamastudio.backend.modules.auth.controller.AuthController;
import com.lamastudio.backend.modules.auth.dto.LoginRequest;
import com.lamastudio.backend.modules.auth.dto.RegisterRequest;
import com.lamastudio.backend.modules.auth.exception.BadCredentialsException;
import com.lamastudio.backend.modules.auth.service.AuthService;
import com.lamastudio.backend.modules.auth.user.dto.AuthProfileResponse;
import com.lamastudio.backend.modules.auth.user.service.AuthProfileService;
import com.lamastudio.backend.modules.billing.controller.StripeWebhookController;
import com.lamastudio.backend.modules.billing.dto.PlanResponse;
import com.lamastudio.backend.modules.billing.service.StripeService;
import com.lamastudio.backend.modules.billing.service.SubscriptionService;
import com.lamastudio.backend.modules.user.repository.UserRepository;
import com.lamastudio.backend.modules.user.service.UserDetailsServiceImpl;
import com.lamastudio.backend.shared.cache.CacheService;
import com.lamastudio.backend.shared.exception.BadRequestException;
import com.lamastudio.backend.shared.security.jwt.JwtAuthenticationFilter;
import com.lamastudio.backend.shared.security.jwt.JwtTokenProvider;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying the SuccessResponseWrapper envelope is applied
 * (or correctly bypassed) for key endpoints.
 */
@WebMvcTest(controllers = {AuthController.class, StripeWebhookController.class})
@AutoConfigureMockMvc(addFilters = false)
class ApiResponseEnvelopeIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // Auth controller dependencies
    @MockBean private AuthService authService;
    @MockBean private AuthProfileService authProfileService;
    @MockBean private UserRepository userRepository;
    @MockBean private UserDetailsServiceImpl userDetailsService;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;

    // Stripe webhook dependencies
    @MockBean private StripeService stripeService;
    @MockBean private SubscriptionService subscriptionService;
    @MockBean private CacheService cacheService;

    private static final String CTX = "/api/v1";

    // ── US1: Success envelope applied ────────────────────────────────────────

    @Test
    @DisplayName("US1: POST /auth/register returns envelope with success=true and data containing profile")
    void register_returnsEnvelope() throws Exception {
        AuthProfileResponse profile = buildProfile();
        when(authService.register(any(), any())).thenReturn(profile);

        RegisterRequest req = buildRegisterRequest("env+register@example.com");

        mockMvc.perform(post(CTX + "/auth/register").contextPath(CTX)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Created successfully"))
                .andExpect(jsonPath("$.data.email").value(profile.getEmail()))
                .andExpect(jsonPath("$.data.userId").exists());
    }

    @Test
    @DisplayName("US1: POST /auth/logout returns envelope with extracted message and data=null")
    void logout_returnsEnvelopeWithExtractedMessage() throws Exception {
        doNothing().when(authService).logout(any(), any());

        mockMvc.perform(post(CTX + "/auth/logout").contextPath(CTX))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Logged out successfully"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ── US3: Stripe webhook excluded (no envelope) ───────────────────────────

    @Test
    @DisplayName("US3: POST /webhooks/stripe returns raw {received:true} without envelope fields")
    void stripeWebhook_notWrappedInEnvelope() throws Exception {
        EventDataObjectDeserializer des = mock(EventDataObjectDeserializer.class);
        when(des.getObject()).thenReturn(Optional.empty());

        Event event = mock(Event.class);
        when(event.getType()).thenReturn("unknown.event");
        when(event.getId()).thenReturn("evt_test");
        when(event.getDataObjectDeserializer()).thenReturn(des);
        when(stripeService.constructWebhookEvent(anyString(), anyString())).thenReturn(event);

        mockMvc.perform(post(CTX + "/webhooks/stripe").contextPath(CTX)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=valid,v1=sig")
                        .content("{\"type\":\"unknown.event\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.success").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ── US3: Error responses bypass envelope ─────────────────────────────────

    @Test
    @DisplayName("US3: POST /auth/login with bad credentials returns error format — no 'data' key")
    void login_badCredentials_errorNotWrapped() throws Exception {
        when(authService.login(any(), any(), any()))
                .thenThrow(new BadCredentialsException("Invalid email or password"));

        LoginRequest req = new LoginRequest();
        req.setEmail("bad@example.com");
        req.setPassword("WrongPass1!");

        mockMvc.perform(post(CTX + "/auth/login").contextPath(CTX)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AuthProfileResponse buildProfile() {
        return AuthProfileResponse.builder()
                .userId(UUID.randomUUID())
                .email("env+register@example.com")
                .firstName("Jane")
                .lastName("Doe")
                .build();
    }

    private RegisterRequest buildRegisterRequest(String email) {
        RegisterRequest r = new RegisterRequest();
        r.setEmail(email);
        r.setPassword("Password1!");
        r.setFirstName("Jane");
        r.setLastName("Doe");
        return r;
    }
}
