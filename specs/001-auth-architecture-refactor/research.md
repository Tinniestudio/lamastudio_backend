# Research: Multi-Actor Auth Architecture Refactor

**Phase**: 0 — Research & Unknowns Resolution
**Date**: 2026-05-29
**Branch**: `001-auth-architecture-refactor`

---

## 1. Dual Security Filter Chain (Admin vs User)

**Decision**: Use two `SecurityFilterChain` beans ordered with `@Order` — `@Order(1)` handles `/auth/admin/**` with admin JWT validation; `@Order(2)` handles all other paths with user JWT validation.

**Rationale**: Spring Security supports multiple filter chains via `SecurityFilterChain` bean ordering. The first matching chain wins. Admin paths are explicitly isolated at the chain level, not just at the role-check level — a user token submitted to an admin endpoint is rejected before the authorization check ever runs.

**How**: Add `AdminJwtAuthenticationFilter` (equivalent of the current `JwtAuthenticationFilter` but using `AdminJwtTokenProvider` + `AdminUserDetailsServiceImpl`). Register it in `AdminSecurityFilterChain` at `@Order(1)`.

**Alternatives considered**:
- Single chain with audience claim check in `JwtAuthenticationFilter` — rejected because a misconfiguration in the claim check could silently allow cross-audience token acceptance.
- Two separate Spring Security applications — overkill for a monolith; adds deployment complexity with no benefit.

---

## 2. JWT Token Separation (Audience + Secret)

**Decision**: Issue admin tokens with `aud: "admin"` claim signed by `JWT_ADMIN_SECRET`; user tokens with `aud: "user"` claim signed by `JWT_USER_SECRET`. Validation always requires the correct secret for the corresponding chain.

**Rationale**: Separate secrets provide cryptographic isolation — even if one secret leaks, the other chain is unaffected. The existing `JwtTokenProvider` needs an `AdminJwtTokenProvider` sibling that uses `appProperties.getJwt().getAdmin()` config block.

**Implementation note**: `AppProperties` will need a new `jwt.admin.accessToken.secret`, `jwt.admin.accessToken.expirationMs`, `jwt.admin.refreshToken.secret`, `jwt.admin.refreshToken.expirationMs` config block. All values via `@ConfigurationProperties` — no `@Value` or `System.getenv()`.

**Alternatives considered**:
- Shared secret with audience-only separation — rejected because the constitution requires cryptographic separation (Constitution §X).

---

## 3. Session Storage: Hybrid DB + Redis

**Decision**: `user_sessions` / `admin_sessions` tables are the authoritative store. Redis (`tinnie:session:{userId}:{sessionId}`) is the fast-path revocation cache with TTL=7 days. On refresh: check Redis first (miss → 401), then verify DB hash. On revocation: mark DB + delete Redis key atomically in a service method.

**Rationale**: Redis-only session storage risks data loss on Redis restart/flush (sessions appear valid again). DB-only is too slow for high-traffic refresh checks. Hybrid gives both speed and durability.

**Redis key naming** (conforming to Constitution §VI namespace convention):
```
tinnie:session:{userId}:{sessionId}          → user sessions
tinnie:admin:session:{adminId}:{sessionId}   → admin sessions
tinnie:content_quota:{userId}               → watch count cache
```

**Implementation note**: All Redis access goes through `CacheService` interface (Constitution §VI). No direct `RedisTemplate` injection into domain services.

**Alternatives considered**:
- Redis-only — rejected (durability risk on restart).
- DB-only — rejected (N queries per refresh at scale).

---

## 4. Refresh Token Hashing

**Decision**: Store BCrypt hash of the refresh token JWT string in the `refresh_token_hash` column. On refresh, BCrypt-verify the submitted token against the stored hash.

**Rationale**: Storing plaintext refresh tokens in the DB is a security risk if the DB is compromised. BCrypt is already the project's password hashing standard (BCryptPasswordEncoder strength 12). Using the same encoder for refresh token hashing ensures consistency.

**Performance note**: BCrypt verification is intentionally slow (~250ms). This is acceptable for refresh (infrequent operation). Access token validation remains stateless (no BCrypt involved).

**Alternatives considered**:
- SHA-256 hash — faster but not salted, vulnerable to rainbow table attacks on the token space. BCrypt is safer and already available.
- HMAC-SHA256 with a server secret — acceptable alternative but adds key management complexity. BCrypt is simpler given existing infrastructure.

---

## 5. Device Fingerprinting

**Decision**: SHA-256 of concatenated `User-Agent + "|" + IP` address, stored as hex string (64 chars). Displayed as a human-readable label (`deviceName`) via UA parsing (e.g., "Chrome on macOS").

**Rationale**: Fingerprint is used only for session list display — the constitution explicitly forbids using fingerprint for security decisions (Constitution §X: "never used for security decisions"). UA parsing can use `nl.basjes.parse.useragent:yauaa` or a simple regex-based parser; a lightweight utility class is sufficient.

**Alternatives considered**:
- Full UA string storage — too long, PII risk.
- No fingerprint — makes the session list useless from a UX perspective.

---

## 6. Super Admin Bootstrap Strategy

**Decision**: Environment-variable-gated one-time endpoint (`POST /auth/admin/bootstrap`). After the first successful call, an in-memory `AtomicBoolean bootstrapUsed` flag (and DB check `adminRepository.existsByRolesContaining(SUPER_ADMIN)`) prevents re-use. Removing `ADMIN_BOOTSTRAP_TOKEN` from the environment also disables it.

**Rationale**: Avoids migration coupling (no hardcoded credentials in Flyway scripts). Simple, auditable, and self-disabling. The `AdminBootstrapService` checks both the flag and the DB state — double guard against race conditions.

**Alternatives considered**:
- Flyway data migration seeding — rejected because it puts credentials in version-controlled SQL files.
- Admin UI wizard — too complex for a backend-first bootstrap.

---

## 7. `contentWatchesUsed` Increment Strategy

**Decision**: Increment the counter at stream request start (not completion), using a DB update + Redis cache invalidation pattern. `CapabilityService.canWatch(userId)` checks Redis fast path first, falls back to DB.

**Rationale**: Incrementing on start prevents users from aborting streams to avoid quota consumption. Redis cache (`tinnie:content_quota:{userId}`) stores the current count for fast checking — invalidated on any write. Falls back to DB on cache miss.

**Alternatives considered**:
- Increment on completion — too easy to game by aborting early.
- DB-only check on every request — acceptable but slower at scale.

---

## 8. Coupon Atomicity

**Decision**: Coupon redemption uses a DB-level `UNIQUE (coupon_id, user_id)` constraint + optimistic locking on `uses_count`. Service executes: validate → increment uses_count → insert redemption in a single `@Transactional` block. DB constraint catches concurrent duplicate attempts.

**Rationale**: Without DB-level uniqueness, concurrent checkout requests for the same user+coupon pair could both succeed. The `UNIQUE` constraint on `coupon_redemptions(coupon_id, user_id)` is the final guard.

**Alternatives considered**:
- Distributed lock via Redis — adds complexity; DB constraint is simpler and sufficient.
- Pessimistic locking on coupon row — deadlock risk under high load.

---

## 9. `/auth/me` Aggregation Pattern

**Decision**: `AuthProfileService` composes data from `UserService`, `SubscriptionService`, and `SessionService` via interface calls only — no cross-domain repository injection. The session list comes from `SessionService.getActiveSessions(userId)`, with the `current: true` flag set by comparing each session's ID against the session ID embedded in the current request's JWT claims.

**Rationale**: Constitution §IV prohibits cross-domain repository injection. Each service returns a DTO; `AuthProfileService` assembles the `AuthProfileResponse`. The `sessionId` claim is added to the access token at login.

**Implementation note**: `sessionId` must be embedded as a JWT claim (`sid`) at token issuance time so `AuthProfileService` can compare it against the session list without a separate lookup.

**Alternatives considered**:
- GraphQL-style sub-resolver — overkill for a single aggregation endpoint.
- Single fat query joining all tables — violates domain ownership rule.

---

## 10. Admin Password Reset Stricter Rules

**Decision**: Admin password reset token stored in `admin_sessions`-adjacent table OR as fields on the `Admin` entity (same pattern as user `password_reset_token` + `password_reset_token_expiry`). Token invalidated on any failed attempt (including weak password). On success: revoke all active admin sessions. Super admin receives email alert.

**Rationale**: Higher stakes for admin credentials requires stricter token governance than user password reset. The email alert to super admin follows the pattern of the existing `EmailService` / `ResendEmailService`.

---

## Resolved Unknowns Summary

| # | Unknown | Resolution |
|---|---------|-----------|
| 1 | Admin/user JWT filter separation | Dual `SecurityFilterChain` at `@Order(1)` / `@Order(2)` |
| 2 | Admin JWT secret | Separate `JWT_ADMIN_SECRET` + `aud: "admin"` claim |
| 3 | Session storage backend | Hybrid: DB primary + Redis fast-path |
| 4 | Refresh token storage | BCrypt hash in DB |
| 5 | Device fingerprint scope | Display-only SHA-256, never security decision |
| 6 | Super admin bootstrap | Env-var-gated one-time endpoint + double guard |
| 7 | Content quota increment | On stream start, Redis cache + DB |
| 8 | Coupon atomicity | DB UNIQUE constraint + single `@Transactional` |
| 9 | `/auth/me` assembly | `AuthProfileService` via service interfaces, `sid` in JWT |
| 10 | Admin password reset | Token invalidated on failure, email alert on any reset |
