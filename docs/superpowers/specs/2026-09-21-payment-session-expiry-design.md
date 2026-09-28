# Payment Session Expiry — Design

**Date:** 2026-09-21
**Status:** Approved, ready for planning
**Repo:** `server` (api-service)
**Depends on:** none — entirely self-contained.

## Context

A `Payment` stuck in `PENDING` status — whose Stripe Checkout Session was abandoned without completing payment — stays `PENDING` forever today. This is misleading: it looks like an in-progress payment when it's actually dead. Nothing in the codebase ever transitions it to `FAILED`.

Investigated directly against the real source during planning:
- `StripeServiceImpl.createCheckoutSession()` already creates a real Stripe Checkout Session (the "session" concept the original request asked about) but never calls `.setExpiresAt(...)` — Stripe's default 24-hour expiry applies, not the 15 minutes intended.
- `SubscriptionService.failPayment(String reference, String reason)` already exists, currently only invoked from the `payment_intent.payment_failed` webhook case (an actual declined card) — looks the `Payment` up by `providerReference` and flips it to `FAILED` with a reason, notifying the user.
- `StripeWebhookController`'s event switch has a `default -> log.debug(...)` for anything unhandled — **Stripe already sends a `checkout.session.expired` event automatically on any session expiry**, but this app silently ignores it today.
- This project has a mature scheduled-job pattern (`api-service/src/main/java/com/tinniestudio/api/modules/jobs/`) — `@Scheduled` + ShedLock `@SchedulerLock` + `JobLogger.start/success/failure` writing to `job_execution_log`. `ExpiredUploadSessionCleanupJob` is the closest existing template.

## Goal

Flip a `PENDING` payment to `FAILED` once its checkout session has genuinely expired, via both a fast webhook-driven path and a fallback sweep for anything the webhook missed.

## Design

### 1. A real, shorter expiry on session creation

`StripeServiceImpl.createCheckoutSession()` sets `.setExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES).getEpochSecond())` on the `SessionCreateParams.Builder`, replacing Stripe's default 24-hour window with the intended 15 minutes.

`Payment` gains a new nullable `expires_at` column, set to the same value at checkout creation — this is the app's own record of the deadline, so the fallback sweep (§3) never needs to call Stripe's API to check. Nullable because pre-existing rows have no known real expiry and should simply never match the new sweep — no retroactive backfill, no change to their current (already-stuck) behavior.

### 2. Webhook fast path

`StripeWebhookController`'s event switch gains:

```java
case "checkout.session.expired" -> {
    Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
    if (obj.isPresent() && obj.get() instanceof Session session) {
        subscriptionService.failPayment(session.getId(), "Checkout session expired");
    }
}
```

Reuses the existing `failPayment(reference, reason)` exactly as `payment_intent.payment_failed` already does — no new fail-a-payment logic, just a new trigger for the existing one. `session.getId()` matches `Payment.providerReference` as stored at checkout-creation time (before a `PaymentIntent` exists, `providerReference` is the checkout session ID — confirmed against `SubscriptionServiceImpl`'s checkout-creation code).

### 3. Fallback sweep job

A new `PendingPaymentExpiryJob`, following `ExpiredUploadSessionCleanupJob`'s exact template (`@Scheduled` + `@SchedulerLock` + `JobLogger`). Runs every 5 minutes — tighter than the existing hourly cleanup jobs, since payment-status accuracy is more time-sensitive than storage cleanup. Queries `PaymentRepository` for `status = PENDING AND expiresAt IS NOT NULL AND expiresAt < now()`, and calls the same `failPayment(payment.getProviderReference(), "Checkout session expired (fallback sweep)")` per match.

This only ever catches what the webhook missed. On the normal path, by the time this job runs the payment is already `FAILED` from §2 and won't match the query — this job's typical run processes zero items.

## Non-goals

- No client-side/UX changes anywhere — entirely server-side.
- No retroactive backfill of `expires_at` for existing `PENDING` payments — they keep their current (already-stuck) behavior unchanged, matching the nullable-column design.
- No change to `payment_intent.payment_failed`'s existing behavior — this adds a new, parallel path, not a replacement.
- No Stripe API calls from the fallback job — it only reads this app's own `expires_at` column, never queries Stripe directly.
