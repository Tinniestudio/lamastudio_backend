# Payment Session Expiry (Server) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every Stripe Checkout Session a real, short expiry instead of Stripe's default 24-hour window, record that deadline on `Payment`, and flip a `PENDING` payment to `FAILED` once it's genuinely dead — via a fast webhook path (`checkout.session.expired`) and a fallback sweep job for anything the webhook missed.

**Architecture:** One new nullable `expires_at` column on `payments`, computed once in `StripeServiceImpl.createCheckoutSession()` and threaded back to `SubscriptionServiceImpl.initiateCheckout()` through an extended `StripeService.CreateCheckoutResult` record (avoids recomputing `Instant.now()` twice for what must be "the same value"). `StripeWebhookController` gains a `checkout.session.expired` case that reuses the existing `SubscriptionService.failPayment(reference, reason)` — no new fail-a-payment logic. A new `PendingPaymentExpiryJob`, following `ExpiredUploadSessionCleanupJob`'s exact `@Scheduled` + `@SchedulerLock` + `JobLogger` template, sweeps every 5 minutes for anything the webhook missed, reusing the same `failPayment`.

**Tech Stack:** Spring Boot 3.3.5, JUnit 5 + Mockito 5.11 (inline mock maker, `mockStatic` works with no extra dependency) + AssertJ, Testcontainers (Postgres), Flyway, stripe-java 26.3.0.

**Covers spec:** `docs/superpowers/specs/2026-09-21-payment-session-expiry-design.md`

**Depends on:** none — entirely self-contained, server-only.

---

## Two findings from reading the real code that change the plan from the spec's literal text

The spec was written at a design level and said to verify everything against actual source before planning. Two things the actual code revealed contradict the spec's literal snippets — both are incorporated into the tasks below, not left for the implementer to discover mid-task.

**1. Stripe rejects a 15-minute expiry — 30 minutes is the real minimum.**
`SessionCreateParams.expiresAt`'s own javadoc (bundled in `stripe-java-26.3.0-sources.jar`, `com/stripe/param/checkout/SessionCreateParams.java` line ~152) reads:

> "The Epoch time in seconds at which the Checkout Session will expire. **It can be anywhere from 30 minutes to 24 hours after Checkout Session creation.** By default, this value is 24 hours from creation."

Stripe's API validates this server-side — `Session.create()` with an `expires_at` less than 30 minutes out throws `InvalidRequestException`, which would break checkout entirely in production. This plan uses **30 minutes**, the closest value to the spec's intent that Stripe's API actually accepts. This is a hard external constraint, not a judgment call — the spec's "15 minutes" is infeasible as written.

**2. `Payment.providerReference` at checkout creation is usually the PaymentIntent ID, not the Checkout Session ID — the webhook case must look up the same way.**

The spec's design doc says: *"`session.getId()` matches `Payment.providerReference` as stored at checkout-creation time... confirmed against `SubscriptionServiceImpl`'s checkout-creation code."* Reading that code (`SubscriptionServiceImpl.initiateCheckout()`, lines 133-135) shows the opposite:

```java
payment.setProviderReference(checkout.paymentIntentId() != null
        ? checkout.paymentIntentId()
        : checkout.checkoutSessionId());
```

Since `StripeServiceImpl.createCheckoutSession()` uses `Mode.PAYMENT`, Stripe creates the underlying `PaymentIntent` synchronously at Session-creation time — `session.getPaymentIntent()` (aliased as `checkout.paymentIntentId()`) is populated immediately, essentially always. So `providerReference` is stored as the **PaymentIntent ID** (`pi_...`), not the Checkout Session ID (`cs_...`), in the normal case. If the webhook case called `failPayment(session.getId(), ...)` literally as the spec's snippet shows, the lookup would find nothing on virtually every real expiry — the fast path would silently no-op every time (the fallback sweep would still eventually catch it via `providerReference`, since that job looks up by the already-correct stored value, but that defeats the entire point of having a fast path).

Task 3 below uses the same fallback Stripe itself gives on the `Session` object at read time: `session.getPaymentIntent() != null ? session.getPaymentIntent() : session.getId()` — mirroring the exact logic `initiateCheckout()` used to decide what to store in the first place.

---

## File Structure

**Modify:**
- `api-service/src/main/java/com/tinniestudio/api/shared/entity/Payment.java` — add `expiresAt` field
- `api-service/src/main/java/com/tinniestudio/api/modules/billing/service/StripeService.java` — add `expiresAt` to `CreateCheckoutResult`
- `api-service/src/main/java/com/tinniestudio/api/modules/billing/service/StripeServiceImpl.java` — `.setExpiresAt(...)` + populate the new record field
- `api-service/src/main/java/com/tinniestudio/api/modules/billing/service/SubscriptionServiceImpl.java` — persist `Payment.expiresAt` from the checkout result
- `api-service/src/main/java/com/tinniestudio/api/modules/billing/controller/StripeWebhookController.java` — new `checkout.session.expired` case
- `api-service/src/main/java/com/tinniestudio/api/modules/billing/repository/PaymentRepository.java` — new `findExpiredPending` query
- `api-service/src/test/java/com/tinniestudio/api/billing/service/StripeServiceTest.java` — new expiry test
- `api-service/src/test/java/com/tinniestudio/api/billing/service/SubscriptionServiceTest.java` — updated `CreateCheckoutResult` call site + new test
- `api-service/src/test/java/com/tinniestudio/api/billing/controller/StripeWebhookIntegrationTest.java` — new `checkout.session.expired` tests
- `api-service/src/test/java/com/tinniestudio/api/modules/jobs/BackgroundJobsTest.java` — new `PendingPaymentExpiryJob` tests

**Create:**
- `api-service/src/main/resources/db/migration/V56__add_payment_expires_at_column.sql`
- `api-service/src/main/java/com/tinniestudio/api/modules/jobs/PendingPaymentExpiryJob.java`

---

### Task 1: `Payment.expiresAt` column + migration

**Files:** `Payment.java`, `V56__add_payment_expires_at_column.sql`

- [ ] **Step 1: Add the field to `Payment.java`**

Find:
```java
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;
```

Replace with:
```java
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /**
     * Stripe Checkout Session deadline, mirrored from the same value sent to Stripe at checkout
     * creation (see StripeServiceImpl.createCheckoutSession). Nullable: pre-existing PENDING rows
     * created before this column existed have no known real expiry and are intentionally never
     * matched by PendingPaymentExpiryJob's sweep — no retroactive backfill (Payment Session Expiry
     * design, 2026-09-21).
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;
```

- [ ] **Step 2: Create the migration**

Create `api-service/src/main/resources/db/migration/V56__add_payment_expires_at_column.sql`:

```sql
-- App's own record of the Stripe Checkout Session deadline (Payment Session Expiry design,
-- 2026-09-21). Nullable, no backfill: pre-existing PENDING rows have no known real expiry and
-- must simply never match the new fallback-sweep job's query.
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

-- PendingPaymentExpiryJob (Task 4) queries exactly
-- WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at < now() every 5 minutes.
-- Scoping the index to PENDING keeps it small since most payments settle out of PENDING quickly.
CREATE INDEX IF NOT EXISTS idx_payments_pending_expires_at
    ON payments (expires_at)
    WHERE status = 'PENDING';
```

- [ ] **Step 3: Compile and run the full suite to confirm the migration applies cleanly**

Run: `./gradlew :api-service:compileJava`
Expected: `BUILD SUCCESSFUL` — a new nullable column with a plain Lombok setter doesn't break any existing caller.

Run: `./gradlew :api-service:test --tests "*Payment*" --tests StripeWebhookIntegrationTest`
Expected: `BUILD SUCCESSFUL` — this exercises Flyway against a real Testcontainers Postgres instance (via `StripeWebhookIntegrationTest`), confirming `V56` applies without error alongside the existing schema.

- [ ] **Step 4: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/Payment.java api-service/src/main/resources/db/migration/V56__add_payment_expires_at_column.sql
git commit -m "feat: add nullable Payment.expiresAt column for checkout session deadline"
```

---

### Task 2: 30-minute expiry on session creation, threaded through to `Payment`

**Files:** `StripeService.java`, `StripeServiceImpl.java`, `SubscriptionServiceImpl.java`, `StripeServiceTest.java`, `SubscriptionServiceTest.java`

- [ ] **Step 1: Write the failing tests**

In `StripeServiceTest.java`, find the full file content:
```java
package com.tinniestudio.api.billing.service;

import com.tinniestudio.api.modules.billing.service.StripeService;
import com.tinniestudio.api.modules.billing.service.StripeServiceImpl;
import com.tinniestudio.api.shared.config.StripeProperties;
import com.tinniestudio.api.shared.exception.BadRequestException;
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
```

Replace with:
```java
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
```

In `SubscriptionServiceTest.java`, find:
```java
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
```

Replace with:
```java
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
```

Find:
```java
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
```

Replace with:
```java
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
```

Find the existing call site that breaks once `CreateCheckoutResult` gains a 4th field:
```java
            when(stripeService.createCheckoutSession(anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(new StripeService.CreateCheckoutResult("cs_123", "pi_123", "https://checkout.stripe.com/pay/cs_123"));
```

Replace with:
```java
            when(stripeService.createCheckoutSession(anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(new StripeService.CreateCheckoutResult("cs_123", "pi_123",
                    "https://checkout.stripe.com/pay/cs_123", Instant.now().plus(30, ChronoUnit.MINUTES)));
```

Add a new test to the `InitiateCheckout` nested class — find:
```java
        @Test
        @DisplayName("user with existing active paid subscription throws BadRequestException")
        void activeSubscriptionExists_throws() {
```

Replace with (inserting the new test immediately before it):
```java
        @Test
        @DisplayName("sets Payment.expiresAt from the Stripe checkout result")
        void setsPaymentExpiresAtFromCheckoutResult() {
            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());
            when(planRepository.findById(silverPlan.getId())).thenReturn(Optional.of(silverPlan));
            when(appProperties.getFrontendUrl()).thenReturn("https://frontend.example.com");
            Instant expiry = Instant.now().plus(30, ChronoUnit.MINUTES);
            when(stripeService.createCheckoutSession(anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(new StripeService.CreateCheckoutResult("cs_123", "pi_123",
                    "https://checkout.stripe.com/pay/cs_123", expiry));

            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            when(paymentRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            CheckoutRequest request = new CheckoutRequest();
            request.setPlanId(silverPlan.getId());
            request.setAutoRenew(true);

            service.initiateCheckout(userId, request);

            assertThat(captor.getValue().getExpiresAt()).isEqualTo(expiry);
        }

        @Test
        @DisplayName("user with existing active paid subscription throws BadRequestException")
        void activeSubscriptionExists_throws() {
```

- [ ] **Step 2: Run to verify the new/changed tests fail**

Run: `./gradlew :api-service:test --tests StripeServiceTest --tests SubscriptionServiceTest`
Expected: FAIL to compile — `CreateCheckoutResult` doesn't take a 4th arg yet, `Session.getExpiresAt()`/`.setExpiresAt(...)` aren't wired, `Payment.getExpiresAt()` isn't set anywhere yet.

- [ ] **Step 3: Extend `CreateCheckoutResult` in `StripeService.java`**

Find:
```java
    record CreateCheckoutResult(String checkoutSessionId, String paymentIntentId, String checkoutUrl) {}
```

Replace with:
```java
    record CreateCheckoutResult(String checkoutSessionId, String paymentIntentId, String checkoutUrl, java.time.Instant expiresAt) {}
```

- [ ] **Step 4: Set the expiry in `StripeServiceImpl.java`**

Find:
```java
import com.tinniestudio.api.shared.config.StripeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionRetrieveParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeServiceImpl implements StripeService {

    private final StripeProperties stripeProperties;

    @Override
    public CreateCheckoutResult createCheckoutSession(long amountCents, String currency, String planName,
                                                      String successUrl, String cancelUrl,
                                                      Map<String, String> metadata) {
        try {
            SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .addLineItem(SessionCreateParams.LineItem.builder()
                    .setQuantity(1L)
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(currency.toLowerCase())
                        .setUnitAmount(amountCents)
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                            .setName(planName + " Subscription")
                            .build())
                        .build())
                    .build())
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl);

            metadata.forEach(paramsBuilder::putMetadata);

            Session session = Session.create(paramsBuilder.build());
            log.info("Stripe Checkout Session created: id={}", session.getId());
            return new CreateCheckoutResult(session.getId(), session.getPaymentIntent(), session.getUrl());

        } catch (StripeException ex) {
```

Replace with:
```java
import com.tinniestudio.api.shared.config.StripeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionRetrieveParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StripeServiceImpl implements StripeService {

    /**
     * 30 minutes is Stripe's own hard minimum for Checkout Session expires_at (validated
     * server-side by Stripe's API — a shorter value throws InvalidRequestException). This is the
     * shortest deadline Stripe allows, matching the Payment Session Expiry design's intent of a
     * short-lived session even though its literal "15 minutes" isn't achievable.
     */
    private static final long SESSION_EXPIRY_MINUTES = 30;

    private final StripeProperties stripeProperties;

    @Override
    public CreateCheckoutResult createCheckoutSession(long amountCents, String currency, String planName,
                                                      String successUrl, String cancelUrl,
                                                      Map<String, String> metadata) {
        try {
            Instant expiresAt = Instant.now().plus(SESSION_EXPIRY_MINUTES, ChronoUnit.MINUTES);

            SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .addLineItem(SessionCreateParams.LineItem.builder()
                    .setQuantity(1L)
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(currency.toLowerCase())
                        .setUnitAmount(amountCents)
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                            .setName(planName + " Subscription")
                            .build())
                        .build())
                    .build())
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .setExpiresAt(expiresAt.getEpochSecond());

            metadata.forEach(paramsBuilder::putMetadata);

            Session session = Session.create(paramsBuilder.build());
            log.info("Stripe Checkout Session created: id={}, expiresAt={}", session.getId(), expiresAt);
            return new CreateCheckoutResult(session.getId(), session.getPaymentIntent(), session.getUrl(), expiresAt);

        } catch (StripeException ex) {
```

- [ ] **Step 5: Persist it on `Payment` in `SubscriptionServiceImpl.java`**

Find:
```java
        payment.setProviderReference(checkout.paymentIntentId() != null
                ? checkout.paymentIntentId()
                : checkout.checkoutSessionId());
        paymentRepository.save(payment);
```

Replace with:
```java
        payment.setProviderReference(checkout.paymentIntentId() != null
                ? checkout.paymentIntentId()
                : checkout.checkoutSessionId());
        payment.setExpiresAt(checkout.expiresAt());
        paymentRepository.save(payment);
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :api-service:test --tests StripeServiceTest --tests SubscriptionServiceTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/billing/service/StripeService.java api-service/src/main/java/com/tinniestudio/api/modules/billing/service/StripeServiceImpl.java api-service/src/main/java/com/tinniestudio/api/modules/billing/service/SubscriptionServiceImpl.java api-service/src/test/java/com/tinniestudio/api/billing/service/StripeServiceTest.java api-service/src/test/java/com/tinniestudio/api/billing/service/SubscriptionServiceTest.java
git commit -m "feat: set 30-minute Stripe checkout session expiry and persist it on Payment"
```

---

### Task 3: Webhook fast path — `checkout.session.expired`

**Files:** `StripeWebhookController.java`, `StripeWebhookIntegrationTest.java`

- [ ] **Step 1: Write the failing tests**

In `StripeWebhookIntegrationTest.java`, find:
```java
import com.tinniestudio.api.modules.billing.service.StripeService;
import com.tinniestudio.api.modules.billing.service.SubscriptionService;
import com.tinniestudio.api.shared.cache.CacheService;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
```

Replace with:
```java
import com.tinniestudio.api.modules.billing.service.StripeService;
import com.tinniestudio.api.modules.billing.service.SubscriptionService;
import com.tinniestudio.api.shared.cache.CacheService;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
```

Find:
```java
    @Test
    @DisplayName("POST /webhooks/stripe is a public endpoint (no auth token required)")
```

Replace with (inserting the two new tests immediately before it):
```java
    @Test
    @DisplayName("POST /webhooks/stripe with checkout.session.expired fails the payment by its payment intent id")
    void checkoutSessionExpired_failsPaymentByPaymentIntentId() throws Exception {
        Session session = new Session();
        session.setId("cs_expired_123");
        session.setPaymentIntent("pi_expired_123");

        EventDataObjectDeserializer des = mock(EventDataObjectDeserializer.class);
        when(des.getObject()).thenReturn(Optional.of(session));

        Event event = mock(Event.class);
        when(event.getType()).thenReturn("checkout.session.expired");
        when(event.getId()).thenReturn("evt_expired_123");
        when(event.getDataObjectDeserializer()).thenReturn(des);

        when(stripeService.constructWebhookEvent(anyString(), anyString())).thenReturn(event);

        mockMvc.perform(postCtx("/webhooks/stripe")
                   .contentType(MediaType.APPLICATION_JSON)
                   .header("Stripe-Signature", "t=valid,v1=validsig")
                   .content("{\"type\":\"checkout.session.expired\"}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.received").value(true));

        verify(subscriptionService).failPayment(eq("pi_expired_123"), eq("Checkout session expired"));
    }

    @Test
    @DisplayName("POST /webhooks/stripe with checkout.session.expired falls back to the session id when no payment intent exists")
    void checkoutSessionExpired_fallsBackToSessionIdWhenNoPaymentIntent() throws Exception {
        Session session = new Session();
        session.setId("cs_expired_456");
        // No payment intent set on this session — the handler must fall back to the session id,
        // matching the exact fallback SubscriptionServiceImpl.initiateCheckout() used when it
        // originally decided what to store as Payment.providerReference.

        EventDataObjectDeserializer des = mock(EventDataObjectDeserializer.class);
        when(des.getObject()).thenReturn(Optional.of(session));

        Event event = mock(Event.class);
        when(event.getType()).thenReturn("checkout.session.expired");
        when(event.getId()).thenReturn("evt_expired_456");
        when(event.getDataObjectDeserializer()).thenReturn(des);

        when(stripeService.constructWebhookEvent(anyString(), anyString())).thenReturn(event);

        mockMvc.perform(postCtx("/webhooks/stripe")
                   .contentType(MediaType.APPLICATION_JSON)
                   .header("Stripe-Signature", "t=valid,v1=validsig")
                   .content("{\"type\":\"checkout.session.expired\"}"))
               .andExpect(status().isOk());

        verify(subscriptionService).failPayment(eq("cs_expired_456"), eq("Checkout session expired"));
    }

    @Test
    @DisplayName("POST /webhooks/stripe is a public endpoint (no auth token required)")
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./gradlew :api-service:test --tests StripeWebhookIntegrationTest`
Expected: FAIL — `checkout.session.expired` currently hits the `default -> log.debug(...)` branch, so `subscriptionService.failPayment(...)` is never called; both new `verify(...)` calls fail.

- [ ] **Step 3: Add the case to `StripeWebhookController.java`**

Find:
```java
            case "payment_intent.payment_failed" -> {
                Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
                if (obj.isPresent() && obj.get() instanceof PaymentIntent pi) {
                    String reason = pi.getLastPaymentError() != null
                        ? pi.getLastPaymentError().getMessage() : "Payment declined";
                    subscriptionService.failPayment(pi.getId(), reason);
                }
            }
            default -> log.debug("Unhandled Stripe event type: {}", event.getType());
```

Replace with:
```java
            case "payment_intent.payment_failed" -> {
                Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
                if (obj.isPresent() && obj.get() instanceof PaymentIntent pi) {
                    String reason = pi.getLastPaymentError() != null
                        ? pi.getLastPaymentError().getMessage() : "Payment declined";
                    subscriptionService.failPayment(pi.getId(), reason);
                }
            }
            case "checkout.session.expired" -> {
                Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
                if (obj.isPresent() && obj.get() instanceof Session session) {
                    // Mirrors the exact fallback SubscriptionServiceImpl.initiateCheckout() used to
                    // decide Payment.providerReference at checkout-creation time: the PaymentIntent id
                    // when one exists (the normal case — Mode.PAYMENT creates it synchronously), else
                    // the Checkout Session id.
                    String reference = session.getPaymentIntent() != null
                        ? session.getPaymentIntent()
                        : session.getId();
                    subscriptionService.failPayment(reference, "Checkout session expired");
                }
            }
            default -> log.debug("Unhandled Stripe event type: {}", event.getType());
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :api-service:test --tests StripeWebhookIntegrationTest`
Expected: PASS — all tests in this class, including the 2 new ones.

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/billing/controller/StripeWebhookController.java api-service/src/test/java/com/tinniestudio/api/billing/controller/StripeWebhookIntegrationTest.java
git commit -m "feat: handle checkout.session.expired webhook to fail stale pending payments"
```

---

### Task 4: `PendingPaymentExpiryJob` fallback sweep

**Files:** `PaymentRepository.java`, `PendingPaymentExpiryJob.java` (new), `BackgroundJobsTest.java`

- [ ] **Step 1: Write the failing tests**

In `BackgroundJobsTest.java`, find:
```java
import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import com.tinniestudio.api.modules.notification.repository.NotificationRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.UploadStatus;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.storage.StorageService;
```

Replace with:
```java
import com.tinniestudio.api.modules.billing.repository.PaymentRepository;
import com.tinniestudio.api.modules.billing.service.SubscriptionService;
import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import com.tinniestudio.api.modules.notification.repository.NotificationRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.PaymentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.UploadStatus;
import com.tinniestudio.api.shared.entity.Payment;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.storage.StorageService;
```

Find:
```java
    @Mock UploadSessionRepository uploadSessionRepo;
    @Mock VideoAssetRepository videoAssetRepo;
    @Mock StorageService storageService;
    @Mock NotificationRepository notificationRepo;
    @Mock UserSessionRepository userSessionRepo;
    @Mock JobLogger jobLogger;

    @InjectMocks ExpiredUploadSessionCleanupJob expiredUploadJob;
    @InjectMocks StaleVideoAssetJob staleVideoJob;
    @InjectMocks FailedVideoAssetCleanupJob failedVideoJob;
    @InjectMocks NotificationCleanupJob notificationCleanupJob;
    @InjectMocks ExpiredSessionCleanupJob expiredSessionJob;
```

Replace with:
```java
    @Mock UploadSessionRepository uploadSessionRepo;
    @Mock VideoAssetRepository videoAssetRepo;
    @Mock StorageService storageService;
    @Mock NotificationRepository notificationRepo;
    @Mock UserSessionRepository userSessionRepo;
    @Mock PaymentRepository paymentRepository;
    @Mock SubscriptionService subscriptionService;
    @Mock JobLogger jobLogger;

    @InjectMocks ExpiredUploadSessionCleanupJob expiredUploadJob;
    @InjectMocks StaleVideoAssetJob staleVideoJob;
    @InjectMocks FailedVideoAssetCleanupJob failedVideoJob;
    @InjectMocks NotificationCleanupJob notificationCleanupJob;
    @InjectMocks ExpiredSessionCleanupJob expiredSessionJob;
    @InjectMocks PendingPaymentExpiryJob pendingPaymentExpiryJob;
```

Find (the end of the class, the last test and closing brace):
```java
    @Test
    @DisplayName("expired session cleanup job records failure when repo throws")
    void expiredSessionCleanupJob_repoThrows_logsFailure() {
        when(jobLogger.start(any())).thenReturn(fakeLog("ExpiredSessionCleanupJob"));
        when(userSessionRepo.deleteExpiredSessions(any())).thenThrow(new RuntimeException("lock fail"));

        expiredSessionJob.run();

        verify(jobLogger).failure(any(), eq("lock fail"));
    }
}
```

Replace with:
```java
    @Test
    @DisplayName("expired session cleanup job records failure when repo throws")
    void expiredSessionCleanupJob_repoThrows_logsFailure() {
        when(jobLogger.start(any())).thenReturn(fakeLog("ExpiredSessionCleanupJob"));
        when(userSessionRepo.deleteExpiredSessions(any())).thenThrow(new RuntimeException("lock fail"));

        expiredSessionJob.run();

        verify(jobLogger).failure(any(), eq("lock fail"));
    }

    // ─── PendingPaymentExpiryJob ──────────────────────────────────────────────

    @Test
    @DisplayName("pending payment expiry job fails each expired PENDING payment by its stored providerReference and logs the count")
    void pendingPaymentExpiryJob_failsExpiredPaymentsAndLogs() {
        Payment payment1 = new Payment();
        payment1.setProviderReference("pi_expired_1");
        Payment payment2 = new Payment();
        payment2.setProviderReference("cs_expired_2");

        when(jobLogger.start(any())).thenReturn(fakeLog("PendingPaymentExpiryJob"));
        when(paymentRepository.findExpiredPending(eq(PaymentStatus.PENDING), any()))
                .thenReturn(List.of(payment1, payment2));

        pendingPaymentExpiryJob.run();

        verify(subscriptionService).failPayment(eq("pi_expired_1"), eq("Checkout session expired (fallback sweep)"));
        verify(subscriptionService).failPayment(eq("cs_expired_2"), eq("Checkout session expired (fallback sweep)"));
        verify(jobLogger).success(any(), eq(2));
    }

    @Test
    @DisplayName("pending payment expiry job is a no-op when nothing has expired — the normal case, since the webhook already handled it")
    void pendingPaymentExpiryJob_nothingExpired_logsZero() {
        when(jobLogger.start(any())).thenReturn(fakeLog("PendingPaymentExpiryJob"));
        when(paymentRepository.findExpiredPending(eq(PaymentStatus.PENDING), any()))
                .thenReturn(List.of());

        pendingPaymentExpiryJob.run();

        verifyNoInteractions(subscriptionService);
        verify(jobLogger).success(any(), eq(0));
    }

    @Test
    @DisplayName("pending payment expiry job records failure when repo throws")
    void pendingPaymentExpiryJob_repoThrows_logsFailure() {
        when(jobLogger.start(any())).thenReturn(fakeLog("PendingPaymentExpiryJob"));
        when(paymentRepository.findExpiredPending(eq(PaymentStatus.PENDING), any()))
                .thenThrow(new RuntimeException("DB error"));

        pendingPaymentExpiryJob.run();

        verify(jobLogger).failure(any(), eq("DB error"));
        verify(jobLogger, never()).success(any(), anyInt());
    }
}
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./gradlew :api-service:test --tests BackgroundJobsTest`
Expected: FAIL to compile — `PaymentRepository.findExpiredPending(...)` doesn't exist yet and `PendingPaymentExpiryJob` doesn't exist yet.

- [ ] **Step 3: Add the query to `PaymentRepository.java`**

Find:
```java
    @Query("SELECT COUNT(p) FROM Payment p " +
           "WHERE p.createdAt >= :from AND p.createdAt < :to AND p.status = 'SUCCESSFUL'")
    long countSuccessfulBetween(@Param("from") Instant from, @Param("to") Instant to);
}
```

Replace with:
```java
    @Query("SELECT COUNT(p) FROM Payment p " +
           "WHERE p.createdAt >= :from AND p.createdAt < :to AND p.status = 'SUCCESSFUL'")
    long countSuccessfulBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** Fallback sweep target for PendingPaymentExpiryJob — anything the checkout.session.expired webhook missed. */
    @Query("SELECT p FROM Payment p WHERE p.status = :status AND p.expiresAt IS NOT NULL AND p.expiresAt < :now")
    List<Payment> findExpiredPending(@Param("status") PaymentStatus status, @Param("now") Instant now);
}
```

- [ ] **Step 4: Create `PendingPaymentExpiryJob.java`**

Create `api-service/src/main/java/com/tinniestudio/api/modules/jobs/PendingPaymentExpiryJob.java`:

```java
package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.billing.repository.PaymentRepository;
import com.tinniestudio.api.modules.billing.service.SubscriptionService;
import com.tinniestudio.api.modules.jobs.entity.JobExecutionLog;
import com.tinniestudio.api.shared.entity.DomainEnums.PaymentStatus;
import com.tinniestudio.api.shared.entity.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Every 5 minutes: fallback sweep for PENDING Payments whose Stripe Checkout Session has passed
 * its own recorded expiry (Payment.expiresAt) but never got a checkout.session.expired webhook
 * (missed delivery, downtime, etc). This only ever catches what the webhook fast path
 * (StripeWebhookController) missed — on the normal path the Payment is already FAILED by the time
 * this job runs, so a typical run processes zero items.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingPaymentExpiryJob {

    private final PaymentRepository paymentRepository;
    private final SubscriptionService subscriptionService;
    private final JobLogger jobLogger;

    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "PendingPaymentExpiryJob", lockAtMostFor = "4m", lockAtLeastFor = "1m")
    public void run() {
        JobExecutionLog logEntry = jobLogger.start("PendingPaymentExpiryJob");
        try {
            List<Payment> expired = paymentRepository.findExpiredPending(PaymentStatus.PENDING, Instant.now());
            for (Payment payment : expired) {
                subscriptionService.failPayment(payment.getProviderReference(), "Checkout session expired (fallback sweep)");
            }
            jobLogger.success(logEntry, expired.size());
        } catch (Exception e) {
            jobLogger.failure(logEntry, e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :api-service:test --tests BackgroundJobsTest`
Expected: PASS — all tests in this class, including the 3 new ones.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew :api-service:test`
Expected: `BUILD SUCCESSFUL`, modulo this repo's known pre-existing unrelated Testcontainers/environment failures (if any — distinguish those from anything newly introduced).

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/billing/repository/PaymentRepository.java api-service/src/main/java/com/tinniestudio/api/modules/jobs/PendingPaymentExpiryJob.java api-service/src/test/java/com/tinniestudio/api/modules/jobs/BackgroundJobsTest.java
git commit -m "feat: add PendingPaymentExpiryJob fallback sweep for expired pending payments"
```

---

## Self-Review Notes

- **Spec coverage:** §1 (real, shorter expiry + `expires_at` column) — Tasks 1 & 2; §2 (webhook fast path reusing `failPayment`) — Task 3; §3 (fallback sweep job following `ExpiredUploadSessionCleanupJob`'s template) — Task 4. All three numbered sections of the design doc have a corresponding task.
- **Two deviations from the spec's literal text, both evidence-based and called out up front (not buried mid-task):** (1) 30 minutes instead of 15 — Stripe's own bundled SDK javadoc states 30 minutes is the hard minimum `expires_at` Stripe's API accepts; 15 minutes would make every checkout call throw `InvalidRequestException` in production. (2) The webhook case looks up by `session.getPaymentIntent() != null ? session.getPaymentIntent() : session.getId()`, not literally `session.getId()` as the spec's snippet showed — reading `SubscriptionServiceImpl.initiateCheckout()` (the actual code the spec cites as its own confirmation) shows `providerReference` is stored as the PaymentIntent id whenever one exists, which in `Mode.PAYMENT` is essentially always immediately. Both deviations are pure bugfixes relative to spec intent, not scope changes — the design's core mechanism (fast path + fallback sweep, both reusing `failPayment`) is unchanged.
- **Non-goals respected:** no retroactive backfill of `expiresAt` (Task 1's migration only adds the nullable column, no `UPDATE`); no change to `payment_intent.payment_failed`'s existing behavior (Task 3 only adds a new `case`, doesn't touch the existing one); the fallback job never calls Stripe's API, only reads `Payment.expiresAt` (Task 4's `PendingPaymentExpiryJob` has no Stripe SDK import at all — consistent with the `StripeService` interface's own constitution comment restricting `com.stripe.*` imports to `StripeServiceImpl`).
- **Placeholder scan:** no task contains a "TODO", "..." elision, or pseudocode — every find/replace block is the complete, real surrounding code as read from the actual files on disk during planning, including full class bodies for the two files (`StripeServiceTest.java`) that were replaced wholesale because the whole file was short enough to show in full.
- **Type consistency:** `expiresAt` is `Instant` end-to-end — `StripeService.CreateCheckoutResult.expiresAt()` returns `Instant`, `StripeServiceImpl` computes it as `Instant`, `SubscriptionServiceImpl` passes it straight through to `Payment.setExpiresAt(Instant)`, `PaymentRepository.findExpiredPending` compares `Instant` to `Instant`. The only place it's converted to a primitive is `.getEpochSecond()` at the single point Stripe's raw params API actually requires a `Long` (`SessionCreateParams.Builder.setExpiresAt(Long)`) — never re-derived or duplicated elsewhere.
- **Ordering dependency respected:** Task 2 (compute + persist `expiresAt`) must land before Task 4 (sweep job queries it) can find anything real to test end-to-end-in-spirit, and before Task 3's webhook fast path is the *only* path exercised in practice — this plan's task order (1 → 2 → 3 → 4) matches that dependency chain; Task 3 and Task 4 do not depend on each other and could be reordered without breaking anything, but are kept in spec order for readability.
