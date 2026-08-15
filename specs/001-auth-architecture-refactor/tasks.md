# Tasks: Multi-Actor Auth Architecture Refactor

**Input**: Design documents from `specs/001-auth-architecture-refactor/`

**Branch**: `001-auth-architecture-refactor`

**Constitution mandate**: TDD is NON-NEGOTIABLE. Write a failing test first, confirm it fails, implement minimal code, confirm it passes, then commit. All test tasks appear before their implementation counterparts within each story.

**Organization**: Tasks are grouped by user story for independent implementation and testing. Phases 1–2 are foundational and block all user stories.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no blocking dependencies)
- **[Story]**: Maps task to user story (US1–US9)
- Every task includes an exact file path

---

## Phase 1: Setup (Structural Prerequisites)

**Purpose**: Create new directory structure and config blocks required before any entity or service code can be written.

- [x] T001 Create admin auth module directory tree: `src/main/java/com/tinniestudio/backend/modules/auth/admin/{controller,service,dto,entity}/`
- [x] T002 [P] Create user session dto/entity directories: `src/main/java/com/tinniestudio/backend/modules/auth/user/{dto,entity,service}/` (confirm existing controller/service dirs present)
- [x] T003 [P] Create billing service directory: `src/main/java/com/tinniestudio/backend/modules/billing/service/`
- [x] T004 [P] Add `jwt.admin.accessToken.*` and `jwt.admin.refreshToken.*` config blocks to `src/main/resources/application.yml` and bind in `src/main/java/com/tinniestudio/backend/shared/config/AppProperties.java`
- [x] T005 [P] Add `ADMIN_BOOTSTRAP_TOKEN` and `FREE_TIER_CONTENT_LIMIT` env var references to `src/main/resources/application.yml` (via `${ADMIN_BOOTSTRAP_TOKEN:}` placeholder pattern)
- [x] T006 [P] Create integration test directories: `src/test/java/com/tinniestudio/backend/admin/`, `src/test/java/com/tinniestudio/backend/session/`, `src/test/java/com/tinniestudio/backend/billing/`

**Checkpoint**: Directory structure exists, config skeleton is in place. No compilation errors.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: DB schema, JPA entities, repositories, and dual security filter chain MUST be complete before ANY user story can be implemented.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### 2A — Database Migrations (run in order, not in parallel)

- [x] T007 Write Flyway migration `src/main/resources/db/migration/V3__add_admin_tables.sql`: create `admins`, `admin_roles`, `admin_sessions` tables with all constraints, indexes, and `updated_at` trigger on `admins`
- [x] T008 Write Flyway migration `src/main/resources/db/migration/V4__add_user_sessions.sql`: create `user_sessions` table with `idx_user_sessions_user_id` and `idx_user_sessions_active` partial index
- [x] T009 Write Flyway migration `src/main/resources/db/migration/V6__add_coupons.sql`: create `coupons` and `coupon_redemptions` tables; add `UNIQUE (coupon_id, user_id)` constraint
- [x] T010 Write Flyway migration `src/main/resources/db/migration/V7__add_subscription_fields.sql`: `ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS content_limit INT; ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS content_watches_used INT NOT NULL DEFAULT 0;`

> **Note**: V5 (`V5__add_subscription_tables.sql`) creates `subscription_plans` and `user_subscriptions` and is tracked separately. Migration order in filesystem is V3→V4→V5→V6→V7→V8.

### 2B — JPA Entities (parallelizable after T007–T010)

- [x] T011 [P] Create `src/main/java/com/tinniestudio/backend/modules/auth/admin/entity/AdminRoleName.java` enum with values `SUPER_ADMIN`, `MODERATOR`
- [x] T012 [P] Create `src/main/java/com/tinniestudio/backend/modules/auth/admin/entity/Admin.java` JPA entity mapping `admins` + `admin_roles` tables; use `@ElementCollection` for roles; include `passwordResetToken`, `passwordResetTokenExpiry`, `passwordResetTokenInvalidated` fields
- [x] T013 [P] Create `src/main/java/com/tinniestudio/backend/modules/auth/admin/entity/AdminSession.java` JPA entity mapping `admin_sessions` table
- [x] T014 [P] Create `src/main/java/com/tinniestudio/backend/modules/auth/user/entity/UserSession.java` JPA entity mapping `user_sessions` table; include `revokedByAdminId` FK field
- [x] T015 [P] Create `src/main/java/com/tinniestudio/backend/shared/entity/Coupon.java` JPA entity with `DomainEnums.DiscountType` enum (add PERCENTAGE, FIXED to `DomainEnums.java`)
- [x] T016 [P] Create `src/main/java/com/tinniestudio/backend/shared/entity/CouponRedemption.java` JPA entity
- [x] T017 [P] Modify `src/main/java/com/tinniestudio/backend/shared/entity/RoleName.java`: remove `ROLE_ADMIN` and `ROLE_SUPER_ADMIN` values (V8 migration runs in Batch 6 — code change first)
- [x] T018 [P] Modify `src/main/java/com/tinniestudio/backend/shared/entity/SubscriptionPlan.java`: add `private Integer contentLimit;` field
- [x] T019 [P] Modify `src/main/java/com/tinniestudio/backend/shared/entity/UserSubscription.java`: add `private int contentWatchesUsed = 0;` field

### 2C — Repositories (parallelizable after entities)

- [x] T020 [P] Create `src/main/java/com/tinniestudio/backend/modules/auth/admin/repository/AdminRepository.java`: `JpaRepository<Admin, UUID>` with `existsByEmail`, `existsByRolesContaining(AdminRoleName)`, `findByEmail` methods
- [x] T021 [P] Create `src/main/java/com/tinniestudio/backend/modules/auth/admin/repository/AdminSessionRepository.java`: `findByAdminIdAndRevokedFalse`, `findFirstByAdminIdOrderByCreatedAtAsc`, `findByAdminIdAndIdAndRevokedFalse` methods
- [x] T022 [P] Create `src/main/java/com/tinniestudio/backend/modules/auth/user/repository/UserSessionRepository.java`: `findByUserIdAndRevokedFalse`, `countByUserIdAndRevokedFalse`, `findFirstByUserIdAndRevokedFalseOrderByCreatedAtAsc`, `findByUserIdAndIdAndRevokedFalse` methods
- [x] T023 [P] Create `src/main/java/com/tinniestudio/backend/modules/billing/repository/CouponRepository.java`: `findByCodeIgnoreCase` method
- [x] T024 [P] Create `src/main/java/com/tinniestudio/backend/modules/billing/repository/CouponRedemptionRepository.java`: `existsByCouponIdAndUserId` method

### 2D — JWT Infrastructure (parallelizable after T004)

- [x] T025 [P] Write unit test `src/test/java/com/tinniestudio/backend/auth/jwt/AdminJwtTokenProviderTest.java`: test admin token has `aud=admin`, `sid` claim, separate secret; test user token rejected by admin validator
- [x] T026 Modify `src/main/java/com/tinniestudio/backend/shared/security/jwt/JwtTokenProvider.java`: add `aud=user` audience claim and `sid` (sessionId) parameter to `generateAccessToken()` and `generateRefreshToken()` signatures; update all callers
- [x] T027 [P] Create `src/main/java/com/tinniestudio/backend/shared/security/jwt/AdminJwtTokenProvider.java`: mirrors `JwtTokenProvider` but reads `jwt.admin.*` secrets and sets `aud=admin` on all issued tokens
- [x] T028 Write unit test for modified `JwtTokenProvider` in `src/test/java/com/tinniestudio/backend/auth/jwt/JwtTokenProviderTest.java`: verify `aud=user` and `sid` claims present
- [x] T029 Verify all existing tests in `JwtTokenProviderTest.java` still pass after T026 changes

### 2E — Security Filter Chain Split (depends on T025–T027)

- [x] T030 [P] Create `src/main/java/com/tinniestudio/backend/modules/auth/admin/service/AdminUserDetailsServiceImpl.java`: `UserDetailsService` that loads `Admin` by ID; grants `GrantedAuthority` from `AdminRoleName`
- [x] T031 [P] Create `src/main/java/com/tinniestudio/backend/shared/security/jwt/AdminJwtAuthenticationFilter.java`: reads `admin_access_token` cookie, validates via `AdminJwtTokenProvider`, sets admin principal in `SecurityContextHolder`; `shouldNotFilter` excludes `/auth/admin/login`, `/auth/admin/refresh`, `/auth/admin/bootstrap`, `/auth/admin/forgot-password`, `/auth/admin/reset-password`
- [x] T032 Write integration test `src/test/java/com/tinniestudio/backend/admin/AdminSecurityChainTest.java`: (a) user token rejected on `/auth/admin/me` → 401; (b) admin token rejected on `/auth/me` → 401; (c) missing token on admin endpoint → 401
- [x] T033 Refactor `src/main/java/com/tinniestudio/backend/shared/config/SecurityConfig.java`: split into `AdminSecurityFilterChain` (`@Order(1)`, covers `/auth/admin/**`, uses `AdminJwtAuthenticationFilter`) and `UserSecurityFilterChain` (`@Order(2)`, covers all other paths, unchanged behavior)
- [x] T034 Modify `src/main/java/com/tinniestudio/backend/shared/security/jwt/CookieFactory.java`: add `addAdminAuthCookies(response, accessToken, refreshToken)` and `clearAdminAuthCookies(response)` methods; admin cookie names: `admin_access_token`, `admin_refresh_token`
- [x] T035 Run `AdminSecurityChainTest` and confirm tests pass

**Checkpoint**: DB schema is correct, all entities compile, dual filter chain is in place, JWT tokens have `aud` and `sid` claims, cross-audience rejection is verified. Foundation complete.

---

## Phase 3: User Story 1 — Admin System Bootstrap (Priority: P1) 🎯 MVP Start

**Goal**: A platform operator can create the first super admin account via a one-time env-gated endpoint. After first use the endpoint is permanently disabled.

**Independent Test**: Set `ADMIN_BOOTSTRAP_TOKEN` env var, call `POST /auth/admin/bootstrap`, verify super admin created, then verify second call is rejected.

### Tests for US1

- [x] T036 [P] [US1] Write unit test `src/test/java/com/tinniestudio/backend/admin/service/AdminBootstrapServiceTest.java`: (a) valid token + no super admin → creates admin with SUPER_ADMIN role; (b) valid token + super admin exists → throws conflict; (c) wrong token → throws 400; (d) second call after success → throws 404 (endpoint disabled)
- [x] T037 [P] [US1] Write integration test `src/test/java/com/tinniestudio/backend/admin/AdminBootstrapIntegrationTest.java`: end-to-end bootstrap flow via `MockMvc`; verify DB state after success

### Implementation for US1

- [x] T038 [US1] Create `src/main/java/com/tinniestudio/backend/modules/auth/admin/dto/AdminBootstrapRequest.java` with `bootstrapToken`, `email`, `password` fields and Bean Validation annotations
- [x] T039 [US1] Create `src/main/java/com/tinniestudio/backend/modules/auth/admin/dto/AdminAuthResponse.java` with `adminId`, `email`, `roles`, `message` fields
- [x] T040 [US1] Implement `src/main/java/com/tinniestudio/backend/modules/auth/admin/service/AdminBootstrapService.java`: `bootstrapSuperAdmin(request)` — check `AtomicBoolean bootstrapUsed` flag; check `adminRepository.existsByRolesContaining(SUPER_ADMIN)`; validate env token via `appProperties.getAdminBootstrapToken()`; create Admin with SUPER_ADMIN role; set flag
- [x] T041 [US1] Create `src/main/java/com/tinniestudio/backend/modules/auth/admin/controller/AdminAuthController.java` with only `POST /auth/admin/bootstrap` endpoint wired to `AdminBootstrapService`; add `@RateLimit` annotation
- [x] T042 [US1] Run all US1 tests and confirm they pass

**Checkpoint**: Bootstrap endpoint operational and self-disabling. Super admin can be created exactly once.

---

## Phase 4: User Story 2 — Separate Admin Authentication Flow (Priority: P1)

**Goal**: Admin login issues cryptographically separate tokens in separate cookies. A user token cannot access any admin endpoint. Admin token cannot access user endpoints.

**Independent Test**: Admin login → verify `admin_access_token` cookie present → call `/auth/admin/me` → 200. Then submit the same token to `/auth/me` → 401.

### Tests for US2

- [x] T043 [P] [US2] Write unit test `src/test/java/com/tinniestudio/backend/admin/service/AdminAuthServiceTest.java`: (a) valid credentials → returns AdminAuthResponse; (b) wrong password → throws BadCredentialsException; (c) suspended account → throws AccountNotActiveException; (d) previous session revoked on new login
- [x] T044 [P] [US2] Write integration test `src/test/java/com/tinniestudio/backend/admin/AdminAuthIntegrationTest.java`: full login → refresh → logout → verify me returns 401

### Implementation for US2

- [x] T045 [US2] Create `src/main/java/com/tinniestudio/backend/modules/auth/admin/dto/AdminLoginRequest.java`
- [x] T046 [US2] Implement `AdminAuthService.login(request, response)` in `src/main/java/com/tinniestudio/backend/modules/auth/admin/service/AdminAuthService.java`: validate credentials via `PasswordEncoder`; revoke existing `AdminSession` (DB + cache); create new `AdminSession` (store BCrypt hash of new refresh token, set Redis key `tinnie:admin:session:{adminId}:{sessionId}` via `CacheService`); embed `sid` in admin tokens; set `admin_access_token` + `admin_refresh_token` cookies via `CookieFactory.addAdminAuthCookies()`
- [x] T047 [US2] Implement `AdminAuthService.refresh(request, response)`: extract `admin_refresh_token` cookie; validate `aud=admin`; lookup Redis `tinnie:admin:session:{adminId}:{sessionId}` (miss → 401); verify BCrypt hash against `AdminSession.refreshTokenHash` (mismatch → revoke + 401); rotate token: new hash in DB + new Redis TTL; issue new access token cookie
- [x] T048 [US2] Implement `AdminAuthService.logout(request, response)`: extract session from admin access token `sid` claim; mark `AdminSession.revoked=true` in DB; delete Redis key; call `CookieFactory.clearAdminAuthCookies(response)`
- [x] T049 [US2] Implement `AdminAuthService.getAdminProfile(adminId)`: return `AdminAuthResponse` from `Admin` entity
- [x] T050 [US2] Add `login`, `refresh`, `logout`, `me` endpoints to `AdminAuthController` mapped to `AdminAuthService` methods
- [x] T051 [US2] Run all US2 tests and confirm they pass

**Checkpoint**: Full admin auth lifecycle works. Cross-audience token rejection confirmed.

---

## Phase 5: User Story 3 — Sub-Admin Creation by Super Admin (Priority: P2)

**Goal**: An authenticated super admin creates a MODERATOR account. A non-super-admin is rejected. A second SUPER_ADMIN cannot be created.

**Independent Test**: Login as super admin → `POST /auth/admin/register` with MODERATOR role → 201; then login as moderator → `/auth/admin/me` → roles contain MODERATOR; then attempt to create SUPER_ADMIN → 400.

### Tests for US3

- [x] T052 [P] [US3] Write unit test in `AdminAuthServiceTest.java`: (a) SUPER_ADMIN creates MODERATOR → success; (b) MODERATOR attempts register → 403; (c) SUPER_ADMIN creates second SUPER_ADMIN → 400
- [x] T053 [P] [US3] Write integration test in `AdminAuthIntegrationTest.java`: full register flow via MockMvc including role enforcement

### Implementation for US3

- [x] T054 [US3] Create `src/main/java/com/tinniestudio/backend/modules/auth/admin/dto/AdminRegisterRequest.java` with `email`, `password`, `firstName`, `lastName`, `role` fields
- [x] T055 [US3] Implement `AdminAuthService.registerAdmin(request)`: check `adminRepository.existsByRolesContaining(SUPER_ADMIN)` to block second super admin; check `adminRepository.existsByEmail()` for conflict; create `Admin` entity; encode password; save with requested role
- [x] T056 [US3] Add `POST /auth/admin/register` endpoint to `AdminAuthController`; annotate with `@PreAuthorize("hasRole('SUPER_ADMIN')")`
- [x] T057 [US3] Implement `AdminAuthService.forgotPassword(email)` and `AdminAuthService.resetPassword(token, newPassword)` with stricter rules: 15-min token expiry; `passwordResetTokenInvalidated=true` on any failed attempt (including weak password); revoke all admin sessions on success; send email alert to super admin via `EmailService`; rate limit 1/60min/IP on forgot-password endpoint
- [x] T058 [US3] Add `POST /auth/admin/forgot-password` and `PATCH /auth/admin/reset-password` endpoints to `AdminAuthController`
- [x] T059 [US3] Run all US3 tests and confirm they pass

**Checkpoint**: Full admin CRUD auth system is operational. Super admin constraints enforced.

---

## Phase 6: User Story 4 — Subscription-Aware Device Session Enforcement (Priority: P2)

**Goal**: Free-plan users are limited to 1 concurrent session. Oldest session is automatically evicted on new login. Gold-plan users are limited to 3.

**Independent Test**: Register user → login from 2 devices → confirm first device's refresh token returns 401.

### Tests for US4

- [x] T060 [P] [US4] Write unit test `src/test/java/com/tinniestudio/backend/session/SessionServiceTest.java`: (a) free plan + 0 sessions → session created; (b) free plan + 1 active session → oldest evicted, new session created; (c) gold plan + 3 sessions → oldest evicted, new created; (d) evicted session Redis key deleted
- [x] T061 [P] [US4] Write integration test `src/test/java/com/tinniestudio/backend/session/SessionEnforcementIntegrationTest.java`: two logins under free plan → first refresh token → 401

### Implementation for US4

- [x] T062 [US4] Create `src/main/java/com/tinniestudio/backend/modules/auth/user/service/SessionService.java` interface with: `SessionRecord createSession(UUID userId, String rawRefreshToken, HttpServletRequest request)`, `void revokeSession(UUID userId, UUID sessionId, UUID adminId)`, `void revokeAllUserSessions(UUID userId, UUID adminId)`, `List<SessionDto> getActiveSessions(UUID userId)`, `SessionRecord validateAndRotate(UUID userId, UUID sessionId, String rawRefreshToken, String newRawRefreshToken)`
- [x] T063 [US4] Implement `src/main/java/com/tinniestudio/backend/modules/auth/user/service/SessionServiceImpl.java`: `createSession()` — load `UserSubscription` to get `plan.maxDevices`; count active sessions; evict oldest if at limit (DB revoke + `CacheService.delete(tinnie:session:{userId}:{sessionId})`); BCrypt hash new token; save `UserSession` to DB; write Redis `tinnie:session:{userId}:{sessionId}` via `CacheService.set()` with 7-day TTL; return `SessionRecord(sessionId)`
- [x] T064 [US4] Modify `src/main/java/com/tinniestudio/backend/modules/auth/service/AuthService.java` `login()`: after successful authentication, call `sessionService.createSession(userId, rawRefreshToken, request)` to get `sessionId`; pass `sessionId` to `jwtTokenProvider.generateAccessToken(user, sessionId)` and `generateRefreshToken(user, sessionId)`
- [x] T065 [US4] Modify `AuthService.register()` to auto-create `UserSubscription` with FREE plan and `contentWatchesUsed=0` via `subscriptionRepository.save()` after user is saved
- [x] T066 [US4] Run all US4 tests and confirm they pass

**Checkpoint**: Device limit is enforced. Old sessions evicted automatically. UserSubscription created on registration.

---

## Phase 7: User Story 5 — Refresh Token Rotation and Revocation (Priority: P2)

**Goal**: Every refresh rotates the token. A replayed old refresh token triggers full session revocation.

**Independent Test**: Login → refresh → replay old refresh token → 401 returned and all sessions revoked.

### Tests for US5

- [x] T067 [P] [US5] Add to `SessionServiceTest.java`: (a) valid session + matching hash → new hash stored, Redis TTL reset, old token invalid; (b) valid session + non-matching hash (replay) → session revoked in DB + Redis deleted → throws `InvalidTokenException`
- [x] T068 [P] [US5] Write integration test `src/test/java/com/tinniestudio/backend/session/TokenRotationIntegrationTest.java`: login → refresh → replay original token → 401; confirm all sessions are revoked after replay (validates SC-003)

### Implementation for US5

- [x] T069 [US5] Implement `SessionServiceImpl.validateAndRotate(userId, sessionId, rawOldRefreshToken, newRawRefreshToken)`: check Redis key exists (miss → throw); load `UserSession` from DB (revoked=false); BCrypt-verify `rawOldRefreshToken` against `refreshTokenHash` (fail → `revokeSession()` → throw InvalidTokenException); update `refreshTokenHash` to BCrypt of `newRawRefreshToken`; update `lastUsedAt`; reset Redis TTL via `CacheService.set()`
- [x] T070 [US5] Modify `AuthService.refresh()`: parse `sessionId` from refresh token `sid` claim; generate new raw refresh token; call `sessionService.validateAndRotate(userId, sessionId, oldRawToken, newRawToken)`; embed `sessionId` in new access + refresh tokens; set new cookies
- [x] T071 [US5] Modify `AuthService.logout()`: parse `sessionId` from access token `sid` claim (available via `SecurityContextHolder`); call `sessionService.revokeSession(userId, sessionId, null)`; clear cookies
- [x] T072 [US5] Run all US5 tests and confirm they pass

**Checkpoint**: Token rotation enforced. Replay detection triggers full session revocation.

---

## Phase 8: User Story 6 — Enriched `/auth/me` Profile Aggregation (Priority: P2)

**Goal**: `/auth/me` returns identity + subscription + sessions in one call. The calling session is flagged `current: true`.

**Independent Test**: Login → call `GET /auth/me` → response contains `subscription.plan`, `devices.sessions[0].current: true`, and `devices.sessions[0].deviceName`.

### Tests for US6

- [x] T073 [P] [US6] Write unit test `src/test/java/com/tinniestudio/backend/auth/service/AuthProfileServiceTest.java`: (a) user with FREE sub + 1 session → all three domains present; (b) calling session ID matches → `current: true` on that session; (c) `canWatch: false` when quota exhausted
- [x] T074 [P] [US6] Write integration test: `GET /auth/me` after login → validate full response shape matches `contracts/api-contracts.md`

### Implementation for US6

- [x] T075 [US6] Create `src/main/java/com/tinniestudio/backend/modules/auth/user/dto/SubscriptionDto.java` with `plan`, `status`, `maxDevices`, `contentWatchesUsed`, `contentWatchesLimit`, `canWatch`, `expiresAt` fields
- [x] T076 [US6] Create `src/main/java/com/tinniestudio/backend/modules/auth/user/dto/SessionDto.java` with `sessionId`, `deviceName`, `ipAddress`, `lastUsedAt`, `current` fields
- [x] T077 [US6] Create `src/main/java/com/tinniestudio/backend/modules/auth/user/dto/AuthProfileResponse.java` with all fields per `contracts/api-contracts.md` `/auth/me` response shape; add static factory `AuthProfileResponse.of(user, sub, sessions, currentSessionId)`
- [x] T078 [US6] Implement `SessionServiceImpl.getActiveSessions(userId)`: query `userSessionRepository.findByUserIdAndRevokedFalse(userId)`; map to `List<SessionDto>` (device fingerprint display resolved via UA parser utility)
- [x] T079 [US6] Create `src/main/java/com/tinniestudio/backend/modules/auth/user/service/AuthProfileService.java`: `getProfile(UUID userId, UUID currentSessionId)` — calls `userService.getById()`, `subscriptionService.getActiveSubscription()`, `sessionService.getActiveSessions()`; assembles `AuthProfileResponse`; sets `current: true` on matching session; sets `canWatch` via `capabilityService.canWatch(userId)`
- [x] T080 [US6] Modify `src/main/java/com/tinniestudio/backend/modules/auth/controller/AuthController.java` `me()` method: change to `@AuthenticationPrincipal UserDetails`; extract `userId` and `sessionId` from principal/security context; call `authProfileService.getProfile(userId, sessionId)`
- [x] T081 [US6] Add `sessionId` to the `UserDetails` principal (or store in request attribute in `JwtAuthenticationFilter`) so `AuthController` can access it without re-parsing the token
- [x] T082 [US6] Run all US6 tests and confirm they pass

**Checkpoint**: `/auth/me` returns enriched single-round-trip response. No additional frontend calls needed.

---

## Phase 9: User Story 7 — Free Tier Content Quota Enforcement (Priority: P3)

**Goal**: Free-plan users are blocked from streaming after 2 lifetime watches. Counter increments on request start. Admin can set limit to 0 to block all free-tier access.

**Independent Test**: Register user → stream twice (counter = 2) → third stream → 403 `upgrade_required`.

### Tests for US7

- [x] T083 [P] [US7] Write unit test `src/test/java/com/tinniestudio/backend/billing/CapabilityServiceTest.java`: (a) user under limit → `canWatch()` returns true; (b) user at limit → `canWatch()` returns false; (c) `contentLimit=null` (paid plan) → always returns true; (d) `contentLimit=0` → always returns false; (e) Redis cache hit returns correct value
- [x] T084 [P] [US7] Write integration test `src/test/java/com/tinniestudio/backend/billing/ContentQuotaIntegrationTest.java`: register user → exhaust quota → verify 403 with `reason: upgrade_required`

### Implementation for US7

- [x] T085 [US7] Create `src/main/java/com/tinniestudio/backend/modules/billing/service/CapabilityService.java` interface with `boolean canWatch(UUID userId)` and `void recordWatch(UUID userId)`
- [x] T086 [US7] Implement `src/main/java/com/tinniestudio/backend/modules/billing/service/CapabilityServiceImpl.java`: `canWatch()` — check Redis `tinnie:content_quota:{userId}` via `CacheService.get()`; on miss load from `UserSubscription.contentWatchesUsed` and cache it; compare against `plan.contentLimit` (null → unlimited; 0 → always false); `recordWatch()` — `UPDATE user_subscriptions SET content_watches_used = content_watches_used + 1 WHERE user_id = ?`; invalidate Redis key
- [x] T087 [US7] Modify `UserSubscriptionRepository` to add `findByUserIdAndStatus(UUID userId, SubscriptionStatus status)` if not already present
- [x] T088 [US7] Add `UPGRADE_REQUIRED` error code handling to `src/main/java/com/tinniestudio/backend/shared/exception/GlobalExceptionHandler.java`; create `UpgradeRequiredException.java` in shared exceptions
- [x] T089 [US7] Run all US7 tests and confirm they pass

**Checkpoint**: Free-tier quota gate operational. `canWatch()` usable by content/streaming service.

---

## Phase 10: User Story 8 — Admin Force-Logout and Session Revocation (Priority: P3)

**Goal**: Admin can revoke all sessions for a user (global logout) or a single session by ID. Refresh tokens invalidated immediately.

**Independent Test**: Login as user → admin calls revoke-all → user refresh token → 401.

### Tests for US8

- [x] T090 [P] [US8] Add to `SessionServiceTest.java`: (a) `revokeAllUserSessions()` — all active sessions marked revoked in DB, all Redis keys deleted; (b) `revokeSession(sessionId)` — only that session revoked; (c) `revokedByAdminId` is set on revoked sessions
- [x] T091 [P] [US8] Write integration test `src/test/java/com/tinniestudio/backend/session/AdminForceLogoutIntegrationTest.java`: user login → admin revoke-all → user refresh → 401

### Implementation for US8

- [x] T092 [US8] Implement `SessionServiceImpl.revokeSession(userId, sessionId, adminId)`: load `UserSession`; set `revoked=true`, `revokedAt=now()`, `revokedByAdminId=adminId`; save; delete Redis key `tinnie:session:{userId}:{sessionId}` via `CacheService`
- [x] T093 [US8] Implement `SessionServiceImpl.revokeAllUserSessions(userId, adminId)`: `userSessionRepository.findByUserIdAndRevokedFalse(userId)`; for each: set revoked + revokedByAdminId; batch save; bulk-delete Redis keys via `CacheService`
- [x] T094 [US8] Create admin user management endpoints (can be in a future `AdminUserController` or as utility endpoints in `AdminAuthController`): `DELETE /auth/admin/users/{userId}/sessions` (revoke all) and `DELETE /auth/admin/users/{userId}/sessions/{sessionId}` (single); annotate with `@PreAuthorize("hasAnyRole('SUPER_ADMIN','MODERATOR')")`
- [x] T095 [US8] Run all US8 tests and confirm they pass

**Checkpoint**: Admin force-logout is operational. Refresh tokens invalidated immediately on revocation.

---

## Phase 11: User Story 9 — Coupon Application at Plan Upgrade (Priority: P3)

**Goal**: Valid coupon codes are validated against 4 rules and applied at checkout. Concurrent redemption of the same code by the same user is prevented by DB constraint.

**Independent Test**: Create coupon → apply at checkout → redemption recorded, `usesCount` incremented → apply same coupon again for same user → 400 `already_used`.

### Tests for US9

- [x] T096 [P] [US9] Write unit test `src/test/java/com/tinniestudio/backend/billing/CouponServiceTest.java`: (a) valid coupon → passes all 4 checks; (b) `isActive=false` → `not_found`; (c) expired → `expired`; (d) at max uses → `limit_reached`; (e) already redeemed by this user → `already_used`; (f) concurrent redemption → DB constraint prevents duplicate
- [x] T097 [P] [US9] Write integration test: apply coupon → verify `uses_count` incremented + `coupon_redemptions` row created

### Implementation for US9

- [x] T098 [US9] Create `src/main/java/com/tinniestudio/backend/modules/billing/dto/CouponValidationResult.java` with `valid`, `reason`, `discountType`, `discountValue` fields
- [x] T099 [US9] Implement `src/main/java/com/tinniestudio/backend/modules/billing/service/CouponService.java` interface with `CouponValidationResult validateCoupon(String code, UUID userId)` and `void redeemCoupon(UUID couponId, UUID userId, UUID subscriptionId)`
- [x] T100 [US9] Implement `src/main/java/com/tinniestudio/backend/modules/billing/service/CouponServiceImpl.java`: `validateCoupon()` — lookup by code; check `isActive`; check date window; check `usesCount < maxUses`; check `couponRedemptionRepository.existsByCouponIdAndUserId()`; `redeemCoupon()` — `@Transactional`: `UPDATE coupons SET uses_count = uses_count + 1 WHERE id = ?`; insert `CouponRedemption`; DB UNIQUE constraint on `(coupon_id, user_id)` catches concurrent duplicate attempts
- [x] T101 [US9] Write Flyway migration `src/main/resources/db/migration/V8__remove_admin_roles_from_users.sql`: `DELETE FROM user_roles WHERE role_id IN (SELECT id FROM roles WHERE name IN ('ROLE_ADMIN','ROLE_SUPER_ADMIN')); DELETE FROM roles WHERE name IN ('ROLE_ADMIN','ROLE_SUPER_ADMIN'); INSERT INTO roles (name) VALUES ('ROLE_PARTNER') ON CONFLICT DO NOTHING;`
- [x] T102 [US9] Run all US9 tests and confirm they pass

**Checkpoint**: All 9 user stories complete. Full feature is functional.

---

## Phase 12: Entity–DB Alignment & Documentation Fixes

**Purpose**: Address findings from `/speckit-analyze` (2026-05-31). Correct JPA entity declarations to precisely match the Flyway-owned schema. Fix migration reference drift in documentation artifacts.

**⚠️ NOTE**: These tasks do NOT change DB schema or service behavior. All changes are JPA annotation corrections and documentation fixes only.

- [x] T103 [P] Fix `src/main/java/com/tinniestudio/backend/shared/entity/BaseEntity.java`: change `@GeneratedValue` to `@GeneratedValue(strategy = GenerationType.UUID)` to make UUID generation strategy explicit under Hibernate 6
- [x] T104 [P] Fix `src/main/java/com/tinniestudio/backend/shared/entity/User.java` index annotations: update `idx_users_provider_id` to `columnList = "provider, provider_id"` (composite, matching V1 migration); add `@Index(name = "idx_users_deleted_at", columnList = "deleted_at")` to the `@Table` indexes array (was in migration, absent from entity)
- [x] T105 [P] Fix `src/main/java/com/tinniestudio/backend/shared/entity/SubscriptionPlan.java` column constraints to match `subscription_plans` table (V5 migration): add `@Column(nullable = false, length = 100)` on `name`; add `precision = 10, scale = 2` to `price` `@Column`; add `@Column(length = 3)` on `currency`; add `@Column(length = 20)` on `billingCycle` and `videoQuality`; add `@Column(name = "is_active", nullable = false)` on `isActive`
- [x] T106 [P] Fix `src/main/java/com/tinniestudio/backend/shared/entity/UserSubscription.java` column constraints to match `user_subscriptions` table (V5 migration): add `@Column(nullable = false)` on `status`; add `@Column(nullable = false)` on `autoRenew`
- [x] T107 [P] Fix documentation migration references: update `specs/001-auth-architecture-refactor/quickstart.md` "V3–V6 migrations" → "V3–V8"; update `specs/001-auth-architecture-refactor/plan.md` Batch 1 deliverables and Batch 6 — V5→V6 (coupons), V6→V7 (subscription fields), V7→V8 (remove admin roles); update `specs/001-auth-architecture-refactor/data-model.md` Flyway Migration Order table to match actual V3–V8 filenames
- [x] T108 Run full Flyway migration sequence V3→V8 on fresh PostgreSQL database via Testcontainers to confirm all 6 migrations apply cleanly in order
- [x] T109 Verify application startup with entity changes: `mvn compile` — clean, no HibernateException or compilation errors

**Checkpoint**: All entity `@Column` annotations precisely match the Flyway-owned DB schema. Documentation references correct migration filenames. Full migration sequence verified on fresh DB.

---

## Final Phase: Polish & Cross-Cutting Concerns

- [x] T110 [P] Add SpringDoc/OpenAPI annotations to all new admin endpoints in `AdminAuthController.java` (operation summaries, response schemas, security schemes for `admin_access_token` cookie)
- [x] T111 [P] Add SpringDoc/OpenAPI annotation to enhanced `AuthController.me()` documenting the new `subscription` and `devices` response fields
- [x] T112_old [P] Review all new service classes for `@Value` annotation usage — replace any with `@ConfigurationProperties`-bound values (constitution drift check)
- [x] T113_old [P] Review all new service classes for direct `RedisTemplate` injection — replace any with `CacheService` calls (constitution drift check)
- [ ] T114_old Run `speckit-analyze` cross-artifact consistency check (re-run after Phase 12 completes)
- [ ] T115_old Run full test suite — confirm all tests pass
- [ ] T116_old Run `quickstart.md` steps end-to-end on local environment
- [ ] T117_old [P] Add `UserSession.deviceName` UA parser utility: create `src/main/java/com/tinniestudio/backend/shared/util/DeviceNameParser.java` (simple regex-based, no external dependency) if not extracted during SessionService implementation

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — BLOCKS all user stories
  - 2A (migrations) must complete before 2B (entities)
  - 2B (entities) must complete before 2C (repositories)
  - 2D (JWT) can run in parallel with 2B and 2C
  - 2E (security config) depends on 2D
- **US1 Bootstrap (Phase 3)**: Depends on Phase 2 complete — P1, MVP start
- **US2 Admin Auth (Phase 4)**: Depends on Phase 3 (AdminSession write path)
- **US3 Sub-Admin (Phase 5)**: Depends on Phase 4 (admin login needed to test)
- **US4 Device Enforcement (Phase 6)**: Depends on Phase 2 (UserSession entities), independent of US1-3
- **US5 Token Rotation (Phase 7)**: Depends on Phase 6 (SessionService.createSession must exist first)
- **US6 `/auth/me` (Phase 8)**: Depends on Phase 6+7 (SessionService.getActiveSessions needed)
- **US7 Content Quota (Phase 9)**: Depends on Phase 6 (UserSubscription auto-creation in register)
- **US8 Force-Logout (Phase 10)**: Depends on Phase 6+7 (revokeSession implementations)
- **US9 Coupon (Phase 11)**: Depends on Phase 2 (Coupon entities) — otherwise independent of other stories
- **Entity Alignment (Phase 12)**: Independent — can run at any time; no service behavior change
- **Polish (Final)**: Depends on all stories + Phase 12 complete

### User Story Dependencies

- **US1 (P1)**: After Phase 2 — no story dependencies
- **US2 (P1)**: After US1 (AdminSession created by login)
- **US3 (P2)**: After US2 (admin login needed to test register)
- **US4 (P2)**: After Phase 2 — independent of US1/US2/US3
- **US5 (P2)**: After US4 (requires `SessionService.createSession`)
- **US6 (P2)**: After US5 (requires `SessionService.getActiveSessions`)
- **US7 (P3)**: After US4 (requires `UserSubscription` auto-creation in register)
- **US8 (P3)**: After US5 (requires `revokeSession` + `revokeAllUserSessions`)
- **US9 (P3)**: After Phase 2 — independent of US1-US8

### Parallel Opportunities Within Each Phase

**Phase 2B** (run together after 2A):
- T011 AdminRoleName, T012 Admin, T013 AdminSession, T014 UserSession, T015 Coupon, T016 CouponRedemption, T017 RoleName, T018 SubscriptionPlan, T019 UserSubscription

**Phase 2C** (run together after 2B):
- T020 AdminRepository, T021 AdminSessionRepository, T022 UserSessionRepository, T023 CouponRepository, T024 CouponRedemptionRepository

**Phase 2D** (partially parallel with 2B/2C):
- T025 (test) + T027 (AdminJwtTokenProvider) can run together

**Phase 12** (all parallelizable — different files):
- T103 BaseEntity, T104 User, T105 SubscriptionPlan, T106 UserSubscription, T107 docs — all run in parallel

---

## Parallel Example: User Story 4 (Device Enforcement)

```bash
# Write tests and create interface in parallel:
Task T060: SessionServiceTest - device limit scenarios
Task T062: SessionService interface

# Then implement (depends on T062):
Task T063: SessionServiceImpl.createSession()

# Then wire into AuthService (depends on T063):
Task T064: AuthService.login() enhanced
Task T065: AuthService.register() - UserSubscription creation
```

---

## Parallel Example: Phase 12 (Entity Alignment)

```bash
# All run in parallel — no shared files:
Task T103: BaseEntity.java - @GeneratedValue strategy
Task T104: User.java - index corrections
Task T105: SubscriptionPlan.java - @Column constraints
Task T106: UserSubscription.java - @Column nullable
Task T107: quickstart.md + plan.md - migration reference fixes
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 2 — Admin Auth Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (DB + JWT + security chain)
3. Complete Phase 3: US1 — Bootstrap
4. Complete Phase 4: US2 — Admin Login/Logout/Refresh/Me
5. **STOP and VALIDATE**: Super admin can log in, manage sessions, reach `/auth/admin/me`
6. Deploy/demo admin auth in isolation

### Incremental Delivery

1. Foundation → Admin auth live (US1 + US2) → MVP demo
2. Add US3 (sub-admin) → full admin team can log in
3. Add US4 + US5 → session enforcement live, refresh token rotation live
4. Add US6 → enriched `/auth/me` live
5. Add US7 → free tier quota enforcement live
6. Add US8 + US9 → admin controls + coupon system live
7. Phase 12 → entity-DB alignment hardened

### Parallel Team Strategy

With two developers after Phase 2:
- **Developer A**: US1 → US2 → US3 (admin auth system)
- **Developer B**: US4 → US5 → US6 (user session + profile)
- Both independent until US6 (B) needs admin force-logout from US8

---

## Notes

- All `[P]` tasks operate on different files — no write conflicts
- TDD is enforced: every test task must fail before its corresponding implementation task runs
- Each user story phase ends with a **Checkpoint** — validate independently before proceeding
- V8 migration (T101) runs last intentionally — removes admin roles from user table only after admin module is fully deployed
- Migration order in filesystem: V3→V4→V5(subscription\_tables)→V6(coupons)→V7(subscription\_fields)→V8(remove\_admin\_roles)
- `CacheService` interface is assumed to exist — if not yet implemented, add before T063
- Phase 12 tasks are annotation-only changes — no behavior change, no new migrations required
