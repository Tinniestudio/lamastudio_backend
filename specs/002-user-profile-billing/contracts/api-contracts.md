# API Contracts: User Profile + Settings & Subscription Billing

**Phase**: 1 — Design
**Date**: 2026-05-31
**Envelope format**: All responses use `{ success, data, error }` per Constitution §IX

---

## User Profile Endpoints

### GET /users/me

Returns the authenticated user's complete profile.

**Auth**: Bearer access token required (401 if missing)

**Response 200**:
```json
{
  "success": true,
  "data": {
    "userId": "uuid",
    "email": "user@example.com",
    "firstName": "Jane",
    "lastName": "Doe",
    "displayName": "Jane Doe",
    "avatarUrl": "https://cdn.example.com/avatars/uuid.jpg",
    "phoneNumber": null,
    "dateOfBirth": null,
    "provider": "LOCAL",
    "emailVerified": true,
    "bio": "I love movies",
    "languageCode": "en",
    "countryCode": "NG",
    "timezone": "Africa/Lagos",
    "notificationEmail": true,
    "createdAt": "2026-01-01T00:00:00Z"
  }
}
```

---

### PATCH /users/me

Partially update the user's profile. All fields optional; missing fields are unchanged.

**Auth**: Bearer access token required

**Request body** (all fields optional):
```json
{
  "firstName": "Jane",
  "lastName": "Doe",
  "displayName": "Jane Doe",
  "bio": "I love films and series",
  "languageCode": "en",
  "countryCode": "NG",
  "timezone": "Africa/Lagos",
  "phoneNumber": "+2348012345678",
  "dateOfBirth": "1990-05-15"
}
```

**Validation**:
- `languageCode`: valid IETF BCP-47 tag (max 10 chars)
- `countryCode`: valid ISO 3166-1 alpha-2 (2 chars)
- `timezone`: valid IANA timezone name
- `phoneNumber`: E.164 format if provided
- `dateOfBirth`: ISO 8601 date, must be in the past

**Response 200**: Same structure as GET /users/me with updated values.

**Response 400**: Validation errors:
```json
{
  "success": false,
  "error": { "code": "VALIDATION_FAILED", "message": "Invalid timezone", "details": { "field": "timezone", "value": "BadTZ" } }
}
```

---

### PATCH /users/me/notifications

Update email notification preference.

**Auth**: Bearer access token required

**Request body**:
```json
{
  "notificationEmail": false
}
```

**Response 200**:
```json
{
  "success": true,
  "data": { "notificationEmail": false }
}
```

---

### PATCH /users/me/avatar

Set the user's profile avatar. Supports two modes.

**Auth**: Bearer access token required

**Mode: UPLOAD** — request a presigned upload URL:
```json
{
  "mode": "UPLOAD",
  "mimeType": "image/jpeg",
  "fileSizeBytes": 1048576
}
```

**Response 200 (UPLOAD)**:
```json
{
  "success": true,
  "data": {
    "uploadUrl": "https://storage.example.com/avatars/uuid.jpg?X-Amz-Signature=...",
    "storageKey": "avatars/uuid/f47ac10b.jpg",
    "expiresAt": "2026-05-31T12:15:00Z"
  }
}
```

After client upload, call `POST /users/me/avatar/confirm` with the `storageKey`.

**Mode: URL** — set an external avatar URL directly:
```json
{
  "mode": "URL",
  "avatarUrl": "https://example.com/my-photo.jpg"
}
```

**Response 200 (URL)**:
```json
{
  "success": true,
  "data": {
    "avatarUrl": "https://example.com/my-photo.jpg"
  }
}
```

**Response 400** (invalid mode, bad MIME type, oversized file, invalid URL):
```json
{
  "success": false,
  "error": { "code": "VALIDATION_FAILED", "message": "File type not supported. Allowed: image/jpeg, image/png, image/webp" }
}
```

---

### POST /users/me/avatar/confirm

Confirm that an avatar file upload completed successfully. Updates `avatarUrl`.

**Auth**: Bearer access token required

**Request body**:
```json
{
  "storageKey": "avatars/uuid/f47ac10b.jpg"
}
```

**Response 200**:
```json
{
  "success": true,
  "data": {
    "avatarUrl": "https://cdn.example.com/avatars/uuid/f47ac10b.jpg"
  }
}
```

**Response 422**: If the object does not exist in storage:
```json
{
  "success": false,
  "error": { "code": "VALIDATION_FAILED", "message": "Upload not found in storage. Please upload the file before confirming." }
}
```

---

### PATCH /users/me/password

Change the authenticated user's password.

**Auth**: Bearer access token required

**Request body**:
```json
{
  "currentPassword": "OldPassword123!",
  "newPassword": "NewPassword456!"
}
```

**Validation**:
- `newPassword`: min 8 chars, at least 1 uppercase, at least 1 digit
- `currentPassword`: must match stored hash

**Response 200**:
```json
{
  "success": true,
  "data": { "message": "Password updated successfully. Other sessions have been logged out." }
}
```

**Response 400** (wrong current password or weak new password):
```json
{
  "success": false,
  "error": { "code": "VALIDATION_FAILED", "message": "Current password is incorrect" }
}
```

**Response 400** (OAuth-only account):
```json
{
  "success": false,
  "error": { "code": "VALIDATION_FAILED", "message": "This account uses Google login. Password change is not available." }
}
```

---

## Subscription Endpoints

### GET /subscriptions/plans

List all purchasable subscription plans (SILVER and GOLD only).

**Auth**: Public (no token required)

**Response 200**:
```json
{
  "success": true,
  "data": [
    {
      "planId": "uuid",
      "name": "SILVER",
      "description": "Silver plan",
      "price": 9.99,
      "currency": "CAD",
      "billingCycle": "MONTHLY",
      "maxDevices": 1,
      "videoQuality": "HD",
      "isActive": true
    },
    {
      "planId": "uuid",
      "name": "GOLD",
      "description": "Gold plan - multi-device",
      "price": 19.99,
      "currency": "CAD",
      "billingCycle": "MONTHLY",
      "maxDevices": 3,
      "videoQuality": "FULL_HD",
      "isActive": true
    }
  ]
}
```

---

### POST /subscriptions/apply-coupon

Validate a coupon code and return the discount details. No charge occurs.

**Auth**: Bearer access token required

**Request body**:
```json
{
  "code": "LAUNCH20",
  "planId": "uuid"
}
```

**Response 200** (valid coupon):
```json
{
  "success": true,
  "data": {
    "valid": true,
    "couponId": "uuid",
    "discountType": "PERCENTAGE",
    "discountValue": 20.00,
    "originalPrice": 9.99,
    "finalPrice": 7.99,
    "currency": "CAD"
  }
}
```

**Response 400** (invalid coupon):
```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "Coupon is expired",
    "details": { "reason": "expired" }
  }
}
```

Possible `reason` values: `expired`, `already_used`, `not_found`, `limit_reached`.

---

### POST /subscriptions/checkout

Initiate a Stripe card payment for a subscription plan.

**Auth**: Bearer access token required

**Request body**:
```json
{
  "planId": "uuid",
  "autoRenew": true,
  "couponCode": "LAUNCH20"
}
```

- `autoRenew`: required boolean — whether the subscription auto-renews at period end
- `couponCode`: optional — validated atomically at checkout

**Response 200**:
```json
{
  "success": true,
  "data": {
    "paymentId": "uuid",
    "paymentReference": "pi_3Qx...",
    "paymentUrl": "https://checkout.stripe.com/pay/cs_...",
    "amount": 7.99,
    "currency": "CAD",
    "planName": "SILVER",
    "autoRenew": true,
    "expiresAt": "2026-06-01T12:30:00Z"
  }
}
```

**Response 409** (active subscription exists):
```json
{
  "success": false,
  "error": { "code": "CONFLICT", "message": "You already have an active subscription." }
}
```

**Response 400** (invalid coupon at checkout):
```json
{
  "success": false,
  "error": { "code": "VALIDATION_FAILED", "message": "Coupon limit reached", "details": { "reason": "limit_reached" } }
}
```

---

### GET /subscriptions/me

Get the authenticated user's current subscription status.

**Auth**: Bearer access token required

**Response 200** (active subscription):
```json
{
  "success": true,
  "data": {
    "subscriptionId": "uuid",
    "plan": "SILVER",
    "status": "ACTIVE",
    "startDate": "2026-05-01T00:00:00Z",
    "endDate": "2026-06-01T00:00:00Z",
    "autoRenew": true,
    "cancelledAt": null,
    "contentWatchesUsed": 0,
    "contentWatchesLimit": null,
    "payments": [
      {
        "paymentId": "uuid",
        "amount": 9.99,
        "currency": "CAD",
        "status": "SUCCESSFUL",
        "paidAt": "2026-05-01T10:00:00Z"
      }
    ]
  }
}
```

**Response 200** (FREE plan user — no paid subscription):
```json
{
  "success": true,
  "data": {
    "subscriptionId": "uuid",
    "plan": "FREE",
    "status": "ACTIVE",
    "startDate": "2026-01-01T00:00:00Z",
    "endDate": null,
    "autoRenew": false,
    "cancelledAt": null,
    "contentWatchesUsed": 1,
    "contentWatchesLimit": 2,
    "payments": []
  }
}
```

---

### PATCH /subscriptions/cancel

Cancel an active paid subscription. Access continues until `endDate`.

**Auth**: Bearer access token required

**Request body**: empty `{}`

**Response 200**:
```json
{
  "success": true,
  "data": {
    "subscriptionId": "uuid",
    "status": "ACTIVE",
    "autoRenew": false,
    "cancelledAt": "2026-05-31T14:00:00Z",
    "endDate": "2026-06-01T00:00:00Z",
    "message": "Your subscription has been cancelled. You retain access until 2026-06-01."
  }
}
```

**Response 404** (no active paid subscription):
```json
{
  "success": false,
  "error": { "code": "NOT_FOUND", "message": "No active subscription found." }
}
```

**Response 409** (already cancelled):
```json
{
  "success": false,
  "error": { "code": "CONFLICT", "message": "Subscription is already cancelled." }
}
```

---

## Stripe Webhook

### POST /webhooks/stripe

Receives and processes Stripe webhook events. Stripe sends this after payment outcomes.

**Auth**: Public — validated via `Stripe-Signature` header using Stripe signing secret (400 if invalid)

**Handled events**:

#### `payment_intent.succeeded`

**Behavior**: Update `payments.status = SUCCESSFUL`, set `paidAt`, create `UserSubscription` (ACTIVE), link `payments.subscriptionId`, send subscription activation email.

**Response 200** (always — Stripe retries on non-2xx):
```json
{ "received": true }
```

#### `payment_intent.payment_failed`

**Behavior**: Update `payments.status = FAILED`, set `failureReason` from Stripe error message, send payment failure email.

**Response 200**:
```json
{ "received": true }
```

**Idempotency**: If a webhook for the same `providerReference` arrives twice (already SUCCESSFUL), the handler skips processing and returns 200 immediately.

**Response 400** (invalid Stripe signature):
```json
{
  "success": false,
  "error": { "code": "UNAUTHORIZED", "message": "Invalid webhook signature" }
}
```

---

## Error Code Reference

| Code | HTTP Status | When Used |
|------|-------------|-----------|
| `NOT_FOUND` | 404 | Subscription or resource not found |
| `UNAUTHORIZED` | 401 | Missing or invalid access token; invalid Stripe signature |
| `FORBIDDEN` | 403 | Authenticated but insufficient permissions |
| `CONFLICT` | 409 | Duplicate active subscription; double-cancel |
| `VALIDATION_FAILED` | 400 | Input validation errors, invalid coupon, OAuth password change |
| `UPGRADE_REQUIRED` | 403 | Content watch quota exhausted (from CapabilityService) |
