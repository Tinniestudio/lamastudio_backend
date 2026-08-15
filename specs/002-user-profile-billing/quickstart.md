# Quickstart: User Profile + Settings & Subscription Billing

**For**: Developers onboarding to this feature branch
**Branch**: `002-user-profile-billing`
**Date**: 2026-05-31

---

## What This Feature Delivers

**Batch 2 — User Profile**:
- Users can view and edit their profile (name, bio, language, country, timezone)
- Users can upload a profile avatar (file upload or external URL)
- Users can change their password (revokes other sessions)
- Users can opt out of email notifications

**Batch 12 — Subscription Billing**:
- Users browse SILVER and GOLD plans
- Users checkout via Stripe card payment (auto-renew or manual)
- Stripe webhook activates subscriptions on successful payment
- Users can view their subscription status and payment history
- Users can cancel (retains access until period end)
- Daily jobs expire subscriptions and send renewal reminders

---

## New Environment Variables Required

```bash
# Stripe (card-only payment)
STRIPE_SECRET_KEY=sk_test_...
STRIPE_WEBHOOK_SECRET=whsec_...

# Storage (already required by storage service)
AWS_S3_BUCKET=tinniestudio-dev
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
CDN_BASE_URL=https://cdn.example.com

# Email (already required from Batch 1)
RESEND_API_KEY=...
```

---

## Key New Files

### User Profile Module

```
src/main/java/com/tinniestudio/backend/
├── modules/user/
│   ├── controller/
│   │   └── UserProfileController.java        # GET/PATCH /users/me, avatar, password, notifications
│   ├── service/
│   │   ├── UserProfileService.java           # Interface
│   │   └── UserProfileServiceImpl.java       # Implementation
│   ├── dto/
│   │   ├── UserProfileResponse.java
│   │   ├── UpdateProfileRequest.java
│   │   ├── AvatarUpdateRequest.java          # mode + conditional fields
│   │   ├── AvatarConfirmRequest.java
│   │   ├── UpdateNotificationRequest.java
│   │   └── UpdatePasswordRequest.java
│   └── repository/
│       └── UserProfileRepository.java        # JpaRepository<UserProfile, UUID>
└── shared/entity/
    └── UserProfile.java                      # NEW entity
```

### Billing Module

```
src/main/java/com/tinniestudio/backend/
├── modules/billing/
│   ├── controller/
│   │   ├── SubscriptionController.java       # /subscriptions/*
│   │   └── StripeWebhookController.java      # POST /webhooks/stripe
│   ├── service/
│   │   ├── SubscriptionService.java          # Interface
│   │   ├── SubscriptionServiceImpl.java
│   │   ├── StripeService.java                # Interface (wraps Stripe SDK)
│   │   └── StripeServiceImpl.java            # stripe-java SDK calls
│   └── dto/
│       ├── CheckoutRequest.java
│       ├── CheckoutResponse.java
│       ├── SubscriptionStatusResponse.java
│       ├── PlanResponse.java
│       └── CouponValidationRequest.java
├── shared/
│   ├── entity/
│   │   └── Payment.java                      # NEW entity
│   ├── config/
│   │   └── StripeProperties.java             # @ConfigurationProperties("stripe")
│   └── jobs/
│       ├── SubscriptionExpirationJob.java    # @Scheduled daily 03:00
│       └── SubscriptionExpiryReminderJob.java # @Scheduled daily 08:00
```

### New Flyway Migrations

```
src/main/resources/db/migration/
├── V9__add_user_profiles.sql
├── V10__add_payments.sql
└── V11__add_subscription_cancelled_at.sql
```

---

## Local Development Flow

### 1. Add Stripe test keys to environment

```bash
export STRIPE_SECRET_KEY=sk_test_...
export STRIPE_WEBHOOK_SECRET=whsec_...
```

### 2. Start the application

Flyway runs V9–V11 automatically on startup.

```bash
./mvnw spring-boot:run
```

### 3. Test profile update

```bash
# Get current profile
curl -H "Authorization: Bearer <access_token>" http://localhost:8080/users/me

# Update profile
curl -X PATCH http://localhost:8080/users/me \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{"bio": "I love movies", "countryCode": "NG", "timezone": "Africa/Lagos"}'
```

### 4. Test subscription checkout

```bash
# List plans
curl http://localhost:8080/subscriptions/plans

# Initiate checkout (replace planId with actual UUID from DB)
curl -X POST http://localhost:8080/subscriptions/checkout \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{"planId": "<SILVER_PLAN_UUID>", "autoRenew": true}'
# → Returns paymentUrl — open in browser to complete test payment

# Simulate Stripe webhook (use Stripe CLI in dev)
stripe listen --forward-to http://localhost:8080/webhooks/stripe
stripe trigger payment_intent.succeeded
```

### 5. Verify subscription activated

```bash
curl -H "Authorization: Bearer <access_token>" http://localhost:8080/subscriptions/me
# → Should return status: ACTIVE, plan: SILVER
```

---

## Testing Key Scenarios

### Coupon at Checkout

```bash
# Validate coupon first
curl -X POST http://localhost:8080/subscriptions/apply-coupon \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{"code": "TEST20", "planId": "<SILVER_PLAN_UUID>"}'

# Include in checkout
curl -X POST http://localhost:8080/subscriptions/checkout \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{"planId": "<SILVER_PLAN_UUID>", "autoRenew": false, "couponCode": "TEST20"}'
```

### Avatar Upload (file)

```bash
# Step 1: Get presigned URL
curl -X PATCH http://localhost:8080/users/me/avatar \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{"mode": "UPLOAD", "mimeType": "image/jpeg", "fileSizeBytes": 204800}'
# → Returns uploadUrl + storageKey

# Step 2: Upload directly to S3/MinIO
curl -X PUT "<uploadUrl>" \
  -H "Content-Type: image/jpeg" \
  --data-binary @my-photo.jpg

# Step 3: Confirm
curl -X POST http://localhost:8080/users/me/avatar/confirm \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{"storageKey": "avatars/uuid/f47ac10b.jpg"}'
```

### Avatar Set via URL

```bash
curl -X PATCH http://localhost:8080/users/me/avatar \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{"mode": "URL", "avatarUrl": "https://example.com/photo.jpg"}'
```

### Cancel Subscription

```bash
curl -X PATCH http://localhost:8080/subscriptions/cancel \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{}'
# → autoRenew=false, cancelledAt set, access until endDate
```

---

## Architecture Notes

- **Stripe Service** is the only class that imports Stripe SDK types — `SubscriptionService` depends on `StripeService` interface only (constitution §VI).
- **UserProfile** is owned by the user domain. **UserSubscription** and **Payment** are owned by the billing domain. No cross-domain repository injection.
- **Email** for billing events is sent via the existing `EmailService` directly (not queued) — this will be migrated to the queue in Batch 15.
- **Avatar upload** in this batch uses a lightweight inline flow without the full `UploadSession` entity — this is scoped to avatar only and will be replaced by Batch 6's generic upload session system.
- **Distributed locks** for background jobs use Redis keys `tinnie:lock:subscription-expiration` and `tinnie:lock:subscription-expiry-reminder` via `CacheService` (constitution §VI compliant).
