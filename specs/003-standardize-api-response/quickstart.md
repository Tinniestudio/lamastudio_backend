# Quickstart: Standardize API Success Response Format

**Feature**: 003-standardize-api-response  
**Branch**: `003-standardize-api-response`

---

## What This Feature Does

Wraps every 2xx JSON response from the TinnieStudio API in a standard envelope:

```json
{
  "success": true,
  "message": "Retrieved successfully",
  "data": { ...original payload... }
}
```

A single interceptor (`SuccessResponseWrapper`) handles all endpoints automatically. No
controller modifications are needed.

---

## Files Changed

| File | Change |
|------|--------|
| `shared/web/ApiResponse.java` | NEW — envelope record |
| `shared/web/SuccessResponseWrapper.java` | NEW — ResponseBodyAdvice interceptor |
| `shared/web/SkipResponseWrapper.java` | NEW — exclusion annotation |
| `modules/billing/controller/StripeWebhookController.java` | Add `@SkipResponseWrapper` |
| `test/web/SuccessResponseWrapperTest.java` | NEW — unit tests |
| `test/web/ApiResponseEnvelopeIT.java` | NEW — integration tests |
| `test/integration/AuthIntegrationTest.java` | Update assertions to use `.data` |
| `test/user/UserProfileIntegrationTest.java` | Update assertions to use `.data` |

---

## How to Produce a Paginated Response

Currently no paginated list endpoints exist. When adding one:

```java
// In a controller:
@GetMapping("/list")
public ResponseEntity<ApiResponse<List<ItemDto>>> listItems(...) {
    List<ItemDto> items = service.getPage(page, size);
    long total = service.count();
    ApiResponse.Meta meta = new ApiResponse.Meta(total, page, size, (int) Math.ceil((double) total / size));
    return ResponseEntity.ok(ApiResponse.ok("Retrieved successfully", items, meta));
}
```

Returning `ApiResponse` directly bypasses the wrapper (idempotency guard) and gives you
full control over the message and meta.

---

## How to Skip the Wrapper for a Controller

Add `@SkipResponseWrapper` to the controller class:

```java
@SkipResponseWrapper
@RestController
@RequestMapping("/webhooks")
public class StripeWebhookController { ... }
```

Document the exclusion in [contracts/excluded-endpoints.md](./contracts/excluded-endpoints.md).

---

## Testing

```bash
# Run all tests
./mvnw test

# Run only the new wrapper tests
./mvnw test -Dtest=SuccessResponseWrapperTest,ApiResponseEnvelopeIT

# Run auth integration tests (updated assertions)
./mvnw test -Dtest=AuthIntegrationTest
```

---

## Verification

After implementation, every successful call to a non-excluded endpoint returns the envelope.
Quick smoke test with curl:

```bash
# Should return: { "success": true, "message": "...", "data": [...] }
curl -s http://localhost:8080/subscriptions/plans | jq '.success, .message, (.data | length)'
```
