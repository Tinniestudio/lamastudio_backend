# Contract: API Response Envelope

**Version**: 1.0  
**Applies to**: All 2xx JSON responses from TinnieStudio API  
**Effective**: Feature 003-standardize-api-response

---

## Envelope Schema

Every successful (2xx) JSON response from the API conforms to this structure:

```json
{
  "success": true,
  "message": "<string>",
  "data": "<object | array | null>",
  "meta": {
    "total": "<integer>",
    "page": "<integer>",
    "size": "<integer>",
    "totalPages": "<integer>"
  }
}
```

### Field Rules

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `success` | `boolean` | Yes | Always `true` for 2xx responses |
| `message` | `string` | Yes | Human-readable outcome description. Never empty. |
| `data` | any | Yes (may be `null`) | Primary payload. `null` for acknowledgement-only responses (e.g., logout) |
| `meta` | object | No | Present only on paginated list responses. Omitted (not `null`) for non-list responses. |
| `meta.total` | integer | When `meta` present | Total record count across all pages |
| `meta.page` | integer | When `meta` present | Current page number (1-based) |
| `meta.size` | integer | When `meta` present | Records per page |
| `meta.totalPages` | integer | When `meta` present | Calculated as `ceil(total / size)` |

---

## Message Conventions

| HTTP Method | Default Message |
|-------------|----------------|
| `GET` | "Retrieved successfully" |
| `POST` | "Created successfully" |
| `PATCH` / `PUT` | "Updated successfully" |
| `DELETE` | "Deleted successfully" |
| Acknowledgement-only | Exact text from controller (e.g., "Logged out successfully") |

---

## Endpoint Mapping

| Endpoint | Method | Message | `data` content |
|----------|--------|---------|----------------|
| `POST /auth/register` | POST | "Created successfully" | `AuthProfileResponse` |
| `POST /auth/login` | POST | "Created successfully" | `AuthProfileResponse` |
| `POST /auth/refresh` | POST | "Created successfully" | `AuthProfileResponse` |
| `POST /auth/logout` | POST | "Logged out successfully" | `null` |
| `GET /auth/verify-email` | GET | "Retrieved successfully" | `VerifyEmailResponse` |
| `POST /auth/resend-verification-email` | POST | "Verification email sent successfully. Please check your inbox." | `null` |
| `POST /auth/forgot-password` | POST | "If an account with that email exists, a password reset link has been sent" | `null` |
| `PATCH /auth/reset-password` | PATCH | "Password reset successfully. Please log in." | `null` |
| `GET /auth/me` | GET | "Retrieved successfully" | `AuthProfileResponse` |
| `POST /auth/admin/bootstrap` | POST | "Created successfully" | `AdminAuthResponse` |
| `POST /auth/admin/login` | POST | "Created successfully" | `AdminAuthResponse` |
| `POST /auth/admin/refresh` | POST | "Created successfully" | `AdminAuthResponse` |
| `POST /auth/admin/logout` | POST | controller message | `null` |
| `GET /auth/admin/me` | GET | "Retrieved successfully" | `AdminAuthResponse` |
| `GET /users/me` | GET | "Retrieved successfully" | `UserProfileResponse` |
| `PATCH /users/me` | PATCH | "Updated successfully" | `UserProfileResponse` |
| `PATCH /users/me/notifications` | PATCH | "Updated successfully" | `{ notificationEmail: boolean }` |
| `PATCH /users/me/avatar` | PATCH | "Updated successfully" | `AvatarUploadResponse` or `{ avatarUrl: string }` |
| `POST /users/me/avatar/confirm` | POST | "Created successfully" | `{ avatarUrl: string }` |
| `PATCH /users/me/password` | PATCH | "Password updated successfully. Other sessions have been logged out." | `null` |
| `GET /subscriptions/plans` | GET | "Retrieved successfully" | `List<PlanResponse>` |
| `POST /subscriptions/apply-coupon` | POST | "Created successfully" | `CouponValidationResponse` |
| `POST /subscriptions/checkout` | POST | "Created successfully" | `CheckoutResponse` |
| `GET /subscriptions/me` | GET | "Retrieved successfully" | `SubscriptionStatusResponse` |
| `PATCH /subscriptions/cancel` | PATCH | "Updated successfully" | `SubscriptionStatusResponse` |

---

## Excluded Endpoints

These endpoints are exempt from the envelope and return their existing response format unchanged:

| Endpoint | Reason |
|----------|--------|
| `POST /webhooks/stripe` | Stripe validates raw `{ received: true }` response; wrapping would break event delivery confirmation |
| `GET /` | Returns HTTP redirect, not JSON |

See [excluded-endpoints.md](./excluded-endpoints.md) for details.

---

## Consumer Guide

**Accessing the payload**:
```
response.data           // primary payload (object, array, or null)
response.message        // human-readable outcome
response.success        // always true for 2xx
response.meta?.total    // pagination total (if present)
```

**Checking for pagination**:
```
if (response.meta != null) { /* use response.meta.page, .size, .total, .totalPages */ }
```

**Do not rely on**:
- Top-level fields other than `success`, `message`, `data`, `meta`
- The specific wording of `message` values (may change without version bump)
