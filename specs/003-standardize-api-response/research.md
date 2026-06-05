# Research: Standardize API Success Response Format

**Feature**: 003-standardize-api-response  
**Date**: 2026-06-05  
**Status**: Complete — all NEEDS CLARIFICATION items resolved

---

## Decision 1: Interception Mechanism

**Decision**: Use `ResponseBodyAdvice<Object>` via `@RestControllerAdvice`

**Rationale**: Spring MVC's built-in hook for transforming response bodies before Jackson serializes them. Receives the `MethodParameter` (allowing controller/method-level introspection) and works with all `@RestController` endpoints automatically. Zero cost on excluded paths since `supports()` runs first and short-circuits.

**Alternatives considered**:
- `HandlerInterceptor` — executes after the response is committed; cannot modify the body
- `javax.servlet.Filter` — can modify body but requires buffering and rewriting the byte stream (expensive, error-prone with streaming responses)
- AOP `@Around` on all controllers — more complex wiring, harder to test in isolation, couples to Spring AOP proxy infrastructure

---

## Decision 2: Message Strategy

**Decision**: Two-step message resolution — message-map extraction first, HTTP-method default fallback

**Rationale**: Controllers returning `Map.of("message", "...")` (logout, resend-verification, forgot-password, reset-password, change-password) already carry a meaningful message. Extracting it avoids duplication (`data: null`, message in envelope). For DTOs with no explicit message, an HTTP-method default is readable and accurate for the vast majority of cases.

**Message defaults by HTTP method**:
- `GET` → `"Retrieved successfully"`
- `POST` → `"Created successfully"`
- `PATCH` / `PUT` → `"Updated successfully"`
- `DELETE` → `"Deleted successfully"`
- fallback → `"Success"`

**Detection rule for message-map**: `body instanceof Map<?,?> m && m.size() == 1 && m.containsKey("message") && m.get("message") instanceof String`

**Alternatives considered**:
- `@ResponseMessage("text")` on every method — readable but requires touching all 25 endpoints (violates no-modification constraint)
- Generic `"OK"` everywhere — too vague; fails FR-007
- OpenAPI `@Operation.summary` extraction at runtime — overly complex, couples to SpringDoc internals

---

## Decision 3: Exclusion Strategy

**Decision**: `@SkipResponseWrapper` annotation at class level; content-type guard in `supports()`

**Rationale**: Annotation is co-located with the controller it protects — visible during code review. Content-type guard (`MediaType.APPLICATION_JSON`) handles all non-JSON cases (redirects, HTML, plain text) without explicit exclusion.

**Confirmed exclusions**:
1. `StripeWebhookController` — must return `{ received: true }` verbatim; Stripe's signature validation checks the raw response body structure
2. `RootController` — returns `redirect:/swagger-ui.html` (Spring resolves as view, never reaches `ResponseBodyAdvice`)

**Alternatives considered**:
- Path-prefix exclusion (`/webhooks/**`) — brittle if paths change; less visible
- Controller class check in `beforeBodyWrite` — equivalent but `supports()` is cleaner (avoids processing body at all)

---

## Decision 4: String Return Types

**Decision**: Guard `supports()` to return `false` for `StringHttpMessageConverter`

**Rationale**: Spring Boot registers `StringHttpMessageConverter` before `MappingJackson2HttpMessageConverter`. A `ResponseBodyAdvice` that returns a non-String object for a String-typed endpoint causes `ClassCastException` at serialization time.

**Verification**: Confirmed no controller in this project returns a plain `String` body — all return `ResponseEntity<SomeDto>` or `ResponseEntity<Map<...>>`. The guard is defensive only.

---

## Decision 5: Idempotency Guard

**Decision**: In `supports()`, return `false` if the controller method's declared return type resolves to `ApiResponse`

**Rationale**: If any future endpoint directly constructs and returns `ApiResponse`, the wrapper must not re-wrap it. This prevents `{ success: true, message: "...", data: { success: true, message: "...", data: ... } }`.

---

## Decision 6: Status Code Scope

**Decision**: The wrapper activates for all 2xx responses; it does not check the specific status code (200 vs 201 vs 204)

**Rationale**: 
- 200/201 — standard wrapping applies
- 204 No Content — body is null; `beforeBodyWrite` receives `null`; wrapper returns `ApiResponse.ok("...", null)`. However, 204 responses must have an empty body — the wrapper must skip null bodies entirely to respect the HTTP spec.
- **Rule added**: if `body == null`, return `null` unchanged

**No controllers in this project currently use 204 No Content** (confirmed by reading all controllers).

---

## Open Questions Resolved

| Question | Resolution |
|----------|------------|
| Does the existing error handler need changes? | No — `GlobalExceptionHandler` already produces `{ success: false, message, error, status, path, timestamp }`. The wrapper only fires for 2xx. |
| Does StripeWebhook use JSON content type? | Yes (`consumes = "application/json"`) — must use `@SkipResponseWrapper`, not content-type filtering |
| Are there any streaming or SSE endpoints? | No — all endpoints are standard request/response JSON |
| Are there any endpoints that return `void` or `Void`? | No controllers return void bodies in the current implementation |
| Will this break existing integration tests? | Yes — `AuthIntegrationTest`, `UserProfileIntegrationTest` will need their response assertions updated to look inside `.data` |
