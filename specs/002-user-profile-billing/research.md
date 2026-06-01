# Research: User Profile + Settings & Subscription Billing

**Phase**: 0 — Research
**Date**: 2026-05-31
**Branch**: `002-user-profile-billing`

---

## Decision 1 — Stripe Payment Model: Payment Intents vs Stripe Subscriptions

**Decision**: Use one-time **Stripe Payment Intents** for both `autoRenew=true` and `autoRenew=false` subscriptions. Auto-renewal is managed by a platform-side background job (not Stripe Billing's subscription lifecycle).

**Rationale**:
- Stripe Subscription objects require managing Stripe-side subscription state (pause, cancel, upgrade) in parallel with our DB state — two sources of truth.
- Our `UserSubscription` entity is already the authoritative source of subscription state. Delegating lifecycle to Stripe would require syncing via additional webhooks (`customer.subscription.updated`, `invoice.payment_failed`, etc.).
- One-time Payment Intents + a daily renewal job (already planned in BATCH-PLAN.md Batch 17) keeps all business logic in the API service and avoids Stripe Subscription complexity.
- The auto-renew background job creates a new Payment Intent at renewal time, captures payment, and extends the subscription — same flow as initial checkout.

**Alternatives Considered**:
- Stripe Subscriptions — rejected: adds Stripe-side state machine, multiple webhook event types, and couples our subscription lifecycle to Stripe's billing engine.
- Stripe Checkout Sessions — rejected: opinionated redirect flow; we need a hosted Payment Intent URL that integrates with our frontend checkout page.

**Implementation**: `stripe-java` SDK, `PaymentIntent.create()` with `payment_method_types: ["card"]`. Return `client_secret` to frontend to mount Stripe Elements, or use `payment_intent.confirm()` with `return_url` for a redirect flow.

---

## Decision 2 — User Profile Storage: Separate Table vs Extend Users

**Decision**: Create a **separate `user_profiles` table** (one-to-one with `users`), containing only the new profile fields (`bio`, `languageCode`, `countryCode`, `timezone`, `notificationEmail`). Name/avatar fields stay on `users`.

**Rationale**:
- Aligns with BATCH-PLAN.md design and the spec assumption.
- Separates identity fields (owned by Auth domain) from preference fields (owned by User Profile domain) — no domain boundary violations.
- The `users` table is auth-owned. Adding preference fields directly to it would couple the auth domain to profile domain state.
- JPA lazy loading means the JOIN only happens when profile fields are actually needed.

**Alternatives Considered**:
- Add `bio`, `languageCode`, etc. directly to `users` — rejected: violates Auth domain ownership (constitution §IV). The `users` table is auth-owned.
- Use a `user_settings` JSONB column on `users` — rejected: untyped, harder to validate and query.

**Table**: `user_profiles` with `user_id UUID PRIMARY KEY REFERENCES users(id)` (one-to-one, lazy-loaded).

---

## Decision 3 — Avatar: File Upload vs URL in One Endpoint

**Decision**: `PATCH /users/me/avatar` accepts a **JSON body with a discriminating field**: `{ "mode": "UPLOAD" }` returns a presigned URL session; `{ "mode": "URL", "avatarUrl": "https://..." }` sets the URL directly.

**Rationale**:
- Single endpoint avoids confusing clients with two separate paths for the same conceptual action.
- `mode` discriminator is clear and extensible (a future `mode: "GRAVATAR"` could be added without breaking existing clients).
- Avoids multipart/form-data for the URL-set case — the JSON body is simpler to validate.

**Avatar Upload Path (UPLOAD mode)**:
1. `PATCH /users/me/avatar` with `{ "mode": "UPLOAD", "mimeType": "image/jpeg", "fileSizeBytes": 1048576 }` → validates type/size → calls `StorageService.generateUploadUrl()` → returns `{ sessionId, uploadUrl, expiresAt }`.
2. Client uploads directly to storage bucket.
3. `POST /uploads/{sessionId}/complete` (existing pattern from Batch 6) → `StorageService.objectExists()` → updates `users.avatar_url`.

**Note**: Since Batch 6 (Upload Session System) is not yet implemented, avatar upload in this batch uses a **lightweight inline presigned URL flow** without a full `UploadSession` record. A `StorageService.generateUploadUrl()` call returns the URL directly; a `POST /users/me/avatar/complete` endpoint finalizes it (not the generic Batch 6 `/uploads/:sessionId/complete`). This is explicitly scoped to avatar only and will be superseded by Batch 6's generic upload session once implemented.

**Avatar URL Validation (URL mode)**:
- Must be a valid HTTPS URL.
- URL is stored as-is — no downloading, re-hosting, or content inspection.
- Max length: 2048 characters (matches existing `users.avatar_url` column length).

---

## Decision 4 — Subscription Expiration: Background Job Approach

**Decision**: Use a **Spring `@Scheduled` background job** with a Redis distributed lock for subscription expiration and renewal reminders.

**Rationale**:
- Aligns with BATCH-PLAN.md §17 (Background Jobs + Automation) which already plans `SubscriptionExpiration` and `SubscriptionExpiryReminder` jobs.
- Stripe webhooks cannot reliably trigger expiration in all cases (e.g., `autoRenew=false` subscriptions have no Stripe-side lifecycle).
- Redis distributed lock (`tinnie:lock:subscription-expiration`) prevents duplicate execution across service instances (constitution §V horizontal scaling).

**Schedule**: Expiration job at `0 0 3 * * *` (03:00 daily). Reminder job at `0 0 8 * * *` (08:00 daily).

---

## Decision 5 — Password Change Session Revocation

**Decision**: After a successful password change, call `SessionService.revokeAllUserSessions(userId, null)` — the same service already implemented in the Auth Refactor — **excluding** the current session (identified by the `sid` claim from the access token).

**Rationale**:
- `SessionService` is in the same module group and is the authoritative session manager.
- Excluding the current session prevents the user from being immediately logged out mid-flow, which is industry-standard UX.
- This is a service call (not repository injection) — no constitution §IV violation.

**Implementation**: `UserProfileService` depends on `SessionService` interface. `SessionService.revokeAllExcept(userId, currentSessionId)` is a new method added to the existing `SessionService` interface.

---

## Decision 6 — Email Delivery for Billing Events

**Decision**: Billing events (subscription activated, payment failed, expiring soon, expired) send emails **directly via `EmailService`** — not via a queue. The `EmailService` abstraction already exists in the auth module and wraps Resend/SendGrid.

**Rationale**:
- The full notification queue system (Batch 15) is not yet implemented.
- For this batch, direct `EmailService` calls are simpler and correct.
- When Batch 15 is implemented, billing events can be migrated to the queue without changing the billing service interface.
- `SubscriptionService` injects `EmailService` as a service interface — no domain repository injection (constitution §IV compliant).

---

## Decision 7 — Stripe Config and SDK Integration

**Decision**: Add `stripe-java` SDK to `pom.xml`. Create `StripeProperties` `@ConfigurationProperties` bean for `stripe.secretKey` and `stripe.webhookSecret`. Create `StripeService` interface + `StripeServiceImpl` wrapping the Stripe SDK.

**pom.xml addition**:
```xml
<dependency>
    <groupId>com.stripe</groupId>
    <artifactId>stripe-java</artifactId>
    <version>26.x.x</version>
</dependency>
```

**StripeProperties fields**:
- `stripe.secretKey` → bound from `${STRIPE_SECRET_KEY}`
- `stripe.webhookSecret` → bound from `${STRIPE_WEBHOOK_SECRET}`

**Constitution §VI compliance**: `StripeServiceImpl` is the only class that imports Stripe SDK types. Domain services (`SubscriptionService`) only call `StripeService` interface — no direct Stripe SDK usage in domain layer.

---

## Summary of All Decisions

| # | Topic | Decision |
|---|-------|----------|
| 1 | Stripe model | Payment Intents (one-time), auto-renew via background job |
| 2 | Profile storage | Separate `user_profiles` table, foreign key to `users` |
| 3 | Avatar endpoint | Single endpoint with `mode` discriminator (UPLOAD vs URL) |
| 4 | Subscription expiry | Spring `@Scheduled` + Redis distributed lock |
| 5 | Password change sessions | `SessionService.revokeAllExcept(userId, currentSessionId)` |
| 6 | Billing email delivery | Direct `EmailService` calls (not queued — Batch 15 TBD) |
| 7 | Stripe SDK | `stripe-java` SDK behind `StripeService` interface |
