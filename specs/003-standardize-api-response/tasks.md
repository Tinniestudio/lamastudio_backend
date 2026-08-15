---
description: "Task list for Standardize API Success Response Format"
---

# Tasks: Standardize API Success Response Format

**Input**: Design documents from `specs/003-standardize-api-response/`

**Prerequisites**: plan.md ✓, spec.md ✓, research.md ✓, data-model.md ✓, contracts/ ✓

**TDD**: Constitution §TDD is NON-NEGOTIABLE. Every implementation task is preceded by a
failing test. Tests MUST be confirmed failing before implementation begins.

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no shared dependency)
- **[Story]**: User story label (US1, US2, US3)

## Path Conventions

```
src/main/java/com/tinniestudio/backend/   ← production code
src/test/java/com/tinniestudio/backend/   ← test code
```

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core shared types required by ALL user story phases. Must reach completion before
any user story task begins.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T001 Create `@SkipResponseWrapper` annotation (`@Retention(RUNTIME)`, `@Target(TYPE)`) in `src/main/java/com/tinniestudio/backend/shared/web/SkipResponseWrapper.java`
- [x] T002 [P] Create `ApiResponse<T>` record with `Meta` inner record and factory methods `ok(String message, T data)` / `ok(String message, T data, Meta meta)` in `src/main/java/com/tinniestudio/backend/shared/web/ApiResponse.java`

**Checkpoint**: `ApiResponse<T>` and `@SkipResponseWrapper` compile cleanly — user story phases can now begin.

---

## Phase 3: User Story 1 — API Consumer Receives Consistent Success Responses (Priority: P1) 🎯 MVP

**Goal**: Every 2xx JSON response from any non-excluded endpoint arrives wrapped in
`{ success: true, message: "...", data: <payload> }` via a single interceptor. No controller changes required.

**Independent Test**: Call `GET /subscriptions/plans` — response contains `success: true`,
`message: "Retrieved successfully"`, and `data: [...]` at the top level with no payload fields
outside the envelope.

### Tests for User Story 1 (TDD — write and confirm FAIL before T006)

- [x] T003 [US1] Write failing unit tests in `src/test/java/com/tinniestudio/backend/web/SuccessResponseWrapperTest.java` covering: (1) DTO body → wrapped in `data`, (2) `Map.of("message","text")` body → message extracted, `data: null`, (3) null body → null returned unchanged, (4) body already `ApiResponse` → not re-wrapped (idempotency), (5) controller annotated `@SkipResponseWrapper` → `supports()` returns false
- [x] T004 [US1] Write failing integration tests in `src/test/java/com/tinniestudio/backend/web/ApiResponseEnvelopeIT.java` covering: (1) `GET /subscriptions/plans` returns `{ success:true, message:"Retrieved successfully", data:[...] }`, (2) `POST /auth/logout` returns `{ success:true, message:"Logged out successfully", data:null }`, (3) `POST /auth/register` (valid body) returns `{ success:true, message:"Created successfully", data:{...} }`
- [x] T005 [US1] Run T003 tests and confirm ALL FAIL: `./mvnw test -Dtest=SuccessResponseWrapperTest` — no `SuccessResponseWrapper` class exists yet, expect compilation or runtime failures

### Implementation for User Story 1

- [x] T006 [US1] Implement `SuccessResponseWrapper` (`@RestControllerAdvice implements ResponseBodyAdvice<Object>`) with `supports()` excluding `StringHttpMessageConverter`, `@SkipResponseWrapper` controllers, and existing `ApiResponse` return types; `beforeBodyWrite()` resolving message via message-map extraction or HTTP-method default in `src/main/java/com/tinniestudio/backend/shared/web/SuccessResponseWrapper.java`
- [x] T007 [US1] Add `@SkipResponseWrapper` annotation to `StripeWebhookController` class declaration in `src/main/java/com/tinniestudio/backend/modules/billing/controller/StripeWebhookController.java`
- [x] T008 [US1] Run T003 unit tests and confirm ALL PASS: `./mvnw test -Dtest=SuccessResponseWrapperTest`
- [x] T009 [US1] Run T004 integration tests and confirm ALL PASS: `./mvnw test -Dtest=ApiResponseEnvelopeIT`

**Checkpoint**: User Story 1 is fully functional. Every non-excluded 2xx JSON endpoint returns the
standard envelope. `GET /subscriptions/plans` returns `{ success: true, message: "Retrieved successfully", data: [...] }`.

---

## Phase 4: User Story 2 — Paginated Responses Include Metadata (Priority: P2)

**Goal**: When a paginated list response is returned, the envelope includes a `meta` field
with `{ total, page, size, totalPages }`. For non-paginated responses `meta` is absent (not
serialized as `null`).

**Independent Test**: Serialize an `ApiResponse` with `meta=null` and confirm the `meta` key
is absent from the JSON output. Serialize one with `meta` populated and confirm all four
pagination fields are present.

### Tests for User Story 2 (TDD — write and confirm FAIL before T011)

- [x] T010 [US2] Write failing unit tests in `src/test/java/com/tinniestudio/backend/web/SuccessResponseWrapperTest.java` covering: (1) `ApiResponse.ok("msg", data)` with no meta → serialized JSON has no `meta` key, (2) `ApiResponse.ok("msg", data, new Meta(47, 1, 20, 3))` → serialized JSON has `meta.total=47`, `meta.page=1`, `meta.size=20`, `meta.totalPages=3`

### Implementation for User Story 2

- [x] T011 [US2] Add `@JsonInclude(JsonInclude.Include.NON_NULL)` to `ApiResponse` record class (ensures `meta: null` is omitted from serialized JSON) in `src/main/java/com/tinniestudio/backend/shared/web/ApiResponse.java`
- [x] T012 [US2] Run T010 tests and confirm ALL PASS: `./mvnw test -Dtest=SuccessResponseWrapperTest`

**Checkpoint**: `meta` field is present only when populated. Non-paginated responses have no
`meta` key in their JSON output.

---

## Phase 5: User Story 3 — Error Responses Are Not Changed (Priority: P3)

**Goal**: Error responses (4xx/5xx) from `GlobalExceptionHandler` and Stripe webhook responses
bypass the `SuccessResponseWrapper` entirely and remain in their existing format.

**Independent Test**: Trigger a 401 from `POST /auth/login` with bad credentials — response
must have `success: false`, `error.code`, `status`, `path`, `timestamp` at the top level
with NO `data` field. Call `POST /webhooks/stripe` — response must be `{ received: true }`.

### Tests for User Story 3 (TDD — write and confirm FAIL if exclusions are not in place)

- [x] T013 [US3] Add to `src/test/java/com/tinniestudio/backend/web/ApiResponseEnvelopeIT.java`: (1) `POST /webhooks/stripe` (with mock valid Stripe-Signature) returns exactly `{ received: true }` with no `success`/`data`/`message` keys, (2) `POST /auth/login` with invalid credentials returns `{ success:false, error:{...}, status:401, path:..., timestamp:... }` — assert no `data` key present

### Verification for User Story 3

- [x] T014 [US3] Run T013 tests and confirm ALL PASS: `./mvnw test -Dtest=ApiResponseEnvelopeIT` — no new implementation needed; exclusions and error-handler bypass already implemented in Phase 3

**Checkpoint**: Error responses and excluded endpoints are byte-for-byte identical to
pre-feature behavior.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Update existing tests to use the new envelope structure and refresh API documentation.

- [x] T015 [P] Update `AuthIntegrationTest` response assertions: change all top-level field accesses (e.g., `response.userId`) to access via `.data.{field}` (e.g., `response.data.userId`) in `src/test/java/com/tinniestudio/backend/integration/AuthIntegrationTest.java`
- [x] T016 [P] Update `UserProfileIntegrationTest` response assertions to use `.data.{field}` in `src/test/java/com/tinniestudio/backend/user/UserProfileIntegrationTest.java`
- [x] T017 Run full test suite and confirm all tests pass: `./mvnw test`
- [x] T018 [P] Update OpenAPI `@ApiResponse` example values in `AuthController` and `AdminAuthController` to show envelope format `{ success:true, message:"...", data:{...} }` in `src/main/java/com/tinniestudio/backend/modules/auth/controller/AuthController.java` and `src/main/java/com/tinniestudio/backend/modules/auth/admin/controller/AdminAuthController.java`
- [x] T019 [P] Update OpenAPI `@ApiResponse` example values in `UserProfileController` and `SubscriptionController` to show envelope format in `src/main/java/com/tinniestudio/backend/modules/user/controller/UserProfileController.java` and `src/main/java/com/tinniestudio/backend/modules/billing/controller/SubscriptionController.java`

**Checkpoint**: All 19 tasks complete. Full test suite green. API documentation reflects envelope.

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 2 (Foundational): No dependencies — start immediately
   ↓
Phase 3 (US1): Requires T001 + T002 complete
   ↓
Phase 4 (US2): Requires Phase 3 complete (ApiResponse already created, just add annotation)
   ↓
Phase 5 (US3): Requires Phase 3 complete (exclusions already in place)
   ↓
Phase 6 (Polish): Requires Phase 3 + Phase 4 + Phase 5 complete
```

### User Story Dependencies

- **US1 (P1)**: Depends on Foundational (T001, T002). No dependency on US2 or US3.
- **US2 (P2)**: Depends on US1 (ApiResponse created in T002). Minor update to T002 output.
- **US3 (P3)**: Depends on US1 (StripeWebhookController exclusion in T007). Verification only.

### Within Each User Story

1. Tests MUST be written first and confirmed failing
2. Implementation follows
3. Tests must pass before moving to next story

### Parallel Opportunities

- T001 and T002 can run in parallel (different files)
- T003 and T004 can run in parallel (different test files in different directories)
- T015, T016, T018, T019 can run in parallel (different files)

---

## Parallel Example: Foundational Phase

```bash
# These can run in parallel (different files):
Task T001: "Create @SkipResponseWrapper annotation in shared/web/SkipResponseWrapper.java"
Task T002: "Create ApiResponse<T> record in shared/web/ApiResponse.java"
```

## Parallel Example: User Story 1 Tests

```bash
# These can run in parallel (different test files):
Task T003: "Write unit tests in SuccessResponseWrapperTest.java"
Task T004: "Write integration tests in ApiResponseEnvelopeIT.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 2: Foundational (T001, T002) — ~30 min
2. Complete Phase 3: User Story 1 (T003–T009) — ~2 hours
3. **STOP and VALIDATE**: `GET /subscriptions/plans` returns envelope
4. All consumers can now reliably access `.data` for any successful response

### Incremental Delivery

1. T001–T009 → US1 complete → MVP shipped (core envelope working)
2. T010–T012 → US2 complete → Meta/pagination infrastructure ready
3. T013–T014 → US3 complete → Exclusions verified
4. T015–T019 → Polish complete → Test suite green, docs updated

### Single Developer (Sequential)

Full estimated time: ~4 hours

1. **Foundation** (T001–T002): 20 min
2. **US1 core** (T003–T009): 2 hours (write tests → implement wrapper → verify)
3. **US2 meta** (T010–T012): 30 min (add test → add @JsonInclude → verify)
4. **US3 exclusions** (T013–T014): 30 min (add tests → verify pass)
5. **Polish** (T015–T019): 45 min (update existing test assertions + docs)

---

## Notes

- **[P]** tasks operate on different files — safe to implement simultaneously
- **[Story]** label traces each task back to a spec user story for review
- TDD is mandatory: tests written and confirmed FAIL before every implementation task
- Run `./mvnw test -Dtest=<TestClass>` to run a specific test class in isolation
- Run `./mvnw test` for full suite
- After T009 the core feature is done — Polish phase (T015–T019) can be deferred if needed
