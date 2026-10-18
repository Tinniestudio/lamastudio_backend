package com.tinniestudio.api.billing.service;

import com.tinniestudio.api.modules.billing.service.StripeService;
import com.tinniestudio.api.modules.billing.service.StripeServiceImpl;
import com.tinniestudio.api.shared.config.StripeProperties;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.SetupIntent;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @DisplayName("createCustomer creates a Stripe Customer and returns its id")
    void createCustomer_returnsCustomerId() {
        try (MockedStatic<Customer> mockedCustomer = mockStatic(Customer.class)) {
            Customer fake = new Customer();
            fake.setId("cus_test_123");
            ArgumentCaptor<CustomerCreateParams> captor = ArgumentCaptor.forClass(CustomerCreateParams.class);
            mockedCustomer.when(() -> Customer.create(captor.capture())).thenReturn(fake);

            String customerId = stripeService.createCustomer("user@example.com", Map.of("userId", "u-1"));

            assertThat(customerId).isEqualTo("cus_test_123");
            assertThat(captor.getValue().getEmail()).isEqualTo("user@example.com");
        }
    }

    @Test
    @DisplayName("createSetupIntent returns the client secret and setup intent id")
    void createSetupIntent_returnsClientSecret() {
        try (MockedStatic<SetupIntent> mocked = mockStatic(SetupIntent.class)) {
            SetupIntent fake = new SetupIntent();
            fake.setId("seti_test_123");
            fake.setClientSecret("seti_test_123_secret_abc");
            mocked.when(() -> SetupIntent.create(org.mockito.ArgumentMatchers.any(SetupIntentCreateParams.class)))
                .thenReturn(fake);

            StripeService.CreateSetupIntentResult result = stripeService.createSetupIntent("cus_test_123");

            assertThat(result.setupIntentId()).isEqualTo("seti_test_123");
            assertThat(result.clientSecret()).isEqualTo("seti_test_123_secret_abc");
        }
    }

    @Test
    @DisplayName("retrieveSetupIntent returns the resolved customer and payment method ids")
    void retrieveSetupIntent_returnsCustomerAndPaymentMethod() {
        try (MockedStatic<SetupIntent> mocked = mockStatic(SetupIntent.class)) {
            SetupIntent fake = new SetupIntent();
            fake.setId("seti_test_123");
            fake.setCustomer("cus_test_123");
            fake.setPaymentMethod("pm_test_123");
            fake.setStatus("succeeded");
            mocked.when(() -> SetupIntent.retrieve("seti_test_123")).thenReturn(fake);

            StripeService.VerifySetupIntentResult result = stripeService.retrieveSetupIntent("seti_test_123");

            assertThat(result.customerId()).isEqualTo("cus_test_123");
            assertThat(result.paymentMethodId()).isEqualTo("pm_test_123");
            assertThat(result.succeeded()).isTrue();
        }
    }

    @Test
    @DisplayName("chargeOffSession returns a successful result with the payment intent id")
    void chargeOffSession_success() {
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent fake = new PaymentIntent();
            fake.setId("pi_test_123");
            fake.setStatus("succeeded");
            mocked.when(() -> PaymentIntent.create(
                    org.mockito.ArgumentMatchers.any(PaymentIntentCreateParams.class),
                    org.mockito.ArgumentMatchers.any(com.stripe.net.RequestOptions.class)))
                .thenReturn(fake);

            StripeService.ChargeResult result = stripeService.chargeOffSession(
                "cus_test_123", "pm_test_123", 1999L, "CAD", "idem-key-1");

            assertThat(result.succeeded()).isTrue();
            assertThat(result.paymentIntentId()).isEqualTo("pi_test_123");
            assertThat(result.failureReason()).isNull();
        }
    }

    @Test
    @DisplayName("chargeOffSession returns a failed result (not an exception) when Stripe declines the card")
    void chargeOffSession_declined_returnsFailureResult() {
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.create(
                    org.mockito.ArgumentMatchers.any(PaymentIntentCreateParams.class),
                    org.mockito.ArgumentMatchers.any(com.stripe.net.RequestOptions.class)))
                .thenThrow(new com.stripe.exception.CardException(
                    "Your card was declined.", "req_123", "card_declined", "number",
                    "generic_decline", null, 402, null));

            StripeService.ChargeResult result = stripeService.chargeOffSession(
                "cus_test_123", "pm_test_123", 1999L, "CAD", "idem-key-2");

            assertThat(result.succeeded()).isFalse();
            assertThat(result.failureReason()).contains("declined");
        }
    }
}
