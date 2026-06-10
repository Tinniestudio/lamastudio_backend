package com.lamastudio.backend.billing.service;

import com.lamastudio.backend.modules.billing.service.StripeService;
import com.lamastudio.backend.modules.billing.service.StripeServiceImpl;
import com.lamastudio.backend.shared.config.StripeProperties;
import com.lamastudio.backend.shared.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
}
