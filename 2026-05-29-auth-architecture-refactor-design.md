# TinnieStudio Auth Architecture Refactor — Design Spec

**Date:** 2026-05-29
**Status:** Approved
**Scope:** Multi-actor auth, RBAC, subscription-aware session management, admin isolation, device/session orchestration, refresh token governance, /auth/me aggregation, super admin bootstrap

---

## 1. Context & Current State

### Technology Stack
- **Framework:** Spring Boot 3.x (Java)
- **Database:** PostgreSQL with Flyway migrations
- **Cache:** Redis (Lettuce, connection pool)
- **Auth:** JWT (HTTP-only cookies) + OAuth2 (Google)

### What Already Exists
- Stateless JWT auth with HTTP-only cookies
- OAuth2 (Google) with account linking to LOCAL users
- User roles via JPA join table (`user_roles`)
- Email verification and password reset
- Rate limiting via Redis
- Modular Spring Boot architecture (`modules/auth/`, `modules/user/`, `modules/billing/`)
- `SubscriptionPlan` and `UserSubscription` entities (scaffolded, not integrated)

### Critical Gaps Being Addressed
- No separate Admin entity — admin is just a role on the User table
- No session or device tracking — fully stateless, no revocation
- No refresh token revocation capability
- No subscription-aware auth (plans exist but unused)
- No enriched `/auth/me` — only returns basic identity
- No device limit enforcement
- No coupon system

---

## 2. Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Admin isolation | Separate `modules/auth/admin/` module | Security boundary requirement; compromising user auth must not expose admin |
| Session enforcement | Revoke oldest session when device limit exceeded | Better UX than deny; user not blocked on new device |
| Session storage | Hybrid (DB primary + Redis cache) | Consistency + availability; Redis for fast revocation check, DB for audit/persistence |
| Super admin bootstrap | Environment bootstrap token | One-time, clean, no migration coupling |
| `/auth/me` aggregation | Projection aggregator service | Clean domain boundaries; each service owns its data slice |
| Free tier content limit | Lifetime limit, admin-configurable | Simple to reason about; admin can disable free tier by setting limit to 0 |

---

## 3. Module Structure

```
modules/auth/
  user/
    controller/
      AuthController.java          # @RequestMapping("/auth") — all user endpoints
    service/
      AuthService.java             # login, register, refresh, logout
      OAuth2Service.java           # Google OAuth (existing, unchanged)
      SessionService.java          # NEW — device tracking, limit enforcement
      AuthProfileService.java      # NEW — /auth/me aggregation
    dto/
      AuthProfileResponse.java     # NEW — enriched /auth/me response
      SessionDto.java              # NEW — device info
      RegisterRequest.java         # existing
      LoginRequest.java            # existing
      AuthResponse.java            # existing
      UserResponseDto.java         # existing
      VerifyEmailResponse.java     # existing
      ResendVerificationEmailRequest.java
      ForgotPasswordRequest.java
      ResetPasswordRequest.java

  admin/
    controller/
      AdminAuthController.java     # NEW — @RequestMapping("/auth/admin")
    service/
      AdminAuthService.java        # NEW — admin login, refresh, logout, register
      AdminBootstrapService.java   # NEW — super admin creation via env token
    dto/
      AdminLoginRequest.java
      AdminRegisterRequest.java
      AdminAuthResponse.java
```

---

## 4. REST Endpoints

### User Auth (all existing paths preserved — no frontend breakage)

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/auth/register` | Public | Unchanged |
| POST | `/auth/login` | Public | Unchanged + creates UserSession |
| POST | `/auth/refresh` | Public | Now validates against UserSession |
| POST | `/auth/logout` | Required | Now revokes UserSession |
| GET  | `/auth/me` | Required | **Enhanced response** (additive, non-breaking) |
| GET  | `/auth/verify-email` | Public | Unchanged |
| POST | `/auth/resend-verification-email` | Public | Unchanged |
| POST | `/auth/forgot-password` | Public | Unchanged |
| PATCH | `/auth/reset-password` | Public | Unchanged |
| GET  | `/auth/oauth2/authorize/google` | Public | Unchanged |

### Admin Auth (all new)

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/auth/admin/bootstrap` | Public | One-time, env-gated |
| POST | `/auth/admin/login` | Public | Rate-limited |
| POST | `/auth/admin/logout` | Admin JWT | Revokes admin session |
| POST | `/auth/admin/refresh` | Public | Validates against AdminSession |
| GET  | `/auth/admin/me` | Admin JWT | Admin profile |
| POST | `/auth/admin/register` | SUPER_ADMIN | Create sub-admin |
| POST | `/auth/admin/forgot-password` | Public | 1 req/60 min/IP |
| PATCH | `/auth/admin/reset-password` | Public | 15 min token, invalidate on failure |

---

## 5. Entity & Database Schema

### 5.1 New: `admins` Table

```sql
CREATE TABLE admins (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    first_name     VARCHAR(100),
    last_name      VARCHAR(100),
    account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, SUSPENDED
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMP
);

CREATE TABLE admin_roles (
    admin_id UUID   NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    role     VARCHAR(50) NOT NULL,  -- SUPER_ADMIN, MODERATOR
    PRIMARY KEY (admin_id, role)
);
```

### 5.2 New: `user_sessions` Table

```sql
CREATE TABLE user_sessions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash   VARCHAR(255) NOT NULL,
    device_fingerprint   VARCHAR(64),    -- SHA-256(UserAgent + IP)
    device_name          VARCHAR(255),   -- "Chrome on macOS"
    ip_address           VARCHAR(45),
    last_used_at         TIMESTAMP NOT NULL DEFAULT now(),
    expires_at           TIMESTAMP NOT NULL,
    revoked              BOOLEAN NOT NULL DEFAULT false,
    revoked_at           TIMESTAMP,
    revoked_by_admin_id  UUID REFERENCES admins(id),
    created_at           TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_sessions_user_id ON user_sessions(user_id);
CREATE INDEX idx_user_sessions_active  ON user_sessions(user_id) WHERE revoked = false;
```

### 5.3 New: `admin_sessions` Table

```sql
CREATE TABLE admin_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id            UUID NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    refresh_token_hash  VARCHAR(255) NOT NULL,
    ip_address          VARCHAR(45),
    last_used_at        TIMESTAMP NOT NULL DEFAULT now(),
    expires_at          TIMESTAMP NOT NULL,
    revoked             BOOLEAN NOT NULL DEFAULT false,
    revoked_at          TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);
```

### 5.4 New: `coupons` Table

```sql
CREATE TABLE coupons (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code             VARCHAR(50) NOT NULL UNIQUE,
    discount_type    VARCHAR(20) NOT NULL,   -- PERCENTAGE, FIXED
    discount_value   DECIMAL(10,2) NOT NULL,
    currency         VARCHAR(3),             -- for FIXED type (e.g. CAD)
    max_uses         INT,                    -- null = unlimited
    uses_count       INT NOT NULL DEFAULT 0,
    valid_from       TIMESTAMP,
    valid_until      TIMESTAMP,
    is_active        BOOLEAN NOT NULL DEFAULT true,
    created_by_admin_id UUID REFERENCES admins(id),
    created_at       TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE coupon_redemptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coupon_id       UUID NOT NULL REFERENCES coupons(id),
    user_id         UUID NOT NULL REFERENCES users(id),
    subscription_id UUID NOT NULL REFERENCES user_subscriptions(id),
    redeemed_at     TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (coupon_id, user_id)  -- one coupon per user
);
```

### 5.5 Changes to Existing Entities

**`User` entity** — no structural changes. `Set<Role>` via `user_roles` join table unchanged.

**`RoleName` enum** — trim to user-only values:
```java
public enum RoleName {
    ROLE_USER,
    ROLE_PARTNER
    // ROLE_ADMIN and ROLE_SUPER_ADMIN removed — moved to AdminRoleName
}
```

**`SubscriptionPlan`** — add `contentLimit` field:
```sql
ALTER TABLE subscription_plans ADD COLUMN content_limit INT;
-- FREE tier: 2 (admin-editable), paid tiers: null (unlimited)
```

**`UserSubscription`** — add `contentWatchesUsed` field:
```sql
ALTER TABLE user_subscriptions ADD COLUMN content_watches_used INT NOT NULL DEFAULT 0;
```

---

## 6. RBAC

### User Roles
```java
public enum RoleName { ROLE_USER, ROLE_PARTNER }
```
Stored via JPA `@ManyToMany` → `user_roles` join table. Multiple roles supported per user.

### Admin Roles
```java
public enum AdminRoleName { SUPER_ADMIN, MODERATOR }
```
Stored via `@ElementCollection` on `Admin` entity → `admin_roles` table.

### JWT Separation

| Token type | `aud` claim | Secret env var | Cookie names |
|---|---|---|---|
| User access token | `user` | `JWT_USER_SECRET` | `access_token` |
| User refresh token | `user` | `JWT_USER_SECRET` | `refresh_token` |
| Admin access token | `admin` | `JWT_ADMIN_SECRET` | `admin_access_token` |
| Admin refresh token | `admin` | `JWT_ADMIN_SECRET` | `admin_refresh_token` |

`JwtAuthenticationFilter` routes to user or admin principal validation based on request path prefix (`/auth/admin/**` → admin validation) and `aud` claim. A user token can never grant access to admin endpoints.

### Authorization Rules
```java
// Admin endpoints
@PreAuthorize("hasRole('SUPER_ADMIN')")              // bootstrap, register
@PreAuthorize("hasAnyRole('SUPER_ADMIN','MODERATOR')") // general admin access

// Single super admin constraint (enforced in service layer)
if (adminRepository.existsByRolesContaining(SUPER_ADMIN)) {
    throw new SuperAdminAlreadyExistsException();
}
```

---

## 7. Super Admin Bootstrap

**Flow:**
1. Set `ADMIN_BOOTSTRAP_TOKEN=<secure-random>` in environment before first deploy
2. `POST /auth/admin/bootstrap` with `{ "bootstrapToken": "...", "email": "...", "password": "..." }`
3. `AdminBootstrapService` checks:
   - Token matches env var `ADMIN_BOOTSTRAP_TOKEN`
   - No SUPER_ADMIN exists yet
4. Creates Admin with `SUPER_ADMIN` role
5. Endpoint disabled after first successful use (flag in application state or env var removal)

Bootstrap token must be a cryptographically random value (minimum 32 chars). After use, remove `ADMIN_BOOTSTRAP_TOKEN` from environment to close the endpoint permanently.

---

## 8. Session & Device Orchestration

### On Login
```
1. Load UserSubscription → get plan.maxDevices (default: 1 for FREE)
2. Count active (non-revoked) sessions for user in DB
3. If count >= maxDevices:
     → Find oldest session (ORDER BY created_at ASC LIMIT 1)
     → Mark revoked=true in DB
     → Delete Redis key: session:{userId}:{sessionId}
4. Hash new refresh token (BCrypt)
5. Write UserSession to DB (device fingerprint, device name, IP, expires_at)
6. Write Redis key: session:{userId}:{sessionId} → TTL = 7 days
7. Embed sessionId in refresh token claims
```

### On Refresh
```
1. Extract sessionId from refresh token claims
2. Redis lookup: session:{userId}:{sessionId} → miss = 401
3. DB lookup: revoked=false AND hash matches → mismatch = 401 + revoke session
4. Issue new access token
5. Rotate refresh token: new hash, update DB + reset Redis TTL
```

Note: Access token validation is **stateless** — `JwtAuthenticationFilter` checks signature + expiry only. No DB/Redis lookup on every request.

### On Logout
```
1. Revoke UserSession in DB (revoked=true)
2. Delete Redis key
3. Clear auth cookies
```

### Plan Device Limits

| Plan | maxDevices |
|------|-----------|
| FREE | 1 |
| SILVER | 1 |
| GOLD | 3 |
| Admin override | per-user configurable |

### Device Fingerprint
SHA-256 hash of `User-Agent + IP`, stored as hex. **Used for display only** (`deviceName` in `/auth/me` sessions list — e.g. "Chrome on macOS"). Never used for security decisions — a new or changed fingerprint never blocks a login or refresh.

### Admin Force-Logout
```java
sessionService.revokeAllUserSessions(userId, adminId)    // global logout
sessionService.revokeSession(userId, sessionId, adminId) // single device
```
Access tokens already issued remain valid up to 15 min (accepted tradeoff).

### Redis Key Design
```
session:{userId}:{sessionId}          → user sessions (TTL = 7 days)
admin:session:{adminId}:{sessionId}   → admin sessions (TTL = 7 days)
content_quota:{userId}                → watch count cache (no TTL)
```

---

## 9. Refresh Token Governance

**Token rotation on every refresh** — old refresh token is invalidated when new one is issued. Replay of a rotated token triggers immediate session revocation (stolen token detection via hash mismatch).

**Admin password reset — stricter rules vs user:**

| Rule | User | Admin |
|------|------|-------|
| Token expiry | 1 hour | 15 minutes |
| Rate limit | 3 req / 30 min / IP | 1 req / 60 min / IP |
| Failed attempt | token stays valid | token invalidated if token is valid but request fails (e.g. weak password) — invalid/malformed tokens return 400 without wiping valid token |
| On success | session cleared | all active admin sessions revoked |
| Audit log | no | yes |
| Super admin notified | no | yes — email alert on any reset request |

**Admin session policy** — single active session per admin. New login revokes previous admin session automatically.

---

## 10. Free Tier Content Quota

- **Limit:** 2 content stream requests (lifetime, for new users)
- **Counter:** `UserSubscription.contentWatchesUsed` (DB) + `content_quota:{userId}` (Redis, fast path)
- **Trigger:** Incremented immediately on stream request (not on completion)
- **Gate:** `CapabilityService.canWatch(userId)` called by content/streaming service before granting stream
- **Configurable:** `SubscriptionPlan.contentLimit` — admin-editable via admin dashboard
- **Env fallback:** `FREE_TIER_CONTENT_LIMIT` env var as default if plan value not set
- **When limit hit:** `403` with `reason: "upgrade_required"` — frontend prompts upgrade
- **Admin can set to 0** to effectively disable free tier content access

Auto-assignment on registration:
```
User created → create UserSubscription(plan=FREE, status=ACTIVE, contentWatchesUsed=0, no endDate)
```

---

## 11. `/auth/me` Projection Aggregator

`AuthProfileService` composes from three domain services:
```java
AuthProfileResponse getProfile(UUID userId) {
    User user                  = userService.getById(userId);
    UserSubscription sub       = subscriptionService.getActiveSubscription(userId);
    List<SessionDto> sessions  = sessionService.getActiveSessions(userId);
    return AuthProfileResponse.of(user, sub, sessions);
}
```

**Response shape (additive — existing fields preserved):**
```json
{
  "userId": "...",
  "email": "...",
  "firstName": "...",
  "lastName": "...",
  "displayName": "...",
  "avatarUrl": "...",
  "roles": ["ROLE_USER"],
  "provider": "LOCAL",
  "emailVerified": true,
  "subscription": {
    "plan": "FREE",
    "status": "ACTIVE",
    "maxDevices": 1,
    "contentWatchesUsed": 1,
    "contentWatchesLimit": 2,
    "canWatch": true,
    "expiresAt": null
  },
  "devices": {
    "active": 1,
    "max": 1,
    "sessions": [
      {
        "sessionId": "...",
        "deviceName": "Chrome on macOS",
        "ipAddress": "...",
        "lastUsedAt": "...",
        "current": true
      }
    ]
  }
}
```

`current: true` flags the session matching the current request's session ID.

Controller updated to use `@AuthenticationPrincipal` — no inline JWT parsing:
```java
@GetMapping("/me")
public ResponseEntity<AuthProfileResponse> me(@AuthenticationPrincipal UserDetails userDetails) {
    UUID userId = UUID.fromString(userDetails.getUsername());
    return ResponseEntity.ok(authProfileService.getProfile(userId));
}
```

---

## 12. Coupon System

### Validation Rules
1. Coupon `is_active = true`
2. Current time within `valid_from` → `valid_until` window
3. `uses_count < max_uses` (or `max_uses` is null)
4. No existing `coupon_redemptions` record for this user + coupon

### Application Flow (at checkout/upgrade)
```
1. User submits code at plan selection
2. Validate against all 4 rules above
3. Apply discount → calculate final charge
4. On successful payment: create UserSubscription + increment uses_count + insert redemption
5. Invalid → 400 with specific reason: "expired" | "already_used" | "not_found" | "limit_reached"
```

### Admin Controls
- Create, deactivate, and view redemption stats for coupons via admin dashboard
- Coupons created by admin — `created_by_admin_id` tracked for audit

---

## 13. Out of Scope (This Spec)

- WebSocket session invalidation (noted in CLAUDE.md, deferred)
- Suspicious login tracking / analytics
- Admin session analytics dashboard
- Content/streaming service implementation (only the quota gate contract is defined here)
- Stripe / payment processor integration (coupon flow defines the contract, not the payment implementation)
