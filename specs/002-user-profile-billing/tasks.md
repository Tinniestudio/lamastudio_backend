# Tasks: User Profile + Settings & Subscription Billing

**Input**: Design documents from `specs/002-user-profile-billing/`

**Branch**: `002-user-profile-billing`

**Constitution mandate**: TDD is NON-NEGOTIABLE. Write a failing test first, confirm it fails, implement minimal code, confirm it passes, then commit. All test tasks appear before their implementation counterparts within each story.

**Organization**: Tasks are grouped by user story for independent implementation and testing. Phases 1–2 are foundational and block all user stories.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no blocking dependencies)
- **[Story]**: Maps task to user story (US1–US8)
- Every task includes an exact file path

---

## Phase 1: Setup (Structural Prerequisites)

**Purpose**: Add Stripe SDK dependency, create config skeleton, and create new package directories before any entity or service code can be written.

- [x] T001 Add `stripe-java` (v26.x) dependency to `pom.xml`
- [x] T002 [P] Add `stripe.secretKey` and `stripe.webhookSecret` placeholder config to `src/main/resources/application.yml` using `${STRIPE_SECRET_KEY:}` and `${STRIPE_WEBHOOK_SECRET:}` pattern
- [x] T003 [P] Create `src/main/java/com/lamastudio/backend/shared/config/StripeProperties.java`: `@ConfigurationProperties("stripe")` with `secretKey` and `webhookSecret` fields; register as `@Configuration` bean; wire `Stripe.apiKey = stripeProperties.getSecretKey()` in `@PostConstruct`
- [x] T004 [P] Create package directories: `src/main/java/com/lamastudio/backend/modules/billing/controller/`, `src/main/java/com/lamastudio/backend/modules/user/controller/`, `src/main/java/com/lamastudio/backend/modules/user/dto/`, `src/main/java/com/lamastudio/backend/modules/user/service/`, `src/main/java/com/lamastudio/backend/modules/user/repository/`, `src/main/java/com/lamastudio/backend/shared/jobs/`
- [x] T005 [P] Create integration test directories: `src/test/java/com/lamastudio/backend/user/`, `src/test/java/com/lamastudio/backend/billing/controller/`

**Checkpoint**: Project compiles with `mvn compile`. Stripe SDK on classpath. Config skeleton in place.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: All new DB tables must exist via Flyway and all entities + repositories must compile before any user story can be implemented.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### 2A — Flyway Migrations (run in order)

- [x] T006 Write `src/main/resources/db/migration/V9__add_user_profiles.sql`: `CREATE TABLE user_profiles (user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, bio TEXT, language_code VARCHAR(10) DEFAULT 'en', country_code VARCHAR(10), timezone VARCHAR(100), notification_email BOOLEAN NOT NULL DEFAULT TRUE, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()); CREATE TRIGGER trg_user_profiles_updated_at BEFORE UPDATE ON user_profiles FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();`
- [x] T007 Write `src/main/resources/db/migration/V10__add_payments.sql`: `CREATE TABLE payments (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id), subscription_id UUID REFERENCES user_subscriptions(id), plan_id UUID NOT NULL REFERENCES subscription_plans(id), provider VARCHAR(20) NOT NULL DEFAULT 'STRIPE', provider_reference VARCHAR(255) NOT NULL UNIQUE, amount DECIMAL(10,2) NOT NULL, currency VARCHAR(3) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', auto_renew BOOLEAN NOT NULL DEFAULT TRUE, coupon_id UUID REFERENCES coupons(id), discount_amount DECIMAL(10,2), paid_at TIMESTAMPTZ, failure_reason TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()); CREATE INDEX idx_payments_user_id ON payments(user_id); CREATE INDEX idx_payments_subscription_id ON payments(subscription_id);`
- [x] T008 Write `src/main/resources/db/migration/V11__add_subscription_cancelled_at.sql`: `ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;`

### 2B — JPA Entities (parallelizable after T006–T008)

- [x] T009 [P] Create `src/main/java/com/lamastudio/backend/shared/entity/UserProfile.java`: `@Entity @Table(name="user_profiles")`; `@Id private UUID userId`; fields: `bio`, `languageCode`, `countryCode`, `timezone`, `notificationEmail` with `@Column` annotations matching migration; `@UpdateTimestamp updatedAt`; `@MapsId @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="user_id") private User user`
- [x] T010 [P] Create `src/main/java/com/lamastudio/backend/shared/entity/Payment.java`: `@Entity @Table(name="payments")`; `@Id @GeneratedValue(strategy=GenerationType.UUID) UUID id`; all fields from V10 migration; `@Column(name="provider_reference", nullable=false, unique=true)`; `@Enumerated(EnumType.STRING) PaymentStatus status` enum with `PENDING, SUCCESSFUL, FAILED, REFUNDED` values defined as inner enum or in `DomainEnums`

### 2C — Repositories (parallelizable after entities)

- [x] T011 [P] Create `src/main/java/com/lamastudio/backend/modules/user/repository/UserProfileRepository.java`: `JpaRepository<UserProfile, UUID>` with `findByUserId(UUID userId)` returning `Optional<UserProfile>`
- [x] T012 [P] Create `src/main/java/com/lamastudio/backend/modules/billing/repository/PaymentRepository.java`: `JpaRepository<Payment, UUID>` with: `findByProviderReference(String ref) → Optional<Payment>`, `findByUserIdOrderByCreatedAtDesc(UUID userId) → List<Payment>`, `existsByProviderReferenceAndStatus(String ref, PaymentStatus status) → boolean`

**Checkpoint**: `mvn compile` succeeds. `mvn test` passes repository integration tests against Testcontainers PostgreSQL. V9–V11 migrations apply cleanly on a fresh DB.

---

## Phase 3: User Story 1 — View and Edit Personal Profile (Priority: P1) 🎯 MVP Start

**Goal**: Authenticated users can view their full profile and partially update name, bio, language, and other preference fields via GET and PATCH /users/me.

**Independent Test**: Register a user → call GET /users/me → verify all fields present → call PATCH /users/me with new bio and countryCode → verify GET /users/me returns updated values only for changed fields.

### Tests for US1

- [x] T013 [P] [US1] Write unit test `src/test/java/com/lamastudio/backend/user/service/UserProfileServiceTest.java`: (a) `getProfile()` — user with existing UserProfile → returns merged UserProfileResponse with all fields; (b) `getProfile()` — user with no UserProfile row → returns UserProfileResponse with null optional fields (profile auto-creates on first access); (c) `updateProfile()` — only provided fields change, others unchanged; (d) `updateProfile()` — invalid timezone string → throws ValidationException
- [x] T014 [P] [US1] Write integration test `src/test/java/com/lamastudio/backend/user/UserProfileIntegrationTest.java`: GET /users/me returns 200 with correct shape; PATCH /users/me partial update; unauthenticated GET returns 401

### Implementation for US1

- [x] T015 [P] [US1] Create `src/main/java/com/lamastudio/backend/modules/user/dto/UserProfileResponse.java`: all fields from `/auth/me` identity section plus `bio`, `languageCode`, `countryCode`, `timezone`, `notificationEmail`; static factory `UserProfileResponse.from(User user, UserProfile profile)`
- [x] T016 [P] [US1] Create `src/main/java/com/lamastudio/backend/modules/user/dto/UpdateProfileRequest.java`: all fields optional (`firstName`, `lastName`, `displayName`, `bio`, `languageCode`, `countryCode`, `timezone`, `phoneNumber`, `dateOfBirth`) with Bean Validation annotations (`@Size`, `@Pattern` for countryCode)
- [x] T017 [US1] Create `src/main/java/com/lamastudio/backend/modules/user/service/UserProfileService.java` interface with: `UserProfileResponse getProfile(UUID userId)`, `UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request)`
- [x] T018 [US1] Implement `src/main/java/com/lamastudio/backend/modules/user/service/UserProfileServiceImpl.java`: `getProfile()` — load User from `UserRepository`; load or create-if-absent `UserProfile` from `UserProfileRepository`; return `UserProfileResponse.from(user, profile)`; `updateProfile()` — patch non-null fields on User and UserProfile; validate timezone/countryCode; save both entities; return updated response
- [x] T019 [US1] Create `src/main/java/com/lamastudio/backend/modules/user/controller/UserProfileController.java`: `@RestController @RequestMapping("/users")` with `GET /users/me` and `PATCH /users/me`; use `@AuthenticationPrincipal` to extract userId; wire `UserProfileService`; apply standard `{ success, data }` response envelope
- [x] T020 [US1] Verify `SecurityConfig.java` (user chain) permits authenticated access to `/users/**`; update `UserSecurityFilterChain` matcher if needed in `src/main/java/com/lamastudio/backend/shared/config/SecurityConfig.java`
- [x] T021 [US1] Run all US1 tests and confirm they pass

**Checkpoint**: GET /users/me and PATCH /users/me work end-to-end. Unauthenticated requests return 401.

---

## Phase 4: User Story 2 — Subscribe to a Plan (Priority: P1)

**Goal**: Authenticated users can list SILVER/GOLD plans and initiate Stripe card checkout. Stripe webhook activates the subscription on successful payment.

**Independent Test**: GET /subscriptions/plans returns only SILVER and GOLD → POST /subscriptions/checkout returns Stripe paymentUrl → simulate `payment_intent.succeeded` webhook → GET /subscriptions/me returns ACTIVE subscription.

### Tests for US2

- [x] T022 [P] [US2] Write unit test `src/test/java/com/lamastudio/backend/billing/service/SubscriptionServiceTest.java`: (a) `listPlans()` — returns only SILVER and GOLD, not FREE; (b) `initiateCheckout()` — creates PENDING payment with correct amount and providerReference; (c) `initiateCheckout()` — user with existing ACTIVE subscription → throws ConflictException; (d) `activateSubscription()` — creates UserSubscription ACTIVE, sets payment SUCCESSFUL; (e) `activateSubscription()` — called twice with same providerReference → idempotent, no duplicate subscription
- [x] T023 [P] [US2] Write unit test `src/test/java/com/lamastudio/backend/billing/service/StripeServiceTest.java`: (a) `createPaymentIntent()` — calls Stripe SDK with correct amount, currency, metadata; (b) `constructWebhookEvent()` — valid signature → returns event; (c) invalid signature → throws `SignatureVerificationException`
- [x] T024 [P] [US2] Write integration test `src/test/java/com/lamastudio/backend/billing/controller/StripeWebhookIntegrationTest.java`: POST /webhooks/stripe with invalid signature → 400; POST /webhooks/stripe with `payment_intent.succeeded` → 200, subscription ACTIVE in DB; POST /webhooks/stripe with same event twice → 200, no duplicate subscription

### Implementation for US2

- [x] T025 [P] [US2] Create `src/main/java/com/lamastudio/backend/modules/billing/service/StripeService.java` interface: `CreatePaymentIntentResult createPaymentIntent(long amountCents, String currency, Map<String, String> metadata)`, `com.stripe.model.Event constructWebhookEvent(String payload, String sigHeader)`
- [x] T026 [US2] Implement `src/main/java/com/lamastudio/backend/modules/billing/service/StripeServiceImpl.java`: `createPaymentIntent()` — call `PaymentIntent.create()` with `payment_method_types: ["card"]`; return Payment Intent ID + hosted checkout URL (`payment_intent.next_action.redirect_to_url.url` or Stripe Payment Links); `constructWebhookEvent()` — call `Webhook.constructEvent(payload, sigHeader, stripeProperties.getWebhookSecret())`; inject `StripeProperties`
- [x] T027 [P] [US2] Create `src/main/java/com/lamastudio/backend/modules/billing/dto/PlanResponse.java`: `planId`, `name`, `description`, `price`, `currency`, `billingCycle`, `maxDevices`, `videoQuality`, `isActive`
- [x] T028 [P] [US2] Create `src/main/java/com/lamastudio/backend/modules/billing/dto/CheckoutRequest.java`: `planId` (UUID, @NotNull), `autoRenew` (boolean, @NotNull), `couponCode` (String, optional); and `CheckoutResponse.java`: `paymentId`, `paymentReference`, `paymentUrl`, `amount`, `currency`, `planName`, `autoRenew`, `expiresAt`
- [x] T029 [US2] Create `src/main/java/com/lamastudio/backend/modules/billing/service/SubscriptionService.java` interface: `List<PlanResponse> listPlans()`, `CheckoutResponse initiateCheckout(UUID userId, CheckoutRequest request)`, `void activateSubscription(String paymentIntentId)`, `void failPayment(String paymentIntentId, String reason)`, `SubscriptionStatusResponse getSubscriptionStatus(UUID userId)`, `void cancelSubscription(UUID userId)`
- [x] T030 [US2] Implement `SubscriptionServiceImpl.listPlans()` in `src/main/java/com/lamastudio/backend/modules/billing/service/SubscriptionServiceImpl.java`: query `subscriptionPlanRepository.findByNameInAndIsActiveTrue(List.of("SILVER","GOLD"))` → map to `PlanResponse`
- [x] T031 [US2] Implement `SubscriptionServiceImpl.initiateCheckout(userId, request)`: (1) check no ACTIVE `UserSubscription` with paid plan → throw `ConflictException` if exists; (2) load plan; (3) compute finalAmount (no coupon yet — see US3); (4) call `StripeService.createPaymentIntent(amountCents, currency, metadata)`; (5) create `Payment` entity (PENDING, providerReference=intentId, autoRenew from request); (6) save Payment; (7) return `CheckoutResponse`
- [x] T032 [US2] Implement `SubscriptionServiceImpl.activateSubscription(paymentIntentId)`: (1) idempotency check: `paymentRepository.existsByProviderReferenceAndStatus(id, SUCCESSFUL)` → return if true; (2) load Payment; (3) update status=SUCCESSFUL, paidAt=now(); (4) create `UserSubscription` (ACTIVE, autoRenew from payment, startDate=now, endDate=now+billingCycle); (5) set `payment.subscriptionId`; (6) save both in `@Transactional`; (7) send activation email via `EmailService`
- [x] T033 [US2] Implement `SubscriptionServiceImpl.failPayment(paymentIntentId, reason)`: load Payment → status=FAILED, failureReason=reason → save → send failure email via `EmailService`
- [x] T034 [US2] Create `src/main/java/com/lamastudio/backend/modules/billing/controller/StripeWebhookController.java`: `@RestController @RequestMapping("/webhooks")` with `POST /webhooks/stripe`; reads raw request body as `String`; reads `Stripe-Signature` header; calls `StripeService.constructWebhookEvent()` (400 on `SignatureVerificationException`); routes `payment_intent.succeeded` → `subscriptionService.activateSubscription()`; routes `payment_intent.payment_failed` → `subscriptionService.failPayment()`; always returns `{ "received": true }`
- [x] T035 [US2] Create `src/main/java/com/lamastudio/backend/modules/billing/controller/SubscriptionController.java`: `GET /subscriptions/plans` (public) and `POST /subscriptions/checkout` (authenticated); ensure `/webhooks/**` is excluded from JWT filter via `SecurityConfig`
- [x] T036 [US2] Update `SecurityConfig.java` to permit `/subscriptions/plans` publicly and `/webhooks/stripe` publicly (no JWT required — Stripe validates via signature); block all other `/subscriptions/**` paths behind authentication in `src/main/java/com/lamastudio/backend/shared/config/SecurityConfig.java`
- [x] T037 [US2] Run all US2 tests and confirm they pass

**Checkpoint**: Full checkout → webhook → subscription activation flow works. Idempotent webhook confirmed. Invalid signature blocked.

---

## Phase 5: User Story 3 — Apply a Coupon at Checkout (Priority: P2)

**Goal**: Users can validate a coupon code before checkout and include it in checkout to reduce the plan price. Coupon redemption is recorded atomically on payment success.

**Independent Test**: Create a coupon in DB → POST /subscriptions/apply-coupon → verify discounted price → POST /subscriptions/checkout with couponCode → simulate webhook → verify `coupon_redemptions` row created and `usesCount` incremented.

### Tests for US3

- [x] T038 [P] [US3] Add to `SubscriptionServiceTest.java`: (a) `validateCoupon()` valid coupon → returns discounted price; (b) expired coupon → returns 400 with reason "expired"; (c) coupon already used by user → reason "already_used"; (d) max uses reached → reason "limit_reached"; (e) code not found → reason "not_found"
- [x] T039 [P] [US3] Write integration test: checkout with coupon → `payment_intent.succeeded` webhook → verify `coupon_redemptions` row inserted exactly once, `coupons.uses_count` incremented

### Implementation for US3

- [x] T040 [P] [US3] Create `src/main/java/com/lamastudio/backend/modules/billing/dto/CouponValidationRequest.java`: `code` (String, @NotBlank), `planId` (UUID, @NotNull); and extend `CheckoutResponse` or create `CouponValidationResponse.java` with `valid`, `discountType`, `discountValue`, `originalPrice`, `finalPrice`, `currency`
- [x] T041 [US3] Implement `SubscriptionServiceImpl.validateCoupon(code, userId, planId)`: delegate to existing `CouponService.validateCoupon(code, userId)` → on valid, compute discounted price from plan; return `CouponValidationResponse`; map `CouponValidationResult.reason` to 400 with specific machine-readable reason field
- [x] T042 [US3] Update `SubscriptionServiceImpl.initiateCheckout()` to apply coupon when `request.couponCode` is non-null: call `validateCoupon()` → use `finalPrice` for Stripe amount; store `couponId` + `discountAmount` on `Payment` entity; re-validate coupon atomically at checkout (not just at apply-coupon time)
- [x] T043 [US3] Update `SubscriptionServiceImpl.activateSubscription()` to call `CouponService.redeemCoupon(couponId, userId, subscriptionId)` when `payment.couponId != null` — inside the same `@Transactional` block, after subscription is created
- [x] T044 [US3] Add `POST /subscriptions/apply-coupon` endpoint to `SubscriptionController.java`; route to `SubscriptionServiceImpl.validateCoupon()`
- [x] T045 [US3] Run all US3 tests and confirm they pass

**Checkpoint**: Coupon validates correctly for all 5 error cases. Coupon applied at checkout reduces Stripe amount. Redemption recorded atomically with subscription activation.

---

## Phase 6: User Story 4 — View Current Subscription Status (Priority: P2)

**Goal**: Users can view their full subscription state including plan details, quota, and payment history.

**Independent Test**: Activate a SILVER subscription via webhook → GET /subscriptions/me → verify plan=SILVER, status=ACTIVE, payment entry present; call for FREE-tier user → verify plan=FREE, contentWatchesUsed/contentWatchesLimit correct.

### Tests for US4

- [x] T046 [P] [US4] Add to `SubscriptionServiceTest.java`: (a) `getSubscriptionStatus()` with active paid subscription → returns all fields including payment history; (b) FREE-tier user → returns plan=FREE, contentWatchesUsed, contentWatchesLimit; (c) expired subscription → status=EXPIRED in response
- [x] T047 [P] [US4] Write integration test `src/test/java/com/lamastudio/backend/billing/controller/SubscriptionStatusIntegrationTest.java`: GET /subscriptions/me authenticated → 200; GET /subscriptions/me unauthenticated → 401

### Implementation for US4

- [x] T048 [P] [US4] Create `src/main/java/com/lamastudio/backend/modules/billing/dto/SubscriptionStatusResponse.java`: `subscriptionId`, `plan`, `status`, `startDate`, `endDate`, `autoRenew`, `cancelledAt`, `contentWatchesUsed`, `contentWatchesLimit`, `payments` (list of `PaymentSummary` inner class with `paymentId`, `amount`, `currency`, `status`, `paidAt`)
- [x] T049 [US4] Implement `SubscriptionServiceImpl.getSubscriptionStatus(userId)`: load latest `UserSubscription` (fallback to FREE auto-created at registration); join `SubscriptionPlan` for plan name/contentLimit; load payment history from `PaymentRepository.findByUserIdOrderByCreatedAtDesc()`; map to `SubscriptionStatusResponse`; no N+1 — use fetch join or separate queries
- [x] T050 [US4] Add `GET /subscriptions/me` endpoint to `SubscriptionController.java`
- [x] T051 [US4] Run all US4 tests and confirm they pass

**Checkpoint**: Full subscription status visible including FREE plan defaults. Payment history included.

---

## Phase 7: User Story 5 — Set Profile Avatar (Priority: P2)

**Goal**: Users can update their profile picture either by uploading a file (presigned URL flow) or providing an external HTTPS URL.

**Independent Test**: (Upload path) PATCH /users/me/avatar with mode=UPLOAD → receive uploadUrl → PUT file to URL → POST /users/me/avatar/confirm → GET /users/me avatarUrl updated; (URL path) PATCH /users/me/avatar with mode=URL and HTTPS URL → GET /users/me avatarUrl = provided URL.

### Tests for US5

- [x] T052 [P] [US5] Add to `UserProfileServiceTest.java`: (a) `initiateAvatarUpload()` — valid JPEG 1MB → returns presigned URL and storageKey; (b) unsupported MIME type (application/pdf) → throws ValidationException; (c) file exceeds 10MB → throws ValidationException; (d) `confirmAvatarUpload()` — object exists in storage → updates avatarUrl; (e) `confirmAvatarUpload()` — object not found → throws ValidationException with "Upload not found"; (f) `setAvatarByUrl()` — valid HTTPS URL → updates avatarUrl; (g) HTTP (not HTTPS) URL → throws ValidationException; (h) non-URL string → throws ValidationException
- [x] T053 [P] [US5] Write integration test: PATCH /users/me/avatar with mode=URL → GET /users/me → avatarUrl matches; PATCH /users/me/avatar with invalid mode → 400

### Implementation for US5

- [x] T054 [P] [US5] Create `src/main/java/com/lamastudio/backend/modules/user/dto/AvatarUpdateRequest.java`: `mode` (enum UPLOAD/URL, @NotNull); `mimeType` (String — required when mode=UPLOAD); `fileSizeBytes` (Long — required when mode=UPLOAD); `avatarUrl` (String — required when mode=URL); add custom `@AssertTrue` cross-field validation
- [x] T055 [P] [US5] Create `src/main/java/com/lamastudio/backend/modules/user/dto/AvatarConfirmRequest.java`: `storageKey` (String, @NotBlank)
- [x] T056 [US5] Implement `UserProfileServiceImpl.setAvatarByUrl(userId, avatarUrl)`: validate URL is HTTPS and well-formed via `java.net.URI`; validate length ≤ 2048 chars; update `User.avatarUrl` → save via `UserRepository`
- [x] T057 [US5] Implement `UserProfileServiceImpl.initiateAvatarUpload(userId, mimeType, fileSizeBytes)`: validate mimeType in `{image/jpeg, image/png, image/webp}`; validate fileSizeBytes ≤ 10MB (10_485_760); generate storageKey `avatars/{userId}/{UUID}.{ext}`; call `StorageService.generateUploadUrl(storageKey, mimeType, fileSizeBytes, Duration.ofMinutes(15))`; return `{ uploadUrl, storageKey, expiresAt }`
- [x] T058 [US5] Implement `UserProfileServiceImpl.confirmAvatarUpload(userId, storageKey)`: call `StorageService.objectExists(storageKey)`; if false → throw `ValidationException("Upload not found in storage")`; update `User.avatarUrl = cdnBaseUrl + "/" + storageKey`; save via `UserRepository`; bind `cdnBaseUrl` from `AppProperties` (existing config bean)
- [x] T059 [US5] Add `PATCH /users/me/avatar` and `POST /users/me/avatar/confirm` endpoints to `UserProfileController.java`; dispatch to appropriate service method based on `request.mode`
- [x] T060 [US5] Run all US5 tests and confirm they pass

**Checkpoint**: Both avatar modes work. File validation enforced. External URL stored as-is (HTTPS only).

---

## Phase 8: User Story 6 — Change Password (Priority: P2)

**Goal**: Authenticated users with LOCAL provider can change their password. All other sessions are revoked after a successful change.

**Independent Test**: Login → call PATCH /users/me/password with correct currentPassword and valid newPassword → verify old credentials rejected → verify other sessions return 401 on refresh → current session remains active.

### Tests for US6

- [x] T061 [P] [US6] Add to `UserProfileServiceTest.java`: (a) valid current + strong new password → password updated, `revokeAllExcept` called with currentSessionId; (b) wrong currentPassword → throws ValidationException, password unchanged; (c) weak newPassword (no digit) → throws ValidationException; (d) OAuth account (provider=GOOGLE) → throws ValidationException with social login message
- [x] T062 [P] [US6] Write integration test `src/test/java/com/lamastudio/backend/user/PasswordChangeIntegrationTest.java`: full flow — login → change password → old credentials rejected → current session refresh still works

### Implementation for US6

- [x] T063 [P] [US6] Create `src/main/java/com/lamastudio/backend/modules/user/dto/UpdatePasswordRequest.java`: `currentPassword` (@NotBlank), `newPassword` (@NotBlank, @Size(min=8), custom `@StrongPassword` annotation or `@Pattern` enforcing uppercase + digit)
- [x] T064 [US6] Add `revokeAllExcept(UUID userId, UUID currentSessionId)` method to `src/main/java/com/lamastudio/backend/modules/auth/user/service/SessionService.java` interface: revokes all active `UserSession` records for `userId` WHERE `id != currentSessionId`; deletes corresponding Redis keys via `CacheService`
- [x] T065 [US6] Implement `revokeAllExcept()` in `src/main/java/com/lamastudio/backend/modules/auth/user/service/SessionServiceImpl.java`: `userSessionRepository.findByUserIdAndRevokedFalse(userId)` → filter out `currentSessionId` → mark each revoked → delete Redis keys in batch
- [x] T066 [US6] Implement `UserProfileServiceImpl.changePassword(userId, request, currentSessionId)`: check `User.provider == LOCAL` (else throw); verify `passwordEncoder.matches(request.currentPassword, user.passwordHash)` (else throw); validate new password strength; `user.passwordHash = passwordEncoder.encode(newPassword)` → save; call `sessionService.revokeAllExcept(userId, currentSessionId)`; send security email via `EmailService`
- [x] T067 [US6] Add `PATCH /users/me/password` endpoint to `UserProfileController.java`; extract `currentSessionId` from `SecurityContextHolder` (already set by `JwtAuthenticationFilter`); route to `changePassword()`
- [x] T068 [US6] Run all US6 tests and confirm they pass

**Checkpoint**: Password change revokes all other sessions. OAuth accounts receive clear error. Current session survives.

---

## Phase 9: User Story 7 — Cancel Subscription (Priority: P3)

**Goal**: Users with an active paid subscription can cancel it. Access continues until `endDate`. Cancelling again returns 409.

**Independent Test**: Activate subscription → PATCH /subscriptions/cancel → GET /subscriptions/me shows autoRenew=false, cancelledAt set, status=ACTIVE → PATCH /subscriptions/cancel again → 409.

### Tests for US7

- [x] T069 [P] [US7] Add to `SubscriptionServiceTest.java`: (a) active paid subscription → sets autoRenew=false, cancelledAt=now, status remains ACTIVE; (b) no active subscription → throws NotFoundException; (c) already cancelled subscription → throws ConflictException
- [x] T070 [P] [US7] Write integration test: cancel flow with active subscription → verify DB state; double-cancel → 409

### Implementation for US7

- [x] T071 [US7] Implement `SubscriptionServiceImpl.cancelSubscription(userId)`: load `UserSubscription` with status=ACTIVE and plan != FREE; if none → throw `NotFoundException`; if `cancelledAt != null` → throw `ConflictException`; set `autoRenew=false`, `cancelledAt=Instant.now()`; save; send cancellation email via `EmailService`
- [x] T072 [US7] Add `PATCH /subscriptions/cancel` endpoint to `SubscriptionController.java`
- [x] T073 [US7] Run all US7 tests and confirm they pass

**Checkpoint**: Cancellation sets correct fields. Access period intact. Double-cancel blocked.

---

## Phase 10: User Story 8 — Email Notification Preferences (Priority: P3)

**Goal**: Users can opt out of email notifications. Security emails are always sent regardless of preference.

**Independent Test**: PATCH /users/me/notifications with notificationEmail=false → verify user_profiles.notification_email=false → trigger a subscription email → verify EmailService.send() is NOT called for that user.

### Tests for US8

- [x] T074 [P] [US8] Add to `UserProfileServiceTest.java`: (a) `updateNotifications()` notificationEmail=false → saves false to user_profiles; (b) notificationEmail=true → saves true; (c) EmailService checks notificationEmail before sending non-security emails → suppresses email when false
- [x] T075 [P] [US8] Write integration test: PATCH /users/me/notifications → verify DB field; GET /users/me → reflects preference

### Implementation for US8

- [x] T076 [P] [US8] Create `src/main/java/com/lamastudio/backend/modules/user/dto/UpdateNotificationRequest.java`: `notificationEmail` (Boolean, @NotNull)
- [x] T077 [US8] Implement `UserProfileServiceImpl.updateNotifications(userId, request)`: load or create `UserProfile`; set `notificationEmail = request.notificationEmail`; save via `UserProfileRepository`; return `{ notificationEmail: <value> }`
- [x] T078 [US8] Update `SubscriptionServiceImpl.activateSubscription()`, `failPayment()`, and cancellation to check `userProfile.notificationEmail` before calling `EmailService.send()` — skip email if false; do NOT skip for security/critical emails (password change email bypasses this check)
- [x] T079 [US8] Add `PATCH /users/me/notifications` endpoint to `UserProfileController.java`
- [x] T080 [US8] Run all US8 tests and confirm they pass

**Checkpoint**: Notification preference persisted and respected by email-sending code paths. Security emails bypass preference.

---

## Phase 11: Background Jobs

**Purpose**: Subscription expiration and renewal reminder emails run on daily schedule with distributed locking.

**Independent Test**: Insert a UserSubscription with endDate = yesterday → run expiration job → verify status=EXPIRED, email sent. Insert subscription with endDate = 2 days from now and autoRenew=false → run reminder job → verify email sent.

### Tests for Background Jobs

- [x] T081 [P] Write unit test `src/test/java/com/lamastudio/backend/billing/jobs/SubscriptionExpirationJobTest.java`: (a) subscription with endDate in past → status set to EXPIRED, email sent; (b) subscription with future endDate → not expired; (c) lock already held → job skips without processing
- [x] T082 [P] Write unit test `src/test/java/com/lamastudio/backend/billing/jobs/SubscriptionExpiryReminderJobTest.java`: (a) autoRenew=false subscription expiring in 2 days → email sent; (b) autoRenew=true subscription → no reminder email; (c) already expired → no reminder email

### Implementation for Background Jobs

- [x] T083 [P] Add `findByStatusAndEndDateBefore(SubscriptionStatus status, Instant date)` and `findByStatusAndAutoRenewFalseAndEndDateBetween(SubscriptionStatus, Instant from, Instant to)` to `src/main/java/com/lamastudio/backend/modules/billing/repository/UserSubscriptionRepository.java`
- [x] T084 [P] Create `src/main/java/com/lamastudio/backend/shared/jobs/SubscriptionExpirationJob.java`: `@Component`, `@Scheduled(cron = "0 0 3 * * *")`; acquire Redis lock `tinnie:lock:subscription-expiration` (TTL 2h) via `CacheService.set()` with `setIfAbsent`; if lock not acquired → log and return; query expired subscriptions → batch update status=EXPIRED → for each: send SUBSCRIPTION_EXPIRED email via `EmailService` (respecting `notificationEmail` preference); release lock; log duration
- [x] T085 Implement `src/main/java/com/lamastudio/backend/shared/jobs/SubscriptionExpiryReminderJob.java`: `@Scheduled(cron = "0 0 8 * * *")`; acquire lock `tinnie:lock:subscription-expiry-reminder`; query subscriptions with `autoRenew=false AND status=ACTIVE AND endDate BETWEEN now() AND now()+3days`; for each: send SUBSCRIPTION_EXPIRING_SOON email; release lock
- [x] T086 Verify `@EnableScheduling` is present on a `@Configuration` class (add to `src/main/java/com/lamastudio/backend/shared/config/SchedulingConfig.java` if not already present)
- [x] T087 Run all background job tests and confirm they pass

**Checkpoint**: Both jobs run without error on startup. Expiration correctly transitions subscriptions. Reminder sent only for non-auto-renew. Distributed lock prevents double execution.

---

## Final Phase: Polish & Cross-Cutting Concerns

- [x] T088 [P] Add SpringDoc OpenAPI annotations to all new endpoints in `UserProfileController.java` (operation summaries, request/response schemas, 401/400 error responses)
- [x] T089 [P] Add SpringDoc OpenAPI annotations to `SubscriptionController.java` and `StripeWebhookController.java`
- [x] T090 [P] Verify all new service classes for `@Value` usage — replace any with `@ConfigurationProperties`-bound values (constitution §VI drift check)
- [x] T091 [P] Verify no direct Stripe SDK imports outside `StripeServiceImpl.java` — scan all new files for `import com.stripe.*` (constitution §VI drift check)
- [x] T092 Run full Flyway migration sequence V1→V11 on a fresh PostgreSQL database (Testcontainers) to confirm all migrations apply cleanly in order
- [ ] T093 Run full integration test: register user → update profile → checkout SILVER with coupon → simulate webhook → verify subscription active → cancel → verify state
- [x] T094 Run `mvn test` — confirm all 100%+ tests pass
- [ ] T095 [P] Validate `quickstart.md` step-by-step using a local dev environment (Stripe CLI + `stripe listen`)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — BLOCKS all user stories
  - 2A (migrations) must complete before 2B (entities)
  - 2B must complete before 2C (repositories)
- **US1 Profile CRUD (Phase 3)**: Depends on Phase 2 complete — P1, MVP start
- **US2 Checkout (Phase 4)**: Depends on Phase 2 complete — P1, parallel-eligible with US1
- **US3 Coupon (Phase 5)**: Depends on US2 complete (wires into checkout flow)
- **US4 Status (Phase 6)**: Depends on US2 complete (needs subscription + payment data)
- **US5 Avatar (Phase 7)**: Depends on US1 complete (extends UserProfileServiceImpl)
- **US6 Password (Phase 8)**: Depends on US1 complete (same service class); also adds SessionService method
- **US7 Cancel (Phase 9)**: Depends on US2 complete (needs active subscription)
- **US8 Notifications (Phase 10)**: Depends on US1 complete (user_profiles row)
- **Background Jobs (Phase 11)**: Depends on US2 complete (subscription data)
- **Polish (Final)**: Depends on all stories complete

### User Story Dependencies

- **US1 (P1)**: After Phase 2 — no story dependencies
- **US2 (P1)**: After Phase 2 — no story dependencies (parallel with US1)
- **US3 (P2)**: After US2 (wires coupon into checkout)
- **US4 (P2)**: After US2 (reads subscription + payment data)
- **US5 (P2)**: After US1 (adds avatar methods to same service)
- **US6 (P2)**: After US1 (adds password method to same service)
- **US7 (P3)**: After US2 (active subscription must exist)
- **US8 (P3)**: After US1 (user_profiles must exist)

### Parallel Opportunities Within Each Phase

**Phase 2B** (run together after 2A):
- T009 UserProfile entity, T010 Payment entity

**Phase 2C** (run together after 2B):
- T011 UserProfileRepository, T012 PaymentRepository

**Phase 3** (within US1, parallelizable):
- T013 unit tests, T014 integration tests — run together
- T015 UserProfileResponse, T016 UpdateProfileRequest — run together

**Phase 4** (within US2, parallelizable):
- T022 SubscriptionService tests, T023 StripeService tests, T024 webhook integration tests — run together
- T025 StripeService interface, T027 PlanResponse, T028 CheckoutRequest/Response — run together after T025

---

## Parallel Example: US1 + US2 (P1 Stories Together)

```bash
# After Phase 2 completes:

# Developer A (Profile):
T013: Write UserProfileServiceTest
T017: UserProfileService interface
T018: UserProfileServiceImpl
T019: UserProfileController

# Developer B (Checkout):
T022–T024: Write Subscription/Stripe tests
T025–T026: StripeService interface + impl
T030–T033: SubscriptionServiceImpl (listPlans, checkout, activate, fail)
```

---

## Implementation Strategy

### MVP (US1 + US2 only — fully functional account + subscription)

1. Complete Phase 1: Setup
2. Complete Phase 2: DB + entities + repos
3. Complete Phase 3 (US1): Profile CRUD working
4. Complete Phase 4 (US2): Stripe checkout + webhook working
5. **STOP and VALIDATE**: User can update profile AND subscribe to SILVER/GOLD via Stripe card
6. Deploy/demo

### Incremental Delivery

1. US1 + US2 → MVP demo (profile + basic subscription)
2. US3 → coupon codes work at checkout
3. US4 → subscription status page complete
4. US5 + US6 → avatar + password change
5. US7 + US8 → cancel + notification preferences
6. Background Jobs → production-ready expiration

---

## Notes

- All `[P]` tasks operate on different files — no write conflicts
- TDD is enforced: every test task must fail before its corresponding implementation runs
- Each phase ends with a **Checkpoint** — validate independently before proceeding
- `StripeServiceImpl` is the ONLY class that may import `com.stripe.*` — enforced by Phase 12 drift check (T091)
- Avatar upload in this batch uses an inline lightweight flow (no full UploadSession entity) — see research.md Decision 3
- `revokeAllExcept()` (T064) is a NEW method on the existing `SessionService` interface from the Auth Refactor — it must be added to both the interface and `SessionServiceImpl`
- Background jobs require `@EnableScheduling` — verify or add `SchedulingConfig.java` (T086)
