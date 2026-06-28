# Data Model: User Profile + Settings & Subscription Billing

**Phase**: 1 — Design
**Date**: 2026-05-31
**Branch**: `002-user-profile-billing`

---

## New Entities

### UserProfile

Extends user identity with editable preference fields. One-to-one with `users`. The `users` table remains the auth-domain source of truth for name, avatar, and email.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| `userId` | UUID | PK, FK → users(id) ON DELETE CASCADE | One-to-one; user_id is the PK |
| `bio` | TEXT | NULLABLE | Free-form biography |
| `languageCode` | VARCHAR(10) | NULLABLE, DEFAULT 'en' | IETF BCP-47 language tag |
| `countryCode` | VARCHAR(10) | NULLABLE | ISO 3166-1 alpha-2 country code |
| `timezone` | VARCHAR(100) | NULLABLE | IANA timezone name (e.g., Africa/Lagos) |
| `notificationEmail` | BOOLEAN | NOT NULL, DEFAULT true | Email notification opt-in |
| `updatedAt` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | Auto-updated via trigger |

**Table**: `user_profiles`
**Migration**: `V9__add_user_profiles.sql`
**JPA**: `@OneToOne(fetch = FetchType.LAZY)` on `User`; or standalone entity with `@Id = userId`.

---

### Payment

Tracks each Stripe card payment transaction. Linked to the subscription it created or attempted to create.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| `id` | UUID | PK, DEFAULT gen_random_uuid() | |
| `userId` | UUID | NOT NULL, FK → users(id) | Denormalized for fast user payment history queries |
| `subscriptionId` | UUID | NULLABLE, FK → user_subscriptions(id) | Set after subscription is created on success |
| `planId` | UUID | NOT NULL, FK → subscription_plans(id) | Plan being purchased |
| `provider` | VARCHAR(20) | NOT NULL, DEFAULT 'STRIPE' | Always STRIPE for this batch |
| `providerReference` | VARCHAR(255) | NOT NULL, UNIQUE | Stripe Payment Intent ID (pi_...) — idempotency key |
| `amount` | DECIMAL(10,2) | NOT NULL | Amount charged (after coupon discount) |
| `currency` | VARCHAR(3) | NOT NULL | ISO 4217 currency code (e.g., NGN, CAD) |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING' | PENDING, SUCCESSFUL, FAILED, REFUNDED |
| `autoRenew` | BOOLEAN | NOT NULL, DEFAULT true | Captured at checkout; applied to resulting subscription |
| `couponId` | UUID | NULLABLE, FK → coupons(id) | Coupon applied (if any) |
| `discountAmount` | DECIMAL(10,2) | NULLABLE | Amount discounted (for audit trail) |
| `paidAt` | TIMESTAMPTZ | NULLABLE | Set when Stripe confirms payment |
| `failureReason` | TEXT | NULLABLE | Stripe failure message (from webhook) |
| `createdAt` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

**Table**: `payments`
**Indexes**:
- `idx_payments_user_id ON payments(user_id)`
- `idx_payments_provider_ref ON payments(provider_reference)` (UNIQUE — used for idempotency)
- `idx_payments_subscription_id ON payments(subscription_id)`

**Migration**: `V10__add_payments.sql`

---

## Modified Entities

### UserSubscription (additive)

Two new fields to support auto-renew tracking and cancellation audit trail:

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| `autoRenew` | BOOLEAN | NOT NULL, DEFAULT true | **Already exists** — confirmed correct per entity alignment task |
| `cancelledAt` | TIMESTAMPTZ | NULLABLE | NEW — set when user cancels via PATCH /subscriptions/cancel |

**Migration**: `V11__add_subscription_cancelled_at.sql` — `ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;`

---

### User (no structural changes)

The `users` table is unchanged. `firstName`, `lastName`, `displayName`, `avatarUrl`, `phoneNumber` already exist. Avatar URL updates write directly to `users.avatar_url`.

---

## Entity Relationships

```
users
  ├── user_profiles (1:1 — lazy, profile domain)
  ├── user_sessions (1:N — auth domain, already implemented)
  ├── user_subscriptions (1:N — billing domain, already implemented)
  └── payments (1:N — billing domain, new)

user_subscriptions
  ├── subscription_plans (N:1 — plan reference)
  └── payments (1:N — payment history)

payments
  ├── users (N:1 — owner)
  ├── subscription_plans (N:1 — what was purchased)
  ├── user_subscriptions (N:1, NULLABLE — the subscription created)
  └── coupons (N:1, NULLABLE — coupon applied)
```

---

## Flyway Migration Order

| Migration | File | Description |
|-----------|------|-------------|
| V9 | `V9__add_user_profiles.sql` | Create `user_profiles` table + `updated_at` trigger |
| V10 | `V10__add_payments.sql` | Create `payments` table + indexes |
| V11 | `V11__add_subscription_cancelled_at.sql` | Add `cancelled_at` to `user_subscriptions` |

---

## Avatar Upload Session (Lightweight)

Since Batch 6 (generic Upload Session System) is not yet implemented, avatar upload uses an **inline flow** scoped to this batch only:

1. `PATCH /users/me/avatar` with `mode=UPLOAD` → calls `StorageService.generateUploadUrl(key, mimeType, maxBytes, ttl)` → returns `{ uploadUrl, storageKey, expiresAt }`.
2. Client uploads directly to storage.
3. `POST /users/me/avatar/confirm` with `{ storageKey }` → calls `StorageService.objectExists(storageKey)` → updates `users.avatar_url = CDN_BASE_URL + "/" + storageKey`.

**Storage key pattern**: `avatars/{userId}/{uuid}.{ext}` (e.g., `avatars/abc123/f47ac10b.jpg`).

No `UploadSession` DB record is created in this batch. Batch 6 will introduce the generic session flow.

---

## Redis Keys (Constitution §VI)

| Key Pattern | TTL | Purpose |
|-------------|-----|---------|
| `tinnie:lock:subscription-expiration` | 2 hours | Distributed lock for expiration job |
| `tinnie:lock:subscription-expiry-reminder` | 2 hours | Distributed lock for reminder job |

---

## Stripe Data Model

Stripe-side entities are not stored in our DB beyond the `providerReference` (Payment Intent ID). The Stripe dashboard serves as the audit trail for raw payment data. Our `Payment` entity is our authoritative record.

| Stripe Object | Our Reference | Notes |
|---------------|---------------|-------|
| PaymentIntent | `payments.providerReference` | Created at checkout, used for idempotency |
| Customer | Not stored (this batch) | Future batch may add Stripe Customer ID to `users` |
