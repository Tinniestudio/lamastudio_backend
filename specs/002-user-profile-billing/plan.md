# Implementation Plan: User Profile + Settings & Subscription Billing

**Branch**: `002-user-profile-billing` | **Date**: 2026-05-31 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-user-profile-billing/spec.md`

---

## Summary

Deliver user profile management (Batch 2) and Stripe-powered subscription billing (Batch 12) on top of the existing Auth Refactor foundation. The primary technical approach: (1) create a `user_profiles` table extending user identity with preference fields; (2) add a `payments` table for Stripe card transactions; (3) wire Stripe Payment Intents for checkout with a `StripeService` interface; (4) implement subscription lifecycle via Stripe webhooks + daily background jobs; and (5) reuse existing `CouponService` for coupon validation at checkout. All billing infrastructure accesses Stripe through a single abstraction layer.

---

## Technical Context

**Language/Version**: Java 21 / Spring Boot 3.3.5

**Primary Dependencies**: Spring Security 6, Spring Data JPA, stripe-java SDK (v26.x), Lettuce (Redis), Flyway, BCryptPasswordEncoder, Resend (email), springdoc-openapi, Spring Scheduling

**Storage**: PostgreSQL (primary) + Redis (job locks) + S3/MinIO (avatar file storage via `StorageService`)

**Testing**: JUnit 5 + Mockito (unit), Spring Boot Test + Testcontainers (integration)

**Target Platform**: Linux server (Spring Boot fat JAR)

**Performance Goals**: Profile GET/PATCH < 500ms; checkout initiation < 2s; webhook processing < 1s

**Constraints**:
- All Stripe SDK calls isolated behind `StripeService` interface (constitution §VI)
- All config via `@ConfigurationProperties` — no `@Value` or `System.getenv()` in service classes
- No cross-domain repository injection (constitution §IV)
- Card payment only — no wallets, BNPL, bank transfer
- Email delivery via existing `EmailService` (not queued — Batch 15 will add queue)

**Scale/Scope**: ~10k users initial target; 2 paid plans (SILVER, GOLD) + system FREE tier; Stripe test mode during development

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Rule | Status | Notes |
|------|--------|-------|
| §I — Feature lifecycle | ✅ PASS | Full specify → plan → tasks → implement cycle |
| §II — Batch boundary | ✅ PASS | Batch 2 (User domain) + Batch 12 (Billing domain) are bounded capabilities; no combining of unrelated domains |
| §IV — Domain ownership | ✅ PASS | `UserProfileService` owns user_profiles mutations; `SubscriptionService` owns payment + subscription mutations; `StripeService` wraps external SDK; no cross-domain repository injection |
| §V — Scalability | ✅ PASS | Background jobs use Redis distributed locks for horizontal scaling; `StripeService` interface allows provider swap without domain changes |
| §VI — Infrastructure abstraction | ✅ PASS | Stripe SDK: behind `StripeService` interface only; S3: behind `StorageService`; Redis: behind `CacheService`; all config via `@ConfigurationProperties` |
| §VIII — Drift prevention | ✅ PASS | No `@Value` in services; no direct `stripe-java` imports outside `StripeServiceImpl`; no `RedisTemplate` in domain services |
| §IX — Shared contracts | ✅ PASS | Standard `{ success, data, error }` envelope; machine-readable error codes; paginated payment history uses `PageResult<T>` |
| §X — Multi-actor security | ✅ PASS | No changes to JWT filter chains; existing `UserSecurityFilterChain` protects all `/users/**` and `/subscriptions/**` endpoints; `/webhooks/stripe` is public with Stripe signature validation |

**Post-design re-check**: All gates pass. No violations.

---

## Project Structure

### Documentation (this feature)

```text
specs/002-user-profile-billing/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 — decisions resolved
├── data-model.md        # Phase 1 — entity design
├── quickstart.md        # Phase 1 — developer onboarding
├── contracts/
│   └── api-contracts.md # Phase 1 — REST endpoint contracts
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code

```text
src/main/java/com/tinniestudio/backend/
├── modules/
│   ├── user/
│   │   ├── controller/
│   │   │   └── UserProfileController.java          # NEW — /users/me endpoints
│   │   ├── service/
│   │   │   ├── UserProfileService.java             # NEW — interface
│   │   │   └── UserProfileServiceImpl.java         # NEW — implementation
│   │   ├── dto/
│   │   │   ├── UserProfileResponse.java            # NEW
│   │   │   ├── UpdateProfileRequest.java           # NEW
│   │   │   ├── AvatarUpdateRequest.java            # NEW (mode + conditional fields)
│   │   │   ├── AvatarConfirmRequest.java           # NEW
│   │   │   ├── UpdateNotificationRequest.java      # NEW
│   │   │   └── UpdatePasswordRequest.java          # NEW
│   │   └── repository/
│   │       └── UserProfileRepository.java          # NEW — JpaRepository<UserProfile, UUID>
│   │
│   └── billing/
│       ├── controller/
│       │   ├── SubscriptionController.java         # NEW — /subscriptions/* endpoints
│       │   └── StripeWebhookController.java        # NEW — POST /webhooks/stripe
│       ├── service/
│       │   ├── SubscriptionService.java            # EXTENDED (interface) + CheckoutService methods
│       │   ├── SubscriptionServiceImpl.java        # NEW — checkout, cancel, status, expiry
│       │   ├── StripeService.java                  # NEW — interface wrapping Stripe SDK
│       │   └── StripeServiceImpl.java              # NEW — stripe-java SDK calls only
│       ├── dto/
│       │   ├── CheckoutRequest.java                # NEW
│       │   ├── CheckoutResponse.java               # NEW
│       │   ├── SubscriptionStatusResponse.java     # NEW (with payment history)
│       │   ├── PlanResponse.java                   # NEW
│       │   └── CouponValidationRequest.java        # NEW
│       └── repository/
│           └── PaymentRepository.java              # NEW — JpaRepository<Payment, UUID>
│           # SubscriptionPlanRepository — EXISTS
│           # UserSubscriptionRepository — EXISTS
│           # CouponRepository — EXISTS
│           # CouponRedemptionRepository — EXISTS
│
├── shared/
│   ├── entity/
│   │   ├── UserProfile.java                        # NEW — user_profiles table
│   │   └── Payment.java                            # NEW — payments table
│   ├── config/
│   │   └── StripeProperties.java                   # NEW — @ConfigurationProperties("stripe")
│   └── jobs/
│       ├── SubscriptionExpirationJob.java          # NEW — @Scheduled daily 03:00
│       └── SubscriptionExpiryReminderJob.java      # NEW — @Scheduled daily 08:00

src/main/resources/
├── application.yml                                 # MODIFIED — add stripe.* config block
└── db/migration/
    ├── V9__add_user_profiles.sql                   # NEW
    ├── V10__add_payments.sql                       # NEW
    └── V11__add_subscription_cancelled_at.sql      # NEW

src/test/java/com/tinniestudio/backend/
├── user/
│   ├── service/UserProfileServiceTest.java         # NEW — unit tests
│   └── UserProfileIntegrationTest.java            # NEW — integration tests
└── billing/
    ├── service/SubscriptionServiceTest.java        # NEW — unit tests
    ├── StripeWebhookIntegrationTest.java          # NEW — webhook integration
    └── CheckoutIntegrationTest.java               # NEW — checkout flow integration
```

**Structure Decision**: Modular monolith, existing structure preserved. User profile logic in `modules/user/` (already exists for `UserRepository`, `UserService`). Billing logic extends `modules/billing/` (CouponService already here). Shared entities in `shared/entity/` (matches existing pattern). Background jobs in `shared/jobs/` (new package, follows §VI job standards).

---

## Complexity Tracking

No constitution violations detected. No complexity justification table required.

---

## Implementation Batches

The implementation is divided into ordered batches. Each batch must reach COMPLETE before the next starts.

---

### Batch A: Database Foundation

**Goal**: All new tables exist via Flyway; entities are mapped; `StripeProperties` is bound. No service logic yet.

**Deliverables**:
- `pom.xml`: add `stripe-java` dependency
- `application.yml`: add `stripe.secretKey` and `stripe.webhookSecret` placeholders
- `StripeProperties.java`: `@ConfigurationProperties("stripe")` with `secretKey`, `webhookSecret` fields
- V9: `user_profiles` table + `updated_at` trigger
- V10: `payments` table + indexes
- V11: `cancelled_at` column on `user_subscriptions`
- JPA entities: `UserProfile`, `Payment`
- Repositories: `UserProfileRepository`, `PaymentRepository`

**Completion gates**:
- Flyway V9–V11 run cleanly on a fresh DB (sequential, no conflicts with V1–V8)
- `UserProfile` and `Payment` entities load without error on startup
- Repository integration tests pass against Testcontainers PostgreSQL

---

### Batch B: User Profile Module

**Goal**: Full `/users/me` CRUD operational — view, edit, avatar, password change, notifications.

**Deliverables**:
- `UserProfileServiceImpl`:
  - `getProfile(userId)`: joins `User` + `UserProfile` (lazy) → `UserProfileResponse`
  - `updateProfile(userId, request)`: partial update on `User` (name/avatar from `users`) + `UserProfile` (bio/prefs)
  - `updateNotifications(userId, request)`: updates `user_profiles.notification_email`
  - `setAvatarByUrl(userId, avatarUrl)`: validates HTTPS URL → updates `users.avatar_url`
  - `initiateAvatarUpload(userId, mimeType, fileSizeBytes)`: validates MIME type + size → calls `StorageService.generateUploadUrl()` → returns upload URL + storageKey
  - `confirmAvatarUpload(userId, storageKey)`: calls `StorageService.objectExists()` → updates `users.avatar_url = CDN_BASE_URL + "/" + storageKey`
  - `changePassword(userId, currentPassword, newPassword, currentSessionId)`: validates current hash → encodes new → saves → calls `SessionService.revokeAllExcept(userId, currentSessionId)` → sends security email
- `UserProfileController`: maps all `/users/me/**` endpoints with `@AuthenticationPrincipal`
- All endpoints documented with SpringDoc

**Completion gates**:
- GET /users/me returns all profile fields in expected shape (matches contracts)
- PATCH /users/me partial update works (only provided fields update)
- Avatar upload mode: presigned URL returned, confirm endpoint updates avatarUrl
- Avatar URL mode: avatarUrl updated immediately, HTTPS enforced
- Password change revokes other sessions, not current session
- OAuth account rejects password change with clear error
- All unit + integration tests pass

---

### Batch C: Stripe Checkout + Subscription Status

**Goal**: Users can checkout via Stripe card, view subscription status, apply coupons, and cancel.

**Deliverables**:
- `StripeServiceImpl`:
  - `createPaymentIntent(amount, currency, metadata)`: calls Stripe SDK `PaymentIntent.create()` → returns Payment Intent ID and hosted payment URL
  - `constructWebhookEvent(payload, sigHeader)`: calls `Webhook.constructEvent()` with `webhookSecret`
- `SubscriptionServiceImpl`:
  - `listPlans()`: queries `SubscriptionPlanRepository` filtering `name IN ('SILVER','GOLD') AND isActive=true`
  - `validateCoupon(code, userId, planId)`: delegates to existing `CouponService.validateCoupon()`; computes discounted price
  - `initiateCheckout(userId, request)`: validates no active subscription → validates coupon (if provided) → computes final amount → creates `Payment` (PENDING) → calls `StripeService.createPaymentIntent()` → sets `providerReference` → saves Payment → returns `CheckoutResponse`
  - `activateSubscription(paymentIntentId)`: idempotency check → update `Payment.status=SUCCESSFUL` → create `UserSubscription` (ACTIVE, startDate=now, endDate=now+billingCycle) → link `Payment.subscriptionId` → if coupon: `CouponService.redeemCoupon()` → send activation email
  - `failPayment(paymentIntentId, reason)`: update `Payment.status=FAILED`, `failureReason=reason` → send failure email
  - `getSubscriptionStatus(userId)`: load latest `UserSubscription` + payment history → `SubscriptionStatusResponse`
  - `cancelSubscription(userId)`: load active subscription → check not already cancelled → set `autoRenew=false`, `cancelledAt=now()` → save → send confirmation email
- `StripeWebhookController`: validates Stripe signature → routes `payment_intent.succeeded` → `activateSubscription()`; routes `payment_intent.payment_failed` → `failPayment()`
- `SubscriptionController`: maps all `/subscriptions/**` endpoints

**Completion gates**:
- GET /subscriptions/plans returns only SILVER + GOLD (not FREE)
- POST /subscriptions/checkout creates Payment (PENDING) + returns Stripe payment URL
- `payment_intent.succeeded` webhook activates subscription atomically (idempotent — second webhook ignored)
- `payment_intent.payment_failed` webhook marks payment FAILED and sends email
- Invalid Stripe signature returns 400 (no processing)
- Coupon applied at checkout reduces amount; redemption recorded in webhook handler (on success)
- POST /subscriptions/apply-coupon validates all 4 rules and returns discounted price
- GET /subscriptions/me returns correct subscription state for active + FREE users
- PATCH /subscriptions/cancel sets autoRenew=false, retains ACTIVE status
- All unit + integration tests pass

---

### Batch D: Background Jobs

**Goal**: Subscription expiration and renewal reminder emails run on schedule.

**Deliverables**:
- `SubscriptionExpirationJob`:
  - `@Scheduled(cron = "0 0 3 * * *")`
  - Acquire Redis lock `tinnie:lock:subscription-expiration` (TTL 2h) via `CacheService`
  - Query: `UserSubscriptions WHERE status=ACTIVE AND endDate < now()`
  - Batch update status to EXPIRED
  - Send SUBSCRIPTION_EXPIRED email to each user via `EmailService`
- `SubscriptionExpiryReminderJob`:
  - `@Scheduled(cron = "0 0 8 * * *")`
  - Acquire Redis lock `tinnie:lock:subscription-expiry-reminder` (TTL 2h)
  - Query: `UserSubscriptions WHERE status=ACTIVE AND autoRenew=false AND endDate BETWEEN now() AND now()+3days`
  - Send SUBSCRIPTION_EXPIRING_SOON email to each user
- Both jobs: fully idempotent, log start/end with duration, failure does not crash application

**Completion gates**:
- Expiration job tested with mock expired subscription (Testcontainers)
- Reminder job sends email for subscriptions expiring in < 3 days
- Distributed lock prevents double execution (verified by attempting concurrent job runs)
- Jobs start without error on application boot

---

## Completion Gate Summary (Constitution §VII)

| Gate | Checked In |
|------|-----------|
| `functionalValidation` | Each batch — happy path + all error cases per contracts |
| `securityValidation` | Batch B (password change session revocation) + Batch C (Stripe webhook signature validation) |
| `integrationValidation` | Batch C (webhook → subscription activation end-to-end) + Batch D (job execution verified) |
| `performanceValidation` | Batch B (profile GET < 500ms); Batch C (checkout < 2s, webhook < 1s); no N+1 on payment history |
| `rollbackReadiness` | V9–V11 verified on fresh DB; `cancelled_at` addition is additive (safe rollback via dropping column) |
