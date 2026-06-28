package com.tinniestudio.api.billing.controller;

import com.tinniestudio.api.modules.billing.service.StripeService;
import com.tinniestudio.api.modules.billing.service.SubscriptionService;
import com.tinniestudio.api.shared.cache.CacheService;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@ExtendWith(SpringExtension.class)
@DisplayName("Stripe Webhook Integration Tests")
class StripeWebhookIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("lamastudio_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.data.redis.url", () -> "redis://localhost:6379");
        registry.add("stripe.secret-key", () -> "sk_test_stub");
        registry.add("stripe.webhook-secret", () -> "whsec_stub");
        registry.add("stripe.cdn-base-url", () -> "http://localhost:9000");
    }

    @Autowired private MockMvc mockMvc;

    @MockBean private com.tinniestudio.api.modules.auth.service.EmailService emailService;
    @MockBean private CacheService cacheService;
    @MockBean private StripeService stripeService;
    @MockBean private SubscriptionService subscriptionService;

    private static final String CONTEXT_PATH = "/api/v1";

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postCtx(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    @Test
    @DisplayName("POST /webhooks/stripe with invalid Stripe-Signature returns 400")
    void invalidSignature_returns400() throws Exception {
        when(stripeService.constructWebhookEvent(anyString(), anyString()))
            .thenThrow(new BadRequestException("Invalid webhook signature"));

        mockMvc.perform(postCtx("/webhooks/stripe")
                   .contentType(MediaType.APPLICATION_JSON)
                   .header("Stripe-Signature", "t=invalid,v1=badsig")
                   .content("{\"type\":\"test\"}"))
               .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /webhooks/stripe with payment_intent.succeeded calls activateSubscription and returns 200")
    void paymentSucceeded_activatesSubscription() throws Exception {
        PaymentIntent pi = new PaymentIntent();
        pi.setId("pi_test_123");

        EventDataObjectDeserializer des = mock(EventDataObjectDeserializer.class);
        when(des.getObject()).thenReturn(Optional.of(pi));

        Event event = mock(Event.class);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getId()).thenReturn("evt_test_123");
        when(event.getDataObjectDeserializer()).thenReturn(des);

        when(stripeService.constructWebhookEvent(anyString(), anyString())).thenReturn(event);

        mockMvc.perform(postCtx("/webhooks/stripe")
                   .contentType(MediaType.APPLICATION_JSON)
                   .header("Stripe-Signature", "t=valid,v1=validsig")
                   .content("{\"type\":\"payment_intent.succeeded\"}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.received").value(true));

        verify(subscriptionService).activateSubscription(isNull(), eq("pi_test_123"));
    }

    @Test
    @DisplayName("POST /webhooks/stripe is a public endpoint (no auth token required)")
    void webhookEndpoint_isPublic() throws Exception {
        EventDataObjectDeserializer des = mock(EventDataObjectDeserializer.class);
        when(des.getObject()).thenReturn(Optional.empty());

        Event event = mock(Event.class);
        when(event.getType()).thenReturn("unknown.event");
        when(event.getId()).thenReturn("evt_unknown");
        when(event.getDataObjectDeserializer()).thenReturn(des);

        when(stripeService.constructWebhookEvent(anyString(), anyString())).thenReturn(event);

        mockMvc.perform(postCtx("/webhooks/stripe")
                   .contentType(MediaType.APPLICATION_JSON)
                   .header("Stripe-Signature", "t=valid,v1=validsig")
                   .content("{\"type\":\"unknown.event\"}"))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /webhooks/stripe with duplicate payment_intent.succeeded is idempotent")
    void duplicateWebhook_isIdempotent() throws Exception {
        PaymentIntent pi = new PaymentIntent();
        pi.setId("pi_dup_123");

        EventDataObjectDeserializer des = mock(EventDataObjectDeserializer.class);
        when(des.getObject()).thenReturn(Optional.of(pi));

        Event event = mock(Event.class);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getId()).thenReturn("evt_dup");
        when(event.getDataObjectDeserializer()).thenReturn(des);
        when(stripeService.constructWebhookEvent(anyString(), anyString())).thenReturn(event);

        // First call
        mockMvc.perform(postCtx("/webhooks/stripe")
                   .contentType(MediaType.APPLICATION_JSON)
                   .header("Stripe-Signature", "t=valid,v1=validsig")
                   .content("{\"type\":\"payment_intent.succeeded\"}"))
               .andExpect(status().isOk());

        // Second call — same event
        mockMvc.perform(postCtx("/webhooks/stripe")
                   .contentType(MediaType.APPLICATION_JSON)
                   .header("Stripe-Signature", "t=valid,v1=validsig")
                   .content("{\"type\":\"payment_intent.succeeded\"}"))
               .andExpect(status().isOk());

        // SubscriptionService handles idempotency internally; controller returns 200 both times
        verify(subscriptionService, times(2)).activateSubscription(isNull(), eq("pi_dup_123"));
    }
}
