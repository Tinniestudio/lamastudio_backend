# Contract: Excluded Endpoints

**Version**: 1.0  
**Feature**: 003-standardize-api-response

These endpoints are explicitly excluded from the `SuccessResponseWrapper` and return their
pre-existing response format.

---

## `POST /webhooks/stripe`

**Controller**: `StripeWebhookController`  
**Exclusion mechanism**: `@SkipResponseWrapper` annotation on the controller class

**Why excluded**: Stripe's webhook delivery system validates the response from this endpoint.
A `200 OK` with body `{ "received": true }` signals successful processing. Any change to
the response body structure would cause Stripe to retry the event delivery, potentially
triggering duplicate payment activations or subscription state corruption.

**Response format (unchanged)**:
```json
{ "received": true }
```

---

## `GET /`

**Controller**: `RootController`  
**Exclusion mechanism**: Returns a view redirect (`redirect:/swagger-ui.html`), not a JSON body.
Spring resolves this as a view and never invokes `ResponseBodyAdvice`.

**Why excluded**: Not a data endpoint — redirects browsers to the Swagger UI.

---

## Adding New Exclusions

To exclude a future endpoint from the response wrapper:

1. Add `@SkipResponseWrapper` to the controller class (class-level, not method-level)
2. Document the exclusion in this file with:
   - Controller class name
   - Exclusion mechanism used
   - Reason for exclusion
   - Expected response format

**Do not** add path-based or method-based exclusion logic to `SuccessResponseWrapper` —
use the annotation only.
