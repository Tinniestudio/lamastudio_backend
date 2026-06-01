# Feature Specification: User Profile + Settings & Subscription Billing

**Feature Branch**: `002-user-profile-billing`

**Created**: 2026-05-31

**Status**: Draft

**Input**: User description: "BATCH 2 — User Profile + Settings, BATCH 12 — Subscription + Billing (from BATCH-PLAN.md)"

**Covers**: Batch 2 (User Profile + Settings) and Batch 12 (Subscription + Billing)

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — View and Edit Personal Profile (Priority: P1)

A registered user navigates to their account settings and views their current profile. They update their display name, bio, preferred language, and country. Changes are reflected immediately the next time they view their profile.

**Why this priority**: Profile completeness is a baseline user engagement requirement. Users cannot personalize their experience — recommendations, language, notifications — until their profile is established. This story has zero dependencies on billing.

**Independent Test**: Can be tested end-to-end by registering a user, calling GET /users/me to see defaults, PATCHing with new values, and verifying GET /users/me reflects the updated values.

**Acceptance Scenarios**:

1. **Given** an authenticated user with no profile data beyond their registration email, **When** they call GET /users/me, **Then** they receive a response containing at minimum their email, roles, and empty optional profile fields.
2. **Given** an authenticated user, **When** they submit a PATCH /users/me with a new `displayName` and `bio`, **Then** subsequent GET /users/me returns the updated values without affecting any other field.
3. **Given** an authenticated user, **When** they submit a PATCH /users/me with an invalid `languageCode` (e.g., "xx"), **Then** a 400 is returned with a descriptive validation error.
4. **Given** a non-authenticated request, **When** GET or PATCH /users/me is called, **Then** a 401 is returned.

---

### User Story 2 — Subscribe to a Plan (Priority: P1)

A user browsing the platform views available paid subscription plans (SILVER, GOLD), selects one, and pays by card via Stripe. They can choose whether their subscription renews automatically at the end of each billing period or requires manual payment each cycle. After a successful payment, their account is immediately upgraded and they gain access to the plan's content tier.

**Why this priority**: Monetization is the platform's revenue source. Without a working checkout flow, no paid subscriptions can be acquired. This is the primary commercial transaction flow.

**Independent Test**: Can be tested by initiating checkout for a SILVER plan with `autoRenew: true`, simulating a successful Stripe payment webhook, and verifying the user's subscription becomes ACTIVE with `autoRenew=true` and the correct plan.

**Acceptance Scenarios**:

1. **Given** an authenticated user, **When** they call GET /subscriptions/plans, **Then** they receive only the SILVER and GOLD plans (FREE is not purchasable — it is system-assigned to new users) with pricing, device limits, and billing cycle details.
2. **Given** an authenticated user with no active paid subscription, **When** they initiate POST /subscriptions/checkout with a valid `planId` and `autoRenew: true`, **Then** a pending Stripe payment intent is created and a Stripe-hosted `paymentUrl` is returned; the `autoRenew` preference is stored on the resulting subscription.
3. **Given** an authenticated user, **When** they initiate checkout with `autoRenew: false`, **Then** the subscription is created with `autoRenew=false` and will not be renewed automatically at period end — the user must re-subscribe manually.
4. **Given** a successful Stripe payment webhook (`payment_intent.succeeded`) arrives with a matching `providerReference`, **When** the webhook is processed with a valid Stripe signature, **Then** the payment status is updated to SUCCESSFUL, a `UserSubscription` is created with status ACTIVE, and a subscription activation email is sent to the user.
5. **Given** a failed Stripe payment webhook (`payment_intent.payment_failed`), **When** processed, **Then** the payment record is updated to FAILED and a payment failure email is sent — no subscription is created.
6. **Given** a user already has an ACTIVE paid subscription, **When** they attempt to initiate another checkout, **Then** a 409 Conflict is returned — duplicate active subscriptions are not permitted.

---

### User Story 3 — Apply a Coupon at Checkout (Priority: P2)

A user enters a discount coupon code during checkout. The system validates the coupon (active, within date range, usage not exhausted, not previously used by this user), applies the discount to the plan price, and proceeds to payment with the reduced amount.

**Why this priority**: Coupon-driven acquisition is a key growth mechanism. Without coupon validation at checkout, marketing campaigns cannot function. Depends on the basic checkout flow (US2).

**Independent Test**: Can be tested by creating a coupon, applying it during checkout, verifying the discounted price is used for payment initialization, then attempting to reuse the same coupon and confirming rejection.

**Acceptance Scenarios**:

1. **Given** a valid, active coupon within its date window with remaining uses, **When** a user submits it via POST /subscriptions/apply-coupon (validation) or includes it in POST /subscriptions/checkout, **Then** the discount is applied to the final price and the user sees the reduced amount.
2. **Given** a coupon already used by this user, **When** the same user submits it at checkout, **Then** a 400 is returned with `reason: "already_used"`.
3. **Given** an expired coupon (past `validUntil`), **When** any user submits it, **Then** a 400 is returned with `reason: "expired"`.
4. **Given** a coupon that has reached its maximum use count, **When** any user submits it, **Then** a 400 is returned with `reason: "limit_reached"`.
5. **Given** a coupon code that does not exist, **When** a user submits it, **Then** a 400 is returned with `reason: "not_found"`.

---

### User Story 4 — View Current Subscription Status (Priority: P2)

A subscribed user views their account's subscription section and sees their current plan name, subscription status, renewal date, and remaining content quota. A free-tier user can see that they are on the FREE plan with their watch counter and upgrade options.

**Why this priority**: Users need visibility into their subscription state for informed upgrade decisions. This also surfaces free-tier watch limits, prompting upgrades. Depends on an active subscription existing (US2).

**Independent Test**: Can be tested by logging in as a user with a known subscription state and calling GET /subscriptions/me, then verifying all fields match the expected subscription record.

**Acceptance Scenarios**:

1. **Given** a user with an ACTIVE paid subscription, **When** they call GET /subscriptions/me, **Then** they receive the plan name, status, start date, end date, auto-renew flag, and payment history summary.
2. **Given** a user on the FREE plan (auto-created at registration), **When** they call GET /subscriptions/me, **Then** they receive plan=FREE, status=ACTIVE, and `contentWatchesUsed` / `contentWatchesLimit` values.
3. **Given** a user whose subscription has expired, **When** they call GET /subscriptions/me, **Then** the status shows EXPIRED and content access is reflected accordingly.
4. **Given** a non-authenticated request, **When** GET /subscriptions/me is called, **Then** a 401 is returned.

---

### User Story 5 — Set Profile Avatar (Priority: P2)

A user updates their profile picture either by uploading an image file directly or by providing a URL pointing to an external image. Both methods update the `avatarUrl` displayed across the platform.

**Why this priority**: Avatar personalization improves user identity and engagement. Offering both file upload and URL input covers users who already have a hosted image (e.g., from a social account). Depends on profile foundation (US1).

**Independent Test**: Can be tested via two paths: (1) request a presigned upload URL, upload a valid image, confirm the session, and verify `avatarUrl` updates; (2) submit a valid external URL directly and verify `avatarUrl` is set to that URL without any upload session.

**Acceptance Scenarios**:

1. **Given** an authenticated user, **When** they call PATCH /users/me/avatar with a `file` upload request (valid MIME type: jpeg, png, webp), **Then** a presigned upload URL and session ID are returned; after the client uploads directly to storage and calls the completion endpoint, `avatarUrl` in the user profile is updated.
2. **Given** an authenticated user, **When** they call PATCH /users/me/avatar providing an `avatarUrl` string (a valid HTTPS URL), **Then** the profile's `avatarUrl` is updated immediately to that URL — no file upload session is created.
3. **Given** an authenticated user, **When** they attempt file upload with an unsupported file type (e.g., application/pdf or video/mp4), **Then** a 400 is returned before any presigned URL is generated.
4. **Given** a file exceeding the maximum size (10 MB), **When** the upload session is created, **Then** a 400 is returned with a clear size limit error.
5. **Given** an authenticated user, **When** they provide a `avatarUrl` that is not a valid HTTPS URL (e.g., an `http://` URL or a non-URL string), **Then** a 400 is returned with a validation error.

---

### User Story 6 — Change Password (Priority: P2)

An authenticated user changes their account password from the settings page by providing their current password and a new password that meets strength requirements. All other active sessions are revoked after the change for security.

**Why this priority**: Password self-service is a security hygiene requirement. Without it, users must rely on the password-reset email flow even when they know their current password.

**Independent Test**: Can be tested by logging in, calling PATCH /users/me/password with the current and new passwords, then verifying the old credentials are rejected and the new ones work.

**Acceptance Scenarios**:

1. **Given** an authenticated user, **When** they submit PATCH /users/me/password with the correct current password and a valid new password, **Then** the password is updated and all other active sessions are revoked.
2. **Given** an incorrect current password, **When** submitted, **Then** a 400 is returned — the password is NOT changed.
3. **Given** a new password that does not meet strength requirements (min 8 chars, 1 uppercase, 1 number), **When** submitted, **Then** a 400 is returned with a descriptive validation error.

---

### User Story 7 — Cancel Subscription (Priority: P3)

A subscribed user decides to cancel their subscription. The cancellation disables auto-renewal so the subscription will not renew at the end of the current billing period. The user retains full access until the subscription's end date.

**Why this priority**: Self-service cancellation is a legal and UX requirement (prevents involuntary charges). Access until period end reduces churn friction. Depends on subscription existing (US2).

**Independent Test**: Can be tested by activating a subscription, cancelling it, verifying `autoRenew=false` and `cancelledAt` is set, and confirming the subscription remains ACTIVE until the end date.

**Acceptance Scenarios**:

1. **Given** a user with an ACTIVE subscription, **When** they call PATCH /subscriptions/cancel, **Then** `autoRenew` is set to false, `cancelledAt` is recorded, the status remains ACTIVE, and access continues until `endDate`.
2. **Given** a user with no active subscription, **When** they call PATCH /subscriptions/cancel, **Then** a 404 is returned.
3. **Given** a user who already cancelled, **When** they call PATCH /subscriptions/cancel again, **Then** a 409 Conflict is returned (already cancelled).

---

### User Story 8 — Manage Email Notification Preferences (Priority: P3)

A user controls whether they receive transactional and marketing emails from the platform. When email notifications are disabled, the system suppresses all non-critical emails for that user. Changes take effect immediately on future deliveries.

**Why this priority**: Email notification preferences respect user autonomy and reduce spam complaints. This is a basic quality-of-life feature with low implementation risk.

**Independent Test**: Can be tested by updating preferences to disable email notifications, triggering a subscription event, and verifying no email is dispatched for that user.

**Acceptance Scenarios**:

1. **Given** an authenticated user, **When** they call PATCH /users/me/notifications with `notificationEmail: false`, **Then** subsequent email notifications for that user are suppressed — no emails are sent for subscription events, renewal reminders, or marketing messages.
2. **Given** an authenticated user who disabled email notifications, **When** they re-enable by calling PATCH /users/me/notifications with `notificationEmail: true`, **Then** email delivery resumes for all future events.
3. **Given** any user (regardless of email preference), **When** a critical security event occurs (e.g., password changed), **Then** a security email is still sent — security emails bypass the notification preference flag.

---

### Edge Cases

- What happens when a user's subscription expires between Stripe checkout initiation and the webhook arrival? The Stripe webhook must still activate the subscription regardless of any intermediate state change.
- What happens if the Stripe webhook is delivered more than once for the same `payment_intent.succeeded` event (duplicate delivery)? The system must be idempotent — a second identical webhook must not create a duplicate subscription or payment record. The `providerReference` (Stripe Payment Intent ID) is the idempotency key.
- What happens if a user provides an external avatar URL that later becomes unavailable (broken link)? The system stores and returns the URL as-is — broken external URLs are the user's responsibility; no periodic URL health checks are in scope.
- What happens if a user uploads an avatar file and the storage upload fails after the presigned URL session is created? The session remains PENDING and times out; the `avatarUrl` is not updated.
- What happens when a subscription with `autoRenew=true` fails to renew (Stripe card decline)? The subscription status transitions to PAST_DUE, not immediately EXPIRED. A payment failure email is sent. The user has a grace period (configured, e.g., 3 days) before the subscription expires.
- What happens if a FREE-tier user exhausts their 2-watch quota and then tries to stream? `canWatch` returns false and a 403 with `reason: upgrade_required` is returned. The user must subscribe to SILVER or GOLD to continue watching. (Enforced by Auth Refactor's CapabilityService — not re-implemented here.)
- What happens when a coupon's `validUntil` expires between POST /subscriptions/apply-coupon (validation) and POST /subscriptions/checkout? The coupon validity must be re-verified atomically at checkout — validation and checkout are not a single atomic operation.
- What happens when a user with no password (OAuth-only account) tries to change their password? A clear 400 error is returned indicating the account uses social login and password change is unavailable.
- What happens if the Stripe signature validation fails due to a clock skew on the server? Stripe allows a 300-second tolerance window; the system must use Stripe SDK's built-in signature verification which handles this.

---

## Requirements *(mandatory)*

### Functional Requirements

#### User Profile (Batch 2)

- **FR-001**: System MUST expose a `GET /users/me` endpoint returning the authenticated user's full profile including email, name, bio, avatar URL, language preference, country, timezone, and notification preferences.
- **FR-002**: System MUST expose a `PATCH /users/me` endpoint allowing partial updates to: `firstName`, `lastName`, `displayName`, `bio`, `languageCode`, `countryCode`, `timezone`.
- **FR-003**: System MUST expose a `PATCH /users/me/notifications` endpoint to update the `notificationEmail` flag per user. Email is the only active notification channel; `notificationPush` and `notificationInApp` fields may be stored for future use but are not yet wired to delivery.
- **FR-004**: System MUST expose a `PATCH /users/me/avatar` endpoint supporting two mutually exclusive modes: (a) **file upload** — validates MIME type (jpeg, png, webp) and file size (max 10 MB), returns a presigned upload URL; after client upload and confirmation call, `avatarUrl` is updated; (b) **URL input** — accepts a valid HTTPS URL string and sets `avatarUrl` directly without any upload session.
- **FR-005**: System MUST expose a `PATCH /users/me/password` endpoint that: validates the current password, enforces strength requirements on the new password (minimum 8 characters, at least 1 uppercase letter, at least 1 digit), updates the password hash, and revokes all sessions except the current one.
- **FR-006**: Avatar file upload MUST reject unsupported MIME types and files exceeding 10 MB with a 400 before issuing a presigned URL. Avatar URL input MUST reject non-HTTPS URLs and malformed URL strings with a 400.
- **FR-007**: OAuth-only accounts (no password set) MUST receive a 400 with a clear error when attempting PATCH /users/me/password.
- **FR-008**: All profile endpoints MUST require authentication — unauthenticated requests receive 401.

#### Subscription Plans & Billing (Batch 12)

- **FR-009**: System MUST expose a public `GET /subscriptions/plans` endpoint returning only the purchasable plans — SILVER and GOLD — with: name, description, price, currency, billing cycle, max devices, and video quality tier. The FREE plan is NOT returned here; it is system-assigned to new users at registration (see Auth Refactor spec FR-025).
- **FR-010**: System MUST expose `POST /subscriptions/checkout` for authenticated users: validate the plan is SILVER or GOLD and active, reject if the user already has an ACTIVE paid subscription (409), optionally apply a validated coupon discount, create a pending Stripe payment record, call the Stripe SDK to create a Payment Intent with card-only payment method, and return a Stripe-hosted `paymentUrl` and `paymentReference`. The request MUST include an `autoRenew` boolean; this value is stored on the resulting subscription.
- **FR-011**: System MUST expose `POST /webhooks/stripe` that validates the Stripe webhook signature using the Stripe signing secret, then: on `payment_intent.succeeded` — updates payment status to SUCCESSFUL, creates/activates the `UserSubscription` with the `autoRenew` value captured at checkout, sends a subscription activation email to the user; on `payment_intent.payment_failed` — updates payment status to FAILED, sends a payment failure email to the user. Card is the only supported payment method — no wallets, bank transfers, or BNPL.
- **FR-012**: Payment webhook processing MUST be idempotent — receiving the same webhook event twice must not create duplicate subscriptions or payment records.
- **FR-013**: System MUST expose `GET /subscriptions/me` returning the authenticated user's active subscription: plan name, status, start date, end date, auto-renew flag, `contentWatchesUsed`, `contentWatchesLimit`, and a paginated payment history.
- **FR-014**: System MUST expose `PATCH /subscriptions/cancel` that sets `autoRenew=false` and records `cancelledAt` without immediately changing subscription status or cutting off access.
- **FR-015**: System MUST expose `POST /subscriptions/apply-coupon` for coupon-only validation (no charge) returning the discounted price and discount details if the coupon is valid.
- **FR-016**: Coupon validation MUST enforce four rules atomically: coupon is active, current date is within `validFrom`–`validUntil`, `usesCount < maxUses` (null = unlimited), and the user has not previously redeemed this coupon.
- **FR-017**: On successful coupon redemption (at webhook completion), the system MUST atomically: increment `usesCount`, insert a `CouponRedemption` record, and link it to the new subscription — all within a single transaction.
- **FR-018**: A background job MUST run daily to expire subscriptions where `endDate < now()` AND `status = ACTIVE` — setting status to EXPIRED and publishing a `SUBSCRIPTION_EXPIRED` notification.
- **FR-019**: A background job MUST run daily to find subscriptions expiring within 3 days and publish `SUBSCRIPTION_EXPIRING_SOON` notifications.
- **FR-020**: The Stripe webhook endpoint MUST validate the Stripe-Signature header using the Stripe signing secret via the Stripe SDK's webhook verification utility; requests with invalid or missing signatures MUST be rejected with a 400 before any processing occurs.
- **FR-021**: All subscription-related notifications (activation, payment failure, expiration, expiring-soon reminder) MUST be delivered via email only. In-app and push channels are not in scope for this batch.

### Key Entities

- **UserProfile**: Extends user identity with editable fields: `fullName`, `bio`, `avatarUrl`, `languageCode`, `countryCode`, `timezone`, notification preference flags. One-to-one with User.
- **SubscriptionPlan**: Admin-managed plan catalog defining `name`, `price`, `currency`, `billingCycle`, `maxDevices`, `videoQuality`, `contentLimit`, `trialDays`, `isActive`. Read-only at checkout.
- **UserSubscription**: Links a user to their active plan with `status`, `startDate`, `endDate`, `autoRenew`, `cancelledAt`, `contentWatchesUsed`. One active subscription per user at any time.
- **Payment**: Tracks each Stripe card transaction: `provider` (always "STRIPE"), `providerReference` (Stripe Payment Intent ID, unique), `amount`, `currency`, `status` (PENDING/SUCCESSFUL/FAILED/REFUNDED), `paidAt`. Linked to a UserSubscription.
- **Coupon**: Discount code with `discountType` (PERCENTAGE/FIXED), `discountValue`, `maxUses`, `usesCount`, `validFrom`, `validUntil`, `isActive`. Created by admins.
- **CouponRedemption**: Ensures one redemption per user per coupon; linked to the subscription created at checkout. Has a DB-level UNIQUE constraint on `(couponId, userId)`.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can view and update their profile in a single round-trip — GET /users/me and PATCH /users/me each complete in under 500ms under normal load.
- **SC-002**: Avatar upload flow (session creation → client upload → confirmation) completes end-to-end in under 10 seconds for a 5 MB image on a standard connection.
- **SC-003**: Checkout initiation to `paymentUrl` delivery completes in under 2 seconds — the user is not left waiting before being redirected to the payment provider.
- **SC-004**: Payment webhook processing (subscription activation) completes in under 1 second of webhook receipt — users gain access without noticeable delay after payment confirmation.
- **SC-005**: A duplicate payment webhook for the same `providerReference` does not create a duplicate subscription — idempotency is guaranteed with zero tolerance for duplicate subscriptions.
- **SC-006**: An expired coupon, a limit-reached coupon, and an already-used coupon each return a machine-readable `reason` field allowing the frontend to display a specific, actionable error message.
- **SC-007**: Subscription expiration job correctly transitions all eligible subscriptions to EXPIRED status within 1 hour of their `endDate` passing (job runs daily at 03:00).
- **SC-008**: All profile and subscription endpoints return 401 for unauthenticated requests — zero public access to protected account data.
- **SC-009**: A cancelled subscription remains accessible until its `endDate` — no service interruption occurs upon cancellation.

---

## Assumptions

- **Payment provider**: Stripe is the sole payment provider. Only card payments are supported (no wallets, bank transfers, BNPL, or other methods). Stripe SDK credentials (`STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`) are environment-configurable.
- **Plan catalog**: Only SILVER and GOLD are purchasable subscription plans. FREE is a system-assigned tier automatically created for every new user at registration (defined in Auth Refactor spec FR-025, with a 2-watch lifetime content quota). FREE does not appear in the public plans endpoint.
- **FREE tier content quota**: The 2-watch limit for FREE users is enforced by the Auth Refactor's `CapabilityService` (already implemented). This spec does not redefine that behavior.
- **UserSubscription entity**: The `UserSubscription` entity, `SubscriptionPlan` entity, and related billing fields (`contentWatchesUsed`, `contentLimit`, `maxDevices`) already exist in the database from the Auth Architecture Refactor. This batch adds the `Payment` entity, the Stripe checkout flow, and the webhook handler.
- **One active subscription per user**: Users may have exactly one active paid subscription at a time. Upgrading plans requires cancelling the current subscription first — a dedicated "upgrade" flow is out of scope for this batch.
- **Auto-renew behavior**: When `autoRenew=true`, the renewal payment is initiated automatically by Stripe's subscription billing (or a background job if using one-time Payment Intents). When `autoRenew=false`, the subscription simply expires at `endDate` and the user must re-subscribe. The exact Stripe billing model (Subscription vs. one-time Payment Intent) is an implementation detail resolved during planning.
- **User profile table**: The `user_profiles` table is a new table extending the existing `users` table. Profile fields are nullable and default to empty at registration. The `users` table already holds `firstName`, `lastName`, `displayName`, and `avatarUrl` — `user_profiles` adds `bio`, `languageCode`, `countryCode`, `timezone`, and `notificationEmail`.
- **Avatar URL validation**: When a user provides an external avatar URL, the system stores it as-is without downloading or re-hosting the image. The URL must be HTTPS. No image format or content validation is performed on external URLs.
- **Email notifications only**: All subscription and account notifications (activation, payment failure, expiration, reminders, password change) are delivered exclusively via email for this batch. Push and in-app channels are not wired.
- **Security emails bypass preferences**: Password change and security-critical emails are always sent regardless of the user's `notificationEmail` preference.
- **Trial periods**: The `trialDays` field is stored on plans but free-trial enforcement is out of scope for this batch.
- **Payment refunds**: Refund flows are out of scope. The `REFUNDED` payment status is reserved for future implementation.
- **Multi-currency**: Plans are priced in a single configured currency. No real-time exchange rate conversion is in scope.
- **Partner analytics and admin billing analytics** (Batches 13 and 16) are out of scope.
- **Notification delivery infrastructure**: The email sending service (via Resend/SendGrid, already wired in Batch 1) is the downstream delivery mechanism. Subscription events are sent directly via the email service — not queued through a separate notification system (Batch 15 is the full notification system; this batch sends emails directly for subscription events).
