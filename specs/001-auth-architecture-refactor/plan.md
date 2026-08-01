# Implementation Plan: Multi-Actor Auth Architecture Refactor

**Branch**: `001-auth-architecture-refactor` | **Date**: 2026-05-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-auth-architecture-refactor/spec.md`

---

## Summary

Evolve the TinnieStudio backend from a stateless single-actor JWT system into a multi-actor, session-aware security architecture. The primary technical approach: (1) add a second `SecurityFilterChain` at `@Order(1)` to isolate admin auth, (2) introduce `UserSession` / `AdminSession` entities for stateful refresh token governance, (3) enrich `/auth/me` via an `AuthProfileService` aggregator, and (4) add a free-tier content quota gate and coupon system. All existing user auth endpoints are preserved with no breaking changes.

---

## Technical Context

**Language/Version**: Java 21 / Spring Boot 3.3.5

**Primary Dependencies**: Spring Security 6, Spring Data JPA, jjwt 0.12.x, Lettuce (Redis), Flyway, BCryptPasswordEncoder, Resend (email), springdoc-openapi

**Storage**: PostgreSQL (primary) + Redis (session cache, rate limiting, content quota)

**Testing**: JUnit 5 + Mockito (unit), Spring Boot Test + Testcontainers (integration)

**Target Platform**: Linux server (Spring Boot fat JAR)

**Project Type**: Web service (REST API — modular monolith)

**Performance Goals**: Stateless access token validation (0 DB/Redis calls per request); refresh check < 10ms via Redis fast-path

**Constraints**: Zero breaking changes to existing `/auth/**` user endpoint contracts; all new admin endpoints under `/auth/admin/**`; all cache access through `CacheService` interface; all config through `@ConfigurationProperties`

**Scale/Scope**: ~10k users initial target; 3 subscription plans; up to 5 admin accounts

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Rule | Status | Notes |
|------|--------|-------|
| §I — Feature lifecycle | ✅ PASS | Full specify → plan → tasks → implement cycle |
| §II — Batch boundary | ✅ PASS | Auth refactor is a single bounded capability |
| §IV — Domain ownership | ✅ PASS | `SessionService` owns session mutations; `CapabilityService` is the cross-domain boundary; no repo cross-injection |
| §V — Scalability | ✅ PASS | Role array design; new billing tiers do not require session architecture changes |
| §VI — Infrastructure abstraction | ✅ PASS | All Redis via `CacheService` interface; all config via `@ConfigurationProperties` |
| §VIII — Drift prevention | ✅ PASS | No `@Value` in services; no direct `RedisTemplate`; no cross-domain repo injection |
| §IX — Shared contracts | ✅ PASS | Standard `{ success, data, error }` envelope; `UPGRADE_REQUIRED` machine-readable code |
| §X — Multi-actor security boundaries | ✅ PASS | Dual `SecurityFilterChain`; separate JWT secrets; `aud` claim separation; single admin session policy |

**Post-design re-check**: All gates pass. No violations.

---

## Project Structure

### Documentation (this feature)

```text
specs/001-auth-architecture-refactor/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 — unknowns resolved
├── data-model.md        # Phase 1 — entity design
├── quickstart.md        # Phase 1 — developer onboarding
├── contracts/
│   └── api-contracts.md # Phase 1 — REST endpoint contracts
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
src/main/java/com/tinniestudio/backend/
├── modules/
│   └── auth/
│       ├── admin/                          # NEW — admin auth module
│       │   ├── controller/
│       │   │   └── AdminAuthController.java
│       │   ├── service/
│       │   │   ├── AdminAuthService.java
│       │   │   └── AdminBootstrapService.java
│       │   ├── dto/
│       │   │   ├── AdminLoginRequest.java
│       │   │   ├── AdminRegisterRequest.java
│       │   │   └── AdminAuthResponse.java
│       │   └── entity/
│       │       ├── Admin.java
│       │       ├── AdminRoleName.java
│       │       └── AdminSession.java
│       └── user/                           # Reorganized from existing modules/auth/
│           ├── controller/
│           │   └── AuthController.java     # Existing — enhanced /auth/me
│           ├── service/
│           │   ├── AuthService.java        # Existing — enhanced login/refresh/logout
│           │   ├── EmailService.java       # Existing — unchanged
│           │   ├── OAuth2Service.java      # Existing — unchanged
│           │   ├── SessionService.java     # NEW — device tracking, limit enforcement
│           │   └── AuthProfileService.java # NEW — /auth/me aggregation
│           ├── dto/
│           │   ├── AuthResponse.java       # Existing
│           │   ├── AuthProfileResponse.java# NEW
│           │   ├── SessionDto.java         # NEW
│           │   ├── SubscriptionDto.java    # NEW — subscription slice of /auth/me
│           │   └── [existing DTOs unchanged]
│           └── entity/
│               └── UserSession.java        # NEW
│
│   └── billing/                            # Extended
│       └── service/
│           ├── CapabilityService.java      # NEW — canWatch() / recordWatch()
│           └── CouponService.java          # NEW — validate + redeem
│
├── shared/
│   ├── config/
│   │   ├── SecurityConfig.java             # MODIFIED — split into 2 chains
│   │   └── AppProperties.java             # MODIFIED — add jwt.admin.* config block
│   ├── entity/
│   │   ├── RoleName.java                  # MODIFIED — remove ROLE_ADMIN, ROLE_SUPER_ADMIN
│   │   ├── SubscriptionPlan.java          # MODIFIED — add contentLimit field
│   │   ├── UserSubscription.java          # MODIFIED — add contentWatchesUsed field
│   │   ├── Coupon.java                    # NEW
│   │   └── CouponRedemption.java          # NEW
│   └── security/
│       └── jwt/
│           ├── JwtTokenProvider.java       # MODIFIED — add aud=user claim, sid claim
│           ├── JwtAuthenticationFilter.java# MODIFIED — extract sid for AuthProfileService
│           ├── AdminJwtTokenProvider.java  # NEW — aud=admin, separate secret
│           └── AdminJwtAuthenticationFilter.java # NEW — admin chain filter

src/main/resources/db/migration/
├── V1__initial_schema.sql                  # Existing — unchanged
├── V2__seed_roles.sql                      # Existing — unchanged
├── V3__add_admin_tables.sql                # NEW
├── V4__add_user_sessions.sql               # NEW
├── V5__add_subscription_tables.sql         # NEW
├── V6__add_coupons.sql                     # NEW
├── V7__add_subscription_fields.sql         # NEW
└── V8__remove_admin_roles_from_users.sql   # NEW

src/test/java/com/tinniestudio/backend/
├── admin/
│   ├── controller/AdminAuthControllerTest.java
│   └── service/AdminAuthServiceTest.java
├── auth/
│   ├── service/SessionServiceTest.java
│   ├── service/AuthProfileServiceTest.java
│   └── service/AuthServiceTest.java        # Extended with session tests
├── billing/
│   └── service/CapabilityServiceTest.java
└── integration/
    ├── AdminAuthIntegrationTest.java
    ├── SessionEnforcementIntegrationTest.java
    └── ContentQuotaIntegrationTest.java
```

**Structure Decision**: Single project (modular monolith). Admin auth is a new sub-module under `modules/auth/admin/`. User auth is reorganized under `modules/auth/user/` to mirror the admin structure. All shared infrastructure (JWT, security config, entities) lives in `shared/`.

---

## Complexity Tracking

No constitution violations detected. No complexity justification table required.

---

## Implementation Batches

The implementation is divided into ordered batches. Each batch must reach COMPLETE before the next starts.

---

### Batch 1: Database Foundation (FOUNDATION)

**Goal**: All new tables exist in the DB via Flyway. Entities are mapped. No service logic yet.

**Deliverables**:
- V3: `admins`, `admin_roles`, `admin_sessions` tables + triggers
- V4: `user_sessions` table + indexes
- V5: `subscription_plans`, `user_subscriptions` tables + seed data
- V6: `coupons`, `coupon_redemptions` tables
- V7: `content_limit` on `subscription_plans`, `content_watches_used` on `user_subscriptions`
- JPA entities: `Admin`, `AdminSession`, `UserSession`, `Coupon`, `CouponRedemption`
- Modified entities: `SubscriptionPlan.contentLimit`, `UserSubscription.contentWatchesUsed`
- Repositories: `AdminRepository`, `AdminSessionRepository`, `UserSessionRepository`, `CouponRepository`, `CouponRedemptionRepository`
- Modified enum: `RoleName` (remove admin roles — V8 migration deferred to Batch 6)

**Completion gates**:
- Flyway migrations run cleanly on a fresh DB
- All entities load without error on application startup
- Repository integration tests pass against Testcontainers PostgreSQL

---

### Batch 2: JWT & Security Config Refactor (SECURITY)

**Goal**: Dual filter chain in place. Admin tokens are cryptographically separate from user tokens. Existing user auth is unaffected.

**Deliverables**:
- `AppProperties`: add `jwt.admin.accessToken.*` and `jwt.admin.refreshToken.*` config blocks
- `AdminJwtTokenProvider`: issues/validates admin tokens with `aud=admin` and separate secrets
- `AdminJwtAuthenticationFilter`: extracts `admin_access_token` cookie, validates via `AdminJwtTokenProvider`
- `AdminUserDetailsServiceImpl`: loads `Admin` as `UserDetails` principal
- `SecurityConfig` split:
  - `AdminSecurityFilterChain` (`@Order(1)`): covers `/auth/admin/**`, uses `AdminJwtAuthenticationFilter`
  - `UserSecurityFilterChain` (`@Order(2)`): covers all other paths, unchanged behavior
- `JwtTokenProvider` enhanced: add `aud=user` claim and `sid` (sessionId) claim to both access and refresh tokens
- `JwtAuthenticationFilter` enhanced: extract `sid` claim from access token; make available to downstream services (via `SecurityContext` or request attribute)
- `CookieFactory`: add `clearAdminAuthCookies()` and `addAdminAuthCookies()` methods

**Completion gates**:
- User token rejected by admin endpoint (401 response verified by test)
- Admin token rejected by user endpoint (401 response verified by test)
- Existing user auth tests still pass
- `sid` claim present and extractable in access tokens

---

### Batch 3: Admin Auth Module (ADMIN)

**Goal**: Full admin CRUD auth flow operational — bootstrap, login, refresh, logout, me, register, password reset.

**Deliverables**:
- `AdminBootstrapService`: env-token-gated super admin creation with double guard (`AtomicBoolean` + DB check)
- `AdminAuthService`: login (revoke previous session, create AdminSession, issue tokens), refresh (validate AdminSession, rotate token), logout (revoke AdminSession), me, register sub-admin, forgot/reset password (stricter rules: 15-min token, invalidate on failure, revoke all sessions on success, email super admin)
- `AdminAuthController`: maps all 8 admin endpoints per contracts
- Rate limiting applied: 5/15m on login; 1/60m on forgot-password
- All endpoints documented in OpenAPI (SpringDoc)

**Completion gates**:
- Bootstrap endpoint creates super admin and disables itself (integration test)
- Second bootstrap attempt rejected (409)
- Admin login issues `admin_access_token` + `admin_refresh_token` cookies
- User token rejected on all admin endpoints
- Sub-admin creation requires SUPER_ADMIN role
- Admin password reset token invalidated on weak-password failure
- All admin tests pass (unit + integration)

---

### Batch 4: User Session & Device Enforcement (SECURITY + DOMAIN)

**Goal**: `UserSession` records are created on user login; device limits enforced; refresh is now stateful; force-logout operational.

**Deliverables**:
- `SessionService`:
  - `createSession(userId, refreshToken, request)`: creates `UserSession`, enforces plan device limit (evicts oldest), writes Redis key
  - `validateAndRotateSession(userId, sessionId, newRefreshToken)`: validates DB + Redis, rotates token hash + Redis TTL
  - `revokeSession(userId, sessionId, adminId)`: marks revoked in DB + deletes Redis key
  - `revokeAllUserSessions(userId, adminId)`: batch revoke
  - `getActiveSessions(userId)`: list for `/auth/me`
- `AuthService.login()`: calls `SessionService.createSession()` after successful auth; gets `sessionId` for token embedding
- `AuthService.refresh()`: calls `SessionService.validateAndRotateSession()` instead of pure JWT validation
- `AuthService.logout()`: calls `SessionService.revokeSession()`
- `UserSubscription` auto-created on registration with FREE plan

**Completion gates**:
- Free-plan user blocked from concurrent sessions (integration test)
- Rotated refresh token replay triggers full session revocation
- Redis key deleted on logout (verified via `CacheService`)
- Admin force-logout invalidates refresh token immediately
- All existing auth tests still pass

---

### Batch 5: `/auth/me` Aggregation & Content Quota (DOMAIN)

**Goal**: `/auth/me` returns enriched response; content quota gate operational.

**Deliverables**:
- `AuthProfileService.getProfile(userId, sessionId)`: aggregates `UserService`, `SubscriptionService`, `SessionService`; marks `current: true` on matching session
- `AuthProfileResponse` DTO with `subscription` and `devices` sub-objects
- `AuthController.me()`: updated to use `@AuthenticationPrincipal` and `AuthProfileService`; extracts `sessionId` from security context
- `CapabilityService`:
  - `canWatch(userId)`: checks Redis `tinnie:content_quota:{userId}` first, falls back to `UserSubscription.contentWatchesUsed` vs `plan.contentLimit`
  - `recordWatch(userId)`: increments `contentWatchesUsed` in DB + updates Redis cache
- `SubscriptionService.createFreeSubscription(userId)`: called on user registration

**Completion gates**:
- `/auth/me` returns all three data domains in one call
- `current: true` set on correct session
- Free-plan quota exhausted → subsequent stream request blocked with 403 + `reason: upgrade_required`
- Admin can set `contentLimit=0` and all free-tier content access is blocked
- No cross-domain repository injection (verified via code review gate)

---

### Batch 6: Coupon System (PAYMENT)

**Goal**: Coupon validation and redemption at checkout.

**Deliverables**:
- `CouponService`:
  - `validateCoupon(code, userId)`: 4-rule validation (active, date window, uses count, uniqueness) — returns specific reason on failure
  - `redeemCoupon(couponId, userId, subscriptionId)`: atomic increment + redemption record insert in `@Transactional`
- `Coupon` + `CouponRedemption` entities (from Batch 1) wired to service
- V8 migration: remove `ROLE_ADMIN` / `ROLE_SUPER_ADMIN` from `roles` + `user_roles` tables (runs last to avoid FK issues during transition)
- Error codes: `expired`, `already_used`, `not_found`, `limit_reached` mapped to 400 responses

**Completion gates**:
- Concurrent checkout with same coupon code — only one redemption succeeds (DB constraint test)
- All 4 invalid-coupon scenarios return correct error code
- Valid coupon correctly reduces checkout price
- V8 migration runs cleanly and removes admin roles from user table

---

## Completion Gate Summary (Constitution §VII)

| Gate | Checked In |
|------|-----------|
| `functionalValidation` | Each batch — happy path + all error cases |
| `securityValidation` | Batch 2 (filter chains) + Batch 3 (admin routes) + Batch 4 (session revocation) |
| `integrationValidation` | Batch 3 (bootstrap → login → refresh → logout) + Batch 4 (session enforcement) |
| `performanceValidation` | Batch 4 — Redis caching verified; no N+1 on session list; indexes applied |
| `rollbackReadiness` | All Flyway migrations verified on fresh DB; V8 has documented rollback path |
