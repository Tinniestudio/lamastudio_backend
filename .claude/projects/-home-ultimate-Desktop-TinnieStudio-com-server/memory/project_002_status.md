---
name: project-002-user-profile-billing-status
description: Implementation status of feature 002 (User Profile + Subscription Billing)
metadata:
  type: project
---

Feature 002 (User Profile + Settings & Subscription Billing) is fully implemented and compiling.

**Why:** Both Batch 2 and Batch 12 from BATCH-PLAN.md are now implemented on branch `001-auth-architecture-refactor` (branch was not switched).

**How to apply:** On next session, this feature is ready for final integration testing and PR.

## Status: Implementation Complete (93/95 tasks done)

### What was built
- `UserProfile` entity + V9 migration (`user_profiles` table)
- `Payment` entity + V10 migration (`payments` table)
- V11 migration (`cancelled_at` on `user_subscriptions`)
- Full user profile CRUD: GET/PATCH `/users/me`, avatar (upload + URL), password change, notification preferences
- Stripe checkout integration (Checkout Sessions → hosted payment URL)
- Subscription lifecycle: list plans, checkout, webhook activation, cancel, status
- Coupon validation wired into checkout + redemption on activation
- Background jobs: `SubscriptionExpirationJob` + `SubscriptionExpiryReminderJob` (daily cron, Redis distributed lock)
- `StorageService` interface + `NoOpStorageService` stub (real S3 impl deferred to Batch 6)
- `@EnableScheduling` added to main application class
- `CacheService.setIfAbsent()` added for distributed job locking
- `SessionService.revokeAllExcept()` added for password-change session cleanup
- Email methods added to `EmailService` for subscription events

### Pre-existing bugs fixed
- `OAuth2Service` was missing `SessionService` injection (auth refactor gap) — fixed; now creates sessions + FREE subscription on first OAuth login
- `AuthServiceTest` missing `AuthProfileService` mock — fixed
- `AuthControllerTest` mocking `AuthResponse` instead of `AuthProfileResponse` — fixed

### Remaining (T093, T095)
- T093: Full end-to-end checkout integration test (needs live Stripe + Testcontainers Redis)
- T095: `quickstart.md` validation on local environment

### Key files
- `UserProfileController`: `/users/me/**`
- `SubscriptionController`: `/subscriptions/**`
- `StripeWebhookController`: `/webhooks/stripe`
- `SubscriptionServiceImpl`: checkout, activate, cancel, coupon, status
- `StripeServiceImpl`: only class importing `com.stripe.*`
- Migrations: `V9`, `V10`, `V11` (after existing V8)
