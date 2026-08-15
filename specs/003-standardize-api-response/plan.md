# Implementation Plan: Standardize API Success Response Format

**Branch**: `003-standardize-api-response` | **Date**: 2026-06-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/003-standardize-api-response/spec.md`

## Summary

All 2xx JSON responses from every controller are wrapped in a standard envelope
`{ success: true, message: string, data: <payload>, meta?: <pagination> }` via a single
`ResponseBodyAdvice` interceptor. Error responses (handled by `GlobalExceptionHandler`) are
unchanged. No controller modifications are required. The `StripeWebhookController` is excluded
to preserve Stripe's expected `{ received: true }` format.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.3.5, Spring MVC (Jakarta), SpringDoc OpenAPI 2.x,
JUnit 5, Mockito, Spring Boot Test

**Storage**: PostgreSQL (JPA), Redis — no schema changes required for this feature

**Testing**: JUnit 5 + Spring Boot Test (`@WebMvcTest` for controller-layer tests,
`@SpringBootTest` for integration tests)

**Target Platform**: Linux server — Spring Boot embedded Tomcat

**Project Type**: REST API web service — `com.tinniestudio.backend`

**Performance Goals**: Zero overhead on non-intercepted paths; envelope serialization adds
< 1ms per response (no DB or cache access in wrapper)

**Constraints**: StripeWebhook endpoint (`/webhooks/stripe`) MUST NOT be wrapped — Stripe
validates the raw `{ received: true }` response. Root redirect (`/`) MUST NOT be wrapped.
Error responses from `GlobalExceptionHandler` MUST NOT be altered.

**Scale/Scope**: 6 controllers, ~25 endpoints affected. Wrapper is a single shared component
with no per-endpoint code.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| **I. Feature Lifecycle** | ✓ PASS | Spec → Plan → Tasks → Implement order followed |
| **II. Batch Boundary** | ✓ PASS | Single bounded capability: success envelope. No cross-batch dependency. Classification: **FOUNDATION** |
| **III. API ↔ Worker** | ✓ PASS | No worker involvement — purely API service |
| **IV. Domain Ownership** | ✓ PASS | Wrapper lives in `shared/web` — no domain repository injection |
| **V. Scalability** | ✓ PASS | New domains/endpoints auto-inherit the wrapper with zero changes |
| **VI. Infrastructure** | ✓ PASS | No Redis, S3, RabbitMQ, or FFmpeg access in wrapper |
| **VII. Completion Gates** | — | Evaluated at completion |
| **VIII. Architecture Drift** | ✓ PASS | No controller business logic, no cross-domain repo injection |
| **IX. Shared Contract** | ✓ PASS | **This feature implements** the constitution's mandate: "All API responses use the standard envelope: `{ success, data, error, meta }`" |
| **X. Multi-Actor Security** | ✓ PASS | Wrapper applies to both user and admin endpoints — no auth logic inside |

**Result**: All pre-implementation gates pass. No violations to justify.

## Project Structure

### Documentation (this feature)

```text
specs/003-standardize-api-response/
├── plan.md              ← this file
├── research.md          ← Phase 0 output
├── data-model.md        ← Phase 1 output
├── quickstart.md        ← Phase 1 output
├── contracts/           ← Phase 1 output
│   ├── api-response-envelope.md
│   └── excluded-endpoints.md
└── tasks.md             ← Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
src/main/java/com/tinniestudio/backend/
└── shared/
    └── web/
        ├── RootController.java              (existing — unchanged)
        ├── ApiResponse.java                 (NEW — envelope record)
        └── SuccessResponseWrapper.java      (NEW — ResponseBodyAdvice)

src/test/java/com/tinniestudio/backend/
└── web/
    ├── SuccessResponseWrapperTest.java      (NEW — unit tests for wrapper logic)
    └── ApiResponseEnvelopeIT.java           (NEW — integration tests for key endpoints)
```

**Structure Decision**: Single project layout, changes confined to `shared/web`. No new packages.
All existing controllers and DTOs remain untouched.

---

## Phase 0: Research

*All NEEDS CLARIFICATION items resolved here. No open questions remain before Phase 1.*

### research.md summary

See [research.md](./research.md) for full findings. Key decisions:

---

**Decision 1: Interception mechanism — `ResponseBodyAdvice<Object>`**
- **Chosen**: `ResponseBodyAdvice<Object>` via `@RestControllerAdvice`
- **Rationale**: This is Spring MVC's canonical hook for transforming response bodies before serialization. It runs after the controller returns but before Jackson serializes the object. It receives the `MethodParameter` (controller + method) and `ServerHttpResponse`/`HttpOutputMessage` so it can inspect HTTP method, path, and response status. Zero performance impact on paths it skips.
- **Alternatives considered**: `HandlerInterceptor` — runs after response is committed, cannot modify body. `Filter` — can modify body but requires reading/rewriting the byte stream, expensive. `AOP pointcut on controllers` — more complex and harder to test in isolation.

---

**Decision 2: Message strategy — HTTP-method defaults + message-map extraction**
- **Chosen**: 
  1. If the response body is a `Map<String, ?>` containing only a `"message"` key → extract the string value as the envelope message; set `data: null`
  2. Otherwise → use HTTP-method defaults: `GET` → "Retrieved successfully", `POST` → "Created successfully", `PATCH/PUT` → "Updated successfully", `DELETE` → "Deleted successfully"
- **Rationale**: Controllers already return `Map.of("message", "Logged out successfully")`, etc. for simple acknowledgement endpoints. Extracting the message and setting `data: null` produces a clean `{ success: true, message: "Logged out successfully", data: null }` without redundancy. No controller modifications required.
- **Alternatives considered**: `@ResponseMessage` annotation on every method — requires touching all controllers (violates the no-modification constraint). Generic `"OK"` message everywhere — too vague, fails FR-007.

---

**Decision 3: Exclusion strategy — class-level annotation + always-excluded list**
- **Chosen**: 
  - Annotate `StripeWebhookController` with `@SkipResponseWrapper` (new annotation in `shared/web`)
  - `RootController` returns a view redirect (not JSON), so `beforeBodyWrite` is never called for it — no explicit exclusion needed
  - The `supports()` method also gates on `MediaType.APPLICATION_JSON` to skip non-JSON responses
- **Rationale**: Annotation is explicit and self-documenting on the controller. Path-based exclusion in the wrapper would require maintaining a hardcoded path list. Class-based check in `supports()` works but is less visible.
- **Alternatives considered**: Path prefix exclusion — brittle if paths change. Content-type check only — sufficient for RootController but not for Stripe (which returns JSON).

---

**Decision 4: Handling `String` return types**
- **Chosen**: Skip wrapping if the return type is `String` (Spring uses `StringHttpMessageConverter` for these, which is registered before Jackson; wrapping would cause `ClassCastException`). Verify no controllers return plain `String` — confirmed: all controllers return `ResponseEntity<SomeDto>` or `ResponseEntity<Map<...>>`.
- **Rationale**: Spring Boot 3 registers `StringHttpMessageConverter` before `MappingJackson2HttpMessageConverter`. A `ResponseBodyAdvice` for `String` would need special handling. Since no controllers return plain `String`, this is a no-op.

---

**Decision 5: Idempotency guard**
- **Chosen**: In `supports()`, return `false` if the declared return type is already `ApiResponse`. This prevents double-wrapping if any code path directly returns `ApiResponse`.
- **Rationale**: Defensive — ensures the wrapper is safe to call on any controller without checking per-method.

## Phase 1: Design & Contracts

### Data Model

See [data-model.md](./data-model.md) for full entity details.

**`ApiResponse<T>`** — envelope record, lives in `shared/web`:

| Field | Type | Always present | Notes |
|-------|------|----------------|-------|
| `success` | `boolean` | Yes | Always `true` for success responses |
| `message` | `String` | Yes | Human-readable outcome description |
| `data` | `T` (generic) | Yes (may be null) | The primary payload; null for message-only responses |
| `meta` | `ApiResponse.Meta` | No (nullable) | Present only on paginated list responses |

**`ApiResponse.Meta`** — pagination metadata (inner record):

| Field | Type | Notes |
|-------|------|-------|
| `total` | `long` | Total record count across all pages |
| `page` | `int` | Current page number (1-based) |
| `size` | `int` | Records per page |
| `totalPages` | `int` | Total page count |

**`SuccessResponseWrapper`** — `ResponseBodyAdvice<Object>` annotated with `@RestControllerAdvice`:

```
supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType):
  → false if returnType is ApiResponse (already wrapped)
  → false if controller class has @SkipResponseWrapper
  → false if converter is StringHttpMessageConverter
  → true otherwise

beforeBodyWrite(body, ..., outputMessage):
  → extract HTTP method from request context
  → determine message:
       if body is Map with sole key "message" → use body.get("message"), set data = null
       else → HTTP-method default, set data = body
  → return new ApiResponse<>(true, message, data, null)
```

**`@SkipResponseWrapper`** — meta-annotation for controllers that must bypass the wrapper:
- `@Retention(RUNTIME)`, `@Target(TYPE)` — applied at class level

### Contracts

See [contracts/](./contracts/) directory.

**`api-response-envelope.md`** — canonical shape for all wrapped responses:
```json
{
  "success": true,
  "message": "string — human-readable outcome",
  "data": "<payload object | array | null>",
  "meta": {
    "total": 100,
    "page": 1,
    "size": 20,
    "totalPages": 5
  }
}
```
`meta` is omitted (field absent, not null) for non-paginated responses.

**`excluded-endpoints.md`** — endpoints that bypass the wrapper:
- `POST /webhooks/stripe` — Stripe requires raw `{ received: true }` (annotated with `@SkipResponseWrapper`)
- `GET /` — returns HTTP redirect, not JSON (no explicit exclusion needed; never hits `beforeBodyWrite`)

### Before/After: Key Endpoints

| Endpoint | Before | After |
|----------|--------|-------|
| `GET /auth/me` | `{ userId, email, roles, ... }` | `{ success: true, message: "Retrieved successfully", data: { userId, email, roles, ... } }` |
| `POST /auth/login` | `{ userId, email, roles, ... }` | `{ success: true, message: "Created successfully", data: { userId, email, roles, ... } }` |
| `POST /auth/logout` | `{ message: "Logged out successfully" }` | `{ success: true, message: "Logged out successfully", data: null }` |
| `GET /subscriptions/plans` | `[ { id, name, ... }, ... ]` | `{ success: true, message: "Retrieved successfully", data: [ { id, name, ... } ] }` |
| `POST /webhooks/stripe` | `{ received: true }` | **unchanged** (excluded) |
| `4xx/5xx errors` | `{ success: false, message, error, ... }` | **unchanged** (excluded) |

### Agent Context Update

The active feature plan reference in CLAUDE.md has been updated to point to this plan file.
