# Data Model: Standardize API Success Response Format

**Feature**: 003-standardize-api-response  
**Date**: 2026-06-05

---

## New Entities

### `ApiResponse<T>`

**Location**: `com.tinniestudio.backend.shared.web.ApiResponse`  
**Type**: Java record  
**Purpose**: Standard envelope for all 2xx JSON success responses

```
ApiResponse<T>
├── success: boolean        — always true for success responses
├── message: String         — human-readable outcome description (non-null)
├── data: T                 — primary payload; null for message-only acknowledgements
└── meta: ApiResponse.Meta  — nullable; only present on paginated list responses
```

**Static factory methods**:
- `ApiResponse.ok(String message, T data)` → envelope without pagination
- `ApiResponse.ok(String message, T data, Meta meta)` → envelope with pagination

**Relationships**:
- Wraps all existing DTOs: `AuthProfileResponse`, `UserProfileResponse`, `SubscriptionStatusResponse`, `PlanResponse`, `CheckoutResponse`, `CouponValidationResponse`, `AdminAuthResponse`, `VerifyEmailResponse`, `AvatarUploadResponse`, `UserResponseDto`
- Replaces ad-hoc `Map<String, String>` / `Map<String, Boolean>` responses

---

### `ApiResponse.Meta`

**Location**: inner record of `ApiResponse`  
**Purpose**: Pagination metadata for list endpoints

```
ApiResponse.Meta
├── total: long     — total record count across all pages
├── page: int       — current page (1-based)
├── size: int       — records per page
└── totalPages: int — total page count
```

**Usage**: Populated by service layer when returning paginated results. Currently no
paginated endpoints exist — this is designed for future list endpoints.

---

### `SuccessResponseWrapper`

**Location**: `com.tinniestudio.backend.shared.web.SuccessResponseWrapper`  
**Type**: Spring `@RestControllerAdvice` implementing `ResponseBodyAdvice<Object>`  
**Purpose**: Intercepts all 2xx JSON responses and wraps them in `ApiResponse`

```
SuccessResponseWrapper
├── supports(MethodParameter, Class<HttpMessageConverter>): boolean
│   ├── false → converter is StringHttpMessageConverter
│   ├── false → controller has @SkipResponseWrapper
│   ├── false → declared return type is ApiResponse
│   └── true  → all other cases
└── beforeBodyWrite(body, MethodParameter, MediaType, Class, ServerHttpRequest, ServerHttpResponse): Object
    ├── guard: body == null → return null
    ├── guard: body instanceof ApiResponse → return body unchanged
    ├── extract message:
    │   ├── Map with sole key "message" → use value, set data = null
    │   └── otherwise → HTTP-method default, set data = body
    └── return ApiResponse.ok(message, data)
```

**State**: Stateless — no fields, no DB or cache access, safe for concurrent use.

---

### `@SkipResponseWrapper`

**Location**: `com.tinniestudio.backend.shared.web.SkipResponseWrapper`  
**Type**: Java annotation  
**Purpose**: Marks a controller class to be bypassed by `SuccessResponseWrapper`

```
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@interface SkipResponseWrapper {}
```

**Applied to**: `StripeWebhookController` (Stripe requires verbatim `{ received: true }`)

---

## Modified Entities

No existing entities are modified. All existing DTOs and controllers remain unchanged.

---

## Envelope Shape Reference

### Standard success (non-paginated)
```json
{
  "success": true,
  "message": "Retrieved successfully",
  "data": {
    "userId": "a1b2c3d4-...",
    "email": "user@example.com"
  }
}
```

### Message-only acknowledgement (logout, password reset, etc.)
```json
{
  "success": true,
  "message": "Logged out successfully",
  "data": null
}
```

### List response (future paginated endpoint)
```json
{
  "success": true,
  "message": "Retrieved successfully",
  "data": [ ... ],
  "meta": {
    "total": 47,
    "page": 1,
    "size": 20,
    "totalPages": 3
  }
}
```

### Error response (UNCHANGED — handled by GlobalExceptionHandler)
```json
{
  "success": false,
  "message": "Invalid email or password",
  "error": {
    "code": "INVALID_CREDENTIALS",
    "message": "Invalid email or password"
  },
  "status": 401,
  "path": "/auth/login",
  "timestamp": "2026-06-05T10:00:00Z"
}
```
