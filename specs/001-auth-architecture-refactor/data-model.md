# Data Model: Multi-Actor Auth Architecture Refactor

**Phase**: 1 — Design
**Date**: 2026-05-29
**Branch**: `001-auth-architecture-refactor`

---

## New Entities

### Admin

Represents a platform administrator. Completely isolated from the `User` entity.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| `id` | UUID | PK, NOT NULL | `gen_random_uuid()` |
| `email` | VARCHAR(255) | NOT NULL, UNIQUE | Normalized lowercase |
| `passwordHash` | VARCHAR(255) | NOT NULL | BCrypt strength 12 |
| `firstName` | VARCHAR(100) | NULLABLE | |
| `lastName` | VARCHAR(100) | NULLABLE | |
| `accountStatus` | VARCHAR(20) | NOT NULL, DEFAULT 'ACTIVE' | Enum: ACTIVE, SUSPENDED |
| `passwordResetToken` | VARCHAR(255) | NULLABLE | One-time, 15-min expiry |
| `passwordResetTokenExpiry` | TIMESTAMPTZ | NULLABLE | |
| `passwordResetTokenInvalidated` | BOOLEAN | DEFAULT false | Invalidated on failed attempt |
| `createdAt` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |
| `updatedAt` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | Auto-updated via trigger |
| `deletedAt` | TIMESTAMPTZ | NULLABLE | Soft delete |

**Relationships**: Has many `AdminRole` (element collection), has many `AdminSession`.

---

### AdminRole (element collection on Admin)

| Field | Type | Constraints |
|-------|------|-------------|
| `adminId` | UUID | FK → admins(id) ON DELETE CASCADE |
| `role` | VARCHAR(50) | NOT NULL — values: SUPER_ADMIN, MODERATOR |

**Table**: `admin_roles`
**PK**: `(admin_id, role)`

---

### AdminSession

Tracks active admin sessions for revocation. Single active session per admin enforced at service layer.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| `id` | UUID | PK | `gen_random_uuid()` |
| `adminId` | UUID | NOT NULL, FK → admins(id) CASCADE | |
| `refreshTokenHash` | VARCHAR(255) | NOT NULL | BCrypt hash of refresh token |
| `ipAddress` | VARCHAR(45) | NULLABLE | IPv4 or IPv6 |
| `lastUsedAt` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | Updated on each refresh |
| `expiresAt` | TIMESTAMPTZ | NOT NULL | Set at session creation (now + 7d) |
| `revoked` | BOOLEAN | NOT NULL, DEFAULT false | |
| `revokedAt` | TIMESTAMPTZ | NULLABLE | |
| `createdAt` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

**Redis key**: `tinnie:admin:session:{adminId}:{sessionId}` (TTL = 7 days)

---

### UserSession

Tracks active user sessions with device information. Enforces plan-level device limits.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| `id` | UUID | PK | `gen_random_uuid()` |
| `userId` | UUID | NOT NULL, FK → users(id) CASCADE | |
| `refreshTokenHash` | VARCHAR(255) | NOT NULL | BCrypt hash of refresh token |
| `deviceFingerprint` | VARCHAR(64) | NULLABLE | SHA-256(UserAgent + "\|" + IP) as hex |
| `deviceName` | VARCHAR(255) | NULLABLE | "Chrome on macOS" derived from UA |
| `ipAddress` | VARCHAR(45) | NULLABLE | |
| `lastUsedAt` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | Updated on each refresh |
| `expiresAt` | TIMESTAMPTZ | NOT NULL | now() + 7 days |
| `revoked` | BOOLEAN | NOT NULL, DEFAULT false | |
| `revokedAt` | TIMESTAMPTZ | NULLABLE | |
| `revokedByAdminId` | UUID | NULLABLE, FK → admins(id) | Set when admin force-revokes |
| `createdAt` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | Used for oldest-session eviction ORDER |

**Indexes**:
- `idx_user_sessions_user_id` ON `user_sessions(user_id)`
- `idx_user_sessions_active` ON `user_sessions(user_id)` WHERE `revoked = false`

**Redis key**: `tinnie:session:{userId}:{sessionId}` (TTL = 7 days)

---

### Coupon

A discount code created by an admin for use at subscription checkout.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| `id` | UUID | PK | |
| `code` | VARCHAR(50) | NOT NULL, UNIQUE | Case-insensitive lookup |
| `discountType` | VARCHAR(20) | NOT NULL | Enum: PERCENTAGE, FIXED |
| `discountValue` | DECIMAL(10,2) | NOT NULL | |
| `currency` | VARCHAR(3) | NULLABLE | Required for FIXED type (e.g. CAD) |
| `maxUses` | INT | NULLABLE | null = unlimited |
| `usesCount` | INT | NOT NULL, DEFAULT 0 | Incremented atomically on redemption |
| `validFrom` | TIMESTAMPTZ | NULLABLE | null = no start restriction |
| `validUntil` | TIMESTAMPTZ | NULLABLE | null = no expiry |
| `isActive` | BOOLEAN | NOT NULL, DEFAULT true | Admin can deactivate |
| `createdByAdminId` | UUID | NULLABLE, FK → admins(id) | Audit trail |
| `createdAt` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

---

### CouponRedemption

Ensures one redemption per user per coupon. Links to the subscription created at upgrade.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| `id` | UUID | PK | |
| `couponId` | UUID | NOT NULL, FK → coupons(id) | |
| `userId` | UUID | NOT NULL, FK → users(id) | |
| `subscriptionId` | UUID | NOT NULL, FK → user_subscriptions(id) | |
| `redeemedAt` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | |

**Unique constraint**: `(coupon_id, user_id)` — enforced at DB level.

---

## Modified Entities

### RoleName (enum — breaking change)

```
BEFORE: ROLE_USER, ROLE_PARTNER, ROLE_ADMIN, ROLE_SUPER_ADMIN
AFTER:  ROLE_USER, ROLE_PARTNER
```

`ROLE_ADMIN` and `ROLE_SUPER_ADMIN` are removed from user roles. Admin roles live exclusively on the `Admin` entity via `AdminRoleName { SUPER_ADMIN, MODERATOR }`.

**Migration required**: Remove any existing `ROLE_ADMIN` / `ROLE_SUPER_ADMIN` rows from `roles` table and `user_roles` join table.

---

### SubscriptionPlan (additive)

New field:

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| `contentLimit` | INT | NULLABLE | null = unlimited; 2 for FREE; 0 = all blocked |

**Migration**: `ALTER TABLE subscription_plans ADD COLUMN content_limit INT;`

---

### UserSubscription (additive)

New field:

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| `contentWatchesUsed` | INT | NOT NULL, DEFAULT 0 | Incremented on stream request |

**Migration**: `ALTER TABLE user_subscriptions ADD COLUMN content_watches_used INT NOT NULL DEFAULT 0;`

**Auto-creation rule**: On user registration, a `UserSubscription` with `plan=FREE`, `status=ACTIVE`, `contentWatchesUsed=0`, no `endDate` is created.

---

### User (no structural changes)

The `User` entity remains unchanged structurally. The `Set<Role>` via `user_roles` join table is unchanged. No admin-related fields are added to `User`.

---

## JWT Token Claims

### User Access Token

| Claim | Value |
|-------|-------|
| `sub` | userId (UUID) |
| `aud` | `user` |
| `iss` | app issuer |
| `iat` | issued-at |
| `exp` | issued-at + 15m |
| `email` | user email |
| `roles` | `["ROLE_USER"]` |
| `provider` | `LOCAL` / `GOOGLE` |
| `sid` | sessionId (UUID) — NEW |

### User Refresh Token

| Claim | Value |
|-------|-------|
| `sub` | userId (UUID) |
| `aud` | `user` |
| `sid` | sessionId (UUID) |
| `iss` | app issuer |
| `iat` | issued-at |
| `exp` | issued-at + 7d |

### Admin Access Token

| Claim | Value |
|-------|-------|
| `sub` | adminId (UUID) |
| `aud` | `admin` |
| `iss` | app issuer |
| `iat` | issued-at |
| `exp` | issued-at + 15m |
| `email` | admin email |
| `roles` | `["SUPER_ADMIN"]` |
| `sid` | adminSessionId (UUID) |

### Admin Refresh Token

| Claim | Value |
|-------|-------|
| `sub` | adminId (UUID) |
| `aud` | `admin` |
| `sid` | adminSessionId (UUID) |
| `iss` | app issuer |
| `iat` | issued-at |
| `exp` | issued-at + 7d |

---

## Cookie Names

| Cookie | Scope | Notes |
|--------|-------|-------|
| `access_token` | User | Existing — unchanged |
| `refresh_token` | User | Existing — unchanged |
| `admin_access_token` | Admin | New |
| `admin_refresh_token` | Admin | New |

---

## Redis Key Namespace (Constitution §VI compliant)

| Key Pattern | TTL | Purpose |
|-------------|-----|---------|
| `tinnie:session:{userId}:{sessionId}` | 7 days | User session existence flag |
| `tinnie:admin:session:{adminId}:{sessionId}` | 7 days | Admin session existence flag |
| `tinnie:content_quota:{userId}` | no TTL | Content watch count (fast read) |

---

## Flyway Migration Order

| Migration | Description |
|-----------|-------------|
| V3 | Add `admins`, `admin_roles`, `admin_sessions` tables |
| V4 | Add `user_sessions` table |
| V5 | Add `subscription_plans`, `user_subscriptions` tables + seed FREE/SILVER/GOLD plans |
| V6 | Add `coupons`, `coupon_redemptions` tables |
| V7 | Add `content_limit` to `subscription_plans`; add `content_watches_used` to `user_subscriptions` |
| V8 | Remove `ROLE_ADMIN` / `ROLE_SUPER_ADMIN` from `roles` + `user_roles`; seed `ROLE_PARTNER` |

All migrations are additive except V7 (destructive removal of admin roles from user table). V7 must run after admin module is fully in place.
