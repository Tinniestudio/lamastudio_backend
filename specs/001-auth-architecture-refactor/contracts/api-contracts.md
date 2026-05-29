# API Contracts: Multi-Actor Auth Architecture Refactor

**Phase**: 1 — Design
**Date**: 2026-05-29
**Envelope format**: All responses use `{ success, data, error }` per Constitution §IX

---

## User Auth Endpoints (Preserved — No Breaking Changes)

### POST /auth/register
*Unchanged. Adds side-effect: creates FREE UserSubscription on success.*

### POST /auth/login
*Signature unchanged. Adds side-effect: creates UserSession, enforces device limit.*

**Changed behavior**: Sets `sid` claim in both issued tokens. Evicts oldest session if plan limit exceeded.

### POST /auth/refresh
*Signature unchanged. Now validates against UserSession (DB + Redis) instead of pure JWT.*

**Changed behavior**: Returns 401 if session is revoked or not found in store. Rotates refresh token and updates session hash.

### POST /auth/logout
*Signature unchanged. Now revokes UserSession in DB + Redis in addition to clearing cookies.*

### GET /auth/me
*Path unchanged. Response is now enriched (additive — no removed fields).*

**New response shape**:
```json
{
  "success": true,
  "data": {
    "userId": "uuid",
    "email": "user@example.com",
    "firstName": "Jane",
    "lastName": "Doe",
    "displayName": "Jane Doe",
    "avatarUrl": null,
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
          "sessionId": "uuid",
          "deviceName": "Chrome on macOS",
          "ipAddress": "192.168.1.1",
          "lastUsedAt": "2026-05-29T10:00:00Z",
          "current": true
        }
      ]
    }
  }
}
```

---

## Admin Auth Endpoints (All New)

### POST /auth/admin/bootstrap

One-time super admin creation. Disabled after first successful call.

**Auth**: Public (gated by env token only)

**Rate limit**: 5 attempts per 15 min per IP.

**Request**:
```json
{
  "bootstrapToken": "string (min 32 chars)",
  "email": "string",
  "password": "string (min 8 chars)"
}
```

**Response 200**:
```json
{
  "success": true,
  "data": {
    "adminId": "uuid",
    "email": "admin@example.com",
    "roles": ["SUPER_ADMIN"],
    "message": "Super admin created successfully"
  }
}
```

**Error cases**:
- `400` — invalid token, missing fields, weak password
- `409` — super admin already exists
- `404` — endpoint disabled (bootstrap token env var removed)

---

### POST /auth/admin/login

**Auth**: Public

**Rate limit**: 5 attempts per 15 min per IP.

**Request**:
```json
{
  "email": "string",
  "password": "string"
}
```

**Response 200**: Sets `admin_access_token` and `admin_refresh_token` HTTP-only cookies.
```json
{
  "success": true,
  "data": {
    "adminId": "uuid",
    "email": "admin@example.com",
    "roles": ["SUPER_ADMIN"],
    "message": null
  }
}
```

**Error cases**:
- `401` — invalid credentials (same message regardless of which field is wrong — no enumeration)
- `403` — account suspended

---

### POST /auth/admin/refresh

**Auth**: Public (refresh token via cookie)

**Request**: No body. Reads `admin_refresh_token` cookie.

**Response 200**: Issues new `admin_access_token` cookie.
```json
{
  "success": true,
  "data": { "message": "Token refreshed" }
}
```

**Error cases**:
- `401` — missing, expired, or revoked refresh token

---

### POST /auth/admin/logout

**Auth**: Admin JWT (`admin_access_token` cookie)

**Request**: No body.

**Response 200**: Clears `admin_access_token` + `admin_refresh_token` cookies.
```json
{
  "success": true,
  "data": { "message": "Logged out" }
}
```

---

### GET /auth/admin/me

**Auth**: Admin JWT

**Response 200**:
```json
{
  "success": true,
  "data": {
    "adminId": "uuid",
    "email": "admin@example.com",
    "firstName": "Super",
    "lastName": "Admin",
    "roles": ["SUPER_ADMIN"],
    "accountStatus": "ACTIVE",
    "createdAt": "2026-05-29T00:00:00Z"
  }
}
```

---

### POST /auth/admin/register

**Auth**: Admin JWT — SUPER_ADMIN role required

**Request**:
```json
{
  "email": "string",
  "password": "string",
  "firstName": "string",
  "lastName": "string",
  "role": "MODERATOR"
}
```

**Response 201**:
```json
{
  "success": true,
  "data": {
    "adminId": "uuid",
    "email": "mod@example.com",
    "roles": ["MODERATOR"],
    "message": "Admin created successfully"
  }
}
```

**Error cases**:
- `403` — caller is not SUPER_ADMIN
- `409` — email already registered
- `400` — attempt to create second SUPER_ADMIN

---

### POST /auth/admin/forgot-password

**Auth**: Public

**Rate limit**: 1 request per 60 min per IP.

**Request**:
```json
{ "email": "string" }
```

**Response 200**: Always 200 (no enumeration).
```json
{
  "success": true,
  "data": { "message": "If this email is registered, a reset link has been sent." }
}
```

**Side effect**: Sends email alert to super admin when reset is requested.

---

### PATCH /auth/admin/reset-password

**Auth**: Public (token in request body)

**Request**:
```json
{
  "token": "string",
  "newPassword": "string (min 8 chars)"
}
```

**Response 200**:
```json
{
  "success": true,
  "data": { "message": "Password reset successfully. All admin sessions have been revoked." }
}
```

**Error cases**:
- `400` — token expired (15 min), token already used/invalidated, weak password (token is invalidated on weak password failure too)
- `401` — invalid token format

---

## Cross-Cutting Contracts

### Error Response Envelope

```json
{
  "success": false,
  "error": {
    "code": "UNAUTHORIZED",
    "message": "Human-readable description"
  }
}
```

Machine-readable codes: `NOT_FOUND`, `UNAUTHORIZED`, `FORBIDDEN`, `CONFLICT`, `VALIDATION_FAILED`, `UPGRADE_REQUIRED`, `RATE_LIMIT_EXCEEDED`

### Session Revocation Contract (Internal — Admin → User)

Admin services call `SessionService` interface:
```java
void revokeAllUserSessions(UUID userId, UUID adminId);
void revokeSession(UUID userId, UUID sessionId, UUID adminId);
```

`SessionService` is the single point of ownership for session state mutations. No other service writes to `user_sessions` directly.

### CapabilityService Contract (Auth → Content)

Content/streaming service calls:
```java
boolean canWatch(UUID userId);
void recordWatch(UUID userId);
```

`CapabilityService` is owned by the Auth/Billing domain boundary. Content service never queries `user_subscriptions` directly.
