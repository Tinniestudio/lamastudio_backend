package com.tinniestudio.api.billing.service;

import com.tinniestudio.api.modules.billing.service.StripeService;
import com.tinniestudio.api.modules.billing.service.StripeServiceImpl;
import com.tinniestudio.api.shared.config.StripeProperties;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StripeService")
class StripeServiceTest {

    @Mock private StripeProperties stripeProperties;

    @InjectMocks private StripeServiceImpl stripeService;

    @Test
    @DisplayName("constructWebhookEvent with invalid signature throws BadRequestException")
    void invalidSignature_throws() {
        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test_secret");

        assertThatThrownBy(() -> stripeService.constructWebhookEvent(
            "{\"type\":\"test\"}",
            "t=invalid,v1=invalidsig"
        )).isInstanceOf(BadRequestException.class)
          .hasMessageContaining("Invalid webhook");
    }

    @Test
    @DisplayName("createCheckoutSession sets a 30-minute session expiry (Stripe's own minimum) and returns it")
    void createCheckoutSession_setsThirtyMinuteExpiry() {
        try (MockedStatic<Session> mockedSession = mockStatic(Session.class)) {
            Session fakeSession = new Session();
            fakeSession.setId("cs_test_123");
            fakeSession.setPaymentIntent("pi_test_123");
            fakeSession.setUrl("https://checkout.stripe.com/pay/cs_test_123");

            ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
            mockedSession.when(() -> Session.create(captor.capture())).thenReturn(fakeSession);

            Instant before = Instant.now();
            StripeService.CreateCheckoutResult result = stripeService.createCheckoutSession(
                1999L, "CAD", "Silver",
                "https://app.example.com/success", "https://app.example.com/cancel",
                Map.of("paymentId", "pid-1"));
            Instant after = Instant.now();

            long minExpected = before.plus(30, ChronoUnit.MINUTES).getEpochSecond();
            long maxExpected = after.plus(30, ChronoUnit.MINUTES).getEpochSecond();

            assertThat(captor.getValue().getExpiresAt()).isBetween(minExpected, maxExpected);
            assertThat(result.expiresAt()).isNotNull();
            assertThat(result.expiresAt().getEpochSecond()).isBetween(minExpected, maxExpected);
        }
    }
}
