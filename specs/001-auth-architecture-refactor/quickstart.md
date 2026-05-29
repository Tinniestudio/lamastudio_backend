# Quickstart: Multi-Actor Auth Architecture Refactor

**For**: Developers onboarding to this feature branch
**Branch**: `001-auth-architecture-refactor`
**Date**: 2026-05-29

---

## What This Feature Does

This refactor evolves the TinnieStudio backend from a simple stateless JWT auth system into a multi-actor, session-aware security architecture with:

- A completely isolated admin auth system (separate entity, tokens, cookies, endpoints)
- Device/session tracking with subscription-aware device limits
- Refresh token rotation with replay detection
- An enriched `/auth/me` response aggregating subscription and session data
- A free-tier content quota gate
- A coupon validation and redemption system

---

## Key Files Changed / Created

### New: Admin Auth Module
```
src/main/java/com/lamastudio/backend/modules/auth/admin/
├── controller/AdminAuthController.java     # /auth/admin/** endpoints
├── service/
│   ├── AdminAuthService.java               # Login, logout, refresh, register
│   └── AdminBootstrapService.java          # One-time super admin creation
├── dto/
│   ├── AdminLoginRequest.java
│   ├── AdminRegisterRequest.java
│   └── AdminAuthResponse.java
└── entity/
    ├── Admin.java
    ├── AdminRoleName.java (enum)
    └── AdminSession.java
```

### New: User Session Module
```
src/main/java/com/lamastudio/backend/modules/auth/user/service/
├── SessionService.java                     # Create, revoke, query sessions
└── AuthProfileService.java                 # /auth/me aggregation
src/main/java/com/lamastudio/backend/modules/auth/user/dto/
├── AuthProfileResponse.java                # Enriched /auth/me response
└── SessionDto.java                         # Device info in session list
src/main/java/com/lamastudio/backend/modules/auth/user/entity/
└── UserSession.java
```

### New: Capability & Coupon
```
src/main/java/com/lamastudio/backend/modules/billing/service/
├── CapabilityService.java                  # canWatch() / recordWatch()
└── CouponService.java                      # Validate + redeem coupons
src/main/java/com/lamastudio/backend/shared/entity/
├── Coupon.java
└── CouponRedemption.java
```

### Modified: JWT & Security
```
src/main/java/com/lamastudio/backend/shared/security/jwt/
├── JwtTokenProvider.java                   # Adds aud=user, sid claim
├── AdminJwtTokenProvider.java              # NEW — aud=admin, separate secret
├── JwtAuthenticationFilter.java            # Adds sid extraction for /auth/me
└── AdminJwtAuthenticationFilter.java       # NEW — admin chain filter
src/main/java/com/lamastudio/backend/shared/config/
└── SecurityConfig.java                     # Split into 2 SecurityFilterChains
```

### New: Flyway Migrations
```
src/main/resources/db/migration/
├── V3__add_admin_tables.sql
├── V4__add_user_sessions.sql
├── V5__add_coupons.sql
├── V6__add_subscription_fields.sql
└── V7__remove_admin_roles_from_users.sql
```

---

## Environment Variables Required

```bash
# Existing
JWT_ACCESS_TOKEN_SECRET=<base64-encoded-secret>
JWT_REFRESH_TOKEN_SECRET=<base64-encoded-secret>

# New — Admin JWT (separate secrets)
JWT_ADMIN_ACCESS_SECRET=<base64-encoded-secret>
JWT_ADMIN_REFRESH_SECRET=<base64-encoded-secret>

# New — Bootstrap (remove after first use)
ADMIN_BOOTSTRAP_TOKEN=<cryptographically-random-32+-chars>

# Optional — Free tier default
FREE_TIER_CONTENT_LIMIT=2
```

---

## Local Development Flow

1. Pull the branch: `git checkout 001-auth-architecture-refactor`
2. Add the new env vars to your `.env` / `application-local.yml`
3. Start the app — Flyway will auto-run V3–V6 migrations
4. Bootstrap the super admin:
   ```bash
   curl -X POST http://localhost:8080/auth/admin/bootstrap \
     -H "Content-Type: application/json" \
     -d '{"bootstrapToken":"<ADMIN_BOOTSTRAP_TOKEN>","email":"admin@example.com","password":"SecurePass123!"}'
   ```
5. Log in as admin and verify the `admin_access_token` cookie is set
6. Remove `ADMIN_BOOTSTRAP_TOKEN` from your environment (or set it to empty string)

---

## Testing the Key Scenarios

### Device limit enforcement (Free plan)
1. Register a new user
2. Log in from "browser 1" — note the `Set-Cookie` headers
3. Log in from "browser 2" (use a different UA or tool)
4. Try to refresh from "browser 1" — should receive 401

### Refresh token replay detection
1. Log in, capture the `refresh_token` cookie value (via devtools)
2. Call `POST /auth/refresh` — get new tokens
3. Manually re-submit the original refresh token to `POST /auth/refresh`
4. Should receive 401 and all sessions should be revoked

### `/auth/me` enriched response
1. Log in, call `GET /auth/me`
2. Verify `subscription`, `devices.sessions`, and `devices.sessions[0].current: true` are present

---

## Architecture Decision Notes

- Access token validation is **always stateless** — no DB or Redis lookup per request
- Refresh token validation is **always stateful** — Redis fast-path, DB fallback
- Admin and user filter chains are completely independent — a misconfigured user token never reaches admin route handlers
- All cache access goes through `CacheService` interface — no direct `RedisTemplate` injection in domain services
- All config goes through `@ConfigurationProperties` — no `@Value` or `System.getenv()` in service classes
