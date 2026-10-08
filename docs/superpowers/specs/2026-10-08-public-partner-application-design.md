# Public Partner Application — Design

**Date:** 2026-10-08
**Status:** Approved, ready for planning
**Repos:** `server` (api-service), `tinniestudio-partner-web`

## Context

Partner-web's `/apply` page is already reachable by anonymous visitors — it's
listed in `src/proxy.ts`'s `PUBLIC_PATHS` — but submitting the form 401s,
because the backend endpoint it calls, `POST /partners/applications`
(`api-service/.../modules/partner/controller/PartnerController.java:38-48`),
requires `isAuthenticated()` and derives the applicant's identity entirely
from the session principal (`CurrentUser.id(principal)`). The request body
(`PartnerApplicationRequest`) has no name/email fields at all — there was
never a path for an application to exist without an already-logged-in user.

This spec makes applying genuinely possible without an account: the endpoint
becomes auth-optional, anonymous submissions create a real account inline
(exactly like `/auth/register`), and a new self-service status check closes a
documented gap (`docs/BACKEND-API-REFERENCE.md`'s "Known Gaps": *"no
self-service GET to check application status"*).

## 1. Backend — Auth-Optional Apply Endpoint

### Two changes needed to make the endpoint reachable without a session

`PartnerController` is `@PreAuthorize("hasRole('PARTNER')")` at the class
level; the `apply` method already overrides this with
`@PreAuthorize("isAuthenticated()")`. Both layers must change:

1. **Method-level:** `@PreAuthorize("isAuthenticated()")` → `@PreAuthorize("permitAll()")`.
2. **URL-level** (`SecurityConfig.PUBLIC_ENDPOINTS`): add
   `"/partners/applications"` and `"/api/v1/partners/applications"` (exact
   path, no trailing wildcard — this is a single fixed route, not a
   parameterized one like the trailer endpoints).

Without both, the request either never reaches the controller (URL-level
block) or reaches it but gets rejected by method security (if only the URL
is opened).

### Why the session-if-present behavior falls out for free

`JwtAuthenticationFilter.shouldNotFilter` only skips JWT processing for
`/auth/register`/`/auth/login`/etc. — `/partners/applications` is **not** in
that skip list, so the filter always runs on it regardless of whether the
path is in `PUBLIC_ENDPOINTS`. `PUBLIC_ENDPOINTS` only controls the
authorization decision (permit vs. require-auth), not whether the filter
populates `SecurityContextHolder`. So:

- A request with a valid `access_token` cookie still gets a populated
  `SecurityContext` → `@AuthenticationPrincipal UserDetails principal` is
  non-null → the controller takes the **authenticated branch**.
- A request with no cookie (or an invalid one) leaves the context empty →
  `principal` is null → the controller takes the **anonymous branch**.

No filter changes needed — this is the existing mechanism, just no longer
gated out at the authorization layer.

### Controller signature change

```java
@PreAuthorize("permitAll()")
@RateLimit(maxRequests = 3, windowMinutes = 60, keyStrategy = "USER_OR_IP",
           errorMessage = "Too many applications. Please try again later.")
@PostMapping("/applications")
public ResponseEntity<PartnerApplicationResponse> apply(
        @AuthenticationPrincipal UserDetails principal,
        @Valid @RequestBody PartnerApplicationRequest req,
        HttpServletResponse response) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(applicationService.apply(principal, req, response));
}
```

(`principal` is now nullable; `HttpServletResponse` is new, needed so the
anonymous branch can set auth cookies via the reused registration logic —
see Section 2.)

**Rate limiting:** the existing 3/hour `USER_OR_IP` limiter covers both
branches (an anonymous caller is keyed by IP). The anonymous branch
additionally goes through the same account-creation path as `/auth/register`,
which carries its own 5/15min IP limiter (`AuthController.register`) — so an
anonymous abuse attempt is caught by the tighter of the two, not a new limit
to invent.

## 2. Backend — Anonymous Branch Creates a Real Account

**Request DTO** (`PartnerApplicationRequest`) gains four fields, required
only when the caller is anonymous (enforced in the service, not via
`@NotNull`, since they must stay optional for the authenticated branch):

```java
private String email;
private String password;
private String firstName;
private String lastName;
```

Same validation rules as `RegisterRequest` (email format, password
complexity pattern, name length) — duplicated constraints on this DTO rather
than extending `RegisterRequest`, since `PartnerApplicationRequest` isn't a
registration request semantically and the two should be free to diverge.

**`PartnerApplicationServiceImpl.apply`** signature changes from
`apply(UUID userId, PartnerApplicationRequest req)` to
`apply(UserDetails principal, PartnerApplicationRequest req, HttpServletResponse response)`:

```java
UUID userId;
if (principal != null) {
    userId = CurrentUser.id(principal);
} else {
    if (userRepo.existsByEmailAndDeletedAtIsNull(req.getEmail())) {
        throw new EmailAlreadyExistsException(
            "An account with this email already exists. Log in to apply.");
    }
    RegisterRequest registerReq = new RegisterRequest();
    registerReq.setEmail(req.getEmail());
    registerReq.setPassword(req.getPassword());
    registerReq.setFirstName(req.getFirstName());
    registerReq.setLastName(req.getLastName());
    AuthProfileResponse newAccount = authService.register(registerReq, response);
    userId = newAccount.userId();
}
// ... upsert logic below, same for both branches
```

`AuthService.register(RegisterRequest, HttpServletResponse)` is the exact
method `AuthController.register` already calls — reused, not duplicated. It
creates the `User` (`ROLE_USER`), sends the verification email, and sets the
same `access_token`/`refresh_token` cookies `/auth/login` sets, on the
`HttpServletResponse` passed in. Because this is the *same response object*
the controller returns, the anonymous applicant is logged in by the time
they receive the application-created response — no separate login step
before they can reach the new status page (Section 3/5).

**Duplicate email on the anonymous branch** throws the same
`EmailAlreadyExistsException` `/auth/register` already throws → the existing
`GlobalExceptionHandler` mapping returns the same 409 shape — no new error
handling needed.

## 3. Backend — Reuse-One-Row Upsert (Replaces Blind Insert)

Today's `apply()` always inserts a new `PartnerApplication` row, and only
blocks when `existsByUserIdAndStatus(userId, PENDING)` — it has no check
against `REJECTED` or `APPROVED`, so a user can already silently accumulate
duplicate rows by re-calling it. `PartnerApplicationRepository.findByUserId`
(`Optional<PartnerApplication>`) already exists but is unused by `apply()` —
the singular-lookup method was clearly the intended design; this closes the
gap between intent and implementation.

New upsert logic:

```java
Optional<PartnerApplication> existing = applicationRepo.findByUserId(userId);
if (existing.isPresent()) {
    PartnerApplicationStatus status = existing.get().getStatus();
    if (status == PartnerApplicationStatus.PENDING) {
        throw new BadRequestException("A pending partner application already exists");
    }
    if (status == PartnerApplicationStatus.APPROVED) {
        throw new BadRequestException("You are already a partner");
    }
    // status == REJECTED: reset and reuse the same row
    PartnerApplication app = existing.get();
    app.setCompanyName(req.getCompanyName());
    app.setDescription(req.getDescription());
    app.setWebsiteUrl(req.getWebsiteUrl());
    app.setStatus(PartnerApplicationStatus.PENDING);
    app.setRejectionReason(null);
    app.setReviewedBy(null);
    app.setReviewedAt(null);
    return PartnerApplicationResponse.from(applicationRepo.save(app));
}
// no existing row — insert, same as today
```

No schema change — `PartnerApplication.userId` stays non-nullable (both
branches guarantee a real user exists by the time this runs), and there's no
DB-level unique constraint on `user_id` to add or worry about (confirmed:
`V36__add_partner_applications.sql` has none) — the upsert logic is the only
thing enforcing one-row-per-user, same as `findByUserId`'s singular return
type already implied.

Admin's `approve`/`reject` flows (`PartnerApplicationServiceImpl.approve`/
`reject`) are unchanged — they already operate on exactly one row by
`applicationId`, and reuse-the-same-row means there's never ambiguity about
which attempt a given `applicationId` refers to.

## 4. Backend — Self-Service Status Endpoint

**`GET /partners/applications/me`** — authenticated (not public; reachable
immediately after an anonymous apply, since that branch logs the new
account in). Add to `PartnerController`:

```java
@Operation(summary = "Get the authenticated user's own partner application, if any")
@GetMapping("/applications/me")
public ResponseEntity<PartnerApplicationResponse> getMyApplication(
        @AuthenticationPrincipal UserDetails principal) {
    UUID userId = CurrentUser.id(principal);
    return ResponseEntity.ok(applicationService.getByUserId(userId));
}
```

New `PartnerApplicationService.getByUserId(UUID)` → `applicationRepo.findByUserId(userId).map(PartnerApplicationResponse::from).orElseThrow(() -> new ResourceNotFoundException("No application found"))`
→ 404 when the user has never applied (frontend treats 404 as "go to
`/apply`"). No new DTO — `PartnerApplicationResponse` already carries
`status` and `rejectionReason`.

This method needs its own `@PreAuthorize("isAuthenticated()")` override,
not the class-level `hasRole('PARTNER')` default — the whole point is
letting a non-partner applicant check their own pending/rejected status
(same override pattern `apply()` already uses today, just a different
rule).

## 5. Partner-web — Apply Form Rework

**`ApplyForm.tsx`** gains a conditional "Account" section, rendered only
when the visitor has no session (checked the same way
`require-partner.ts`/`getCurrentUserRoles()` already does — an empty roles
array with no thrown error, vs. a populated one):

- Anonymous: "Account" section (`firstName`, `lastName`, `email`,
  `password`, + client-only confirm-password) above the existing "Company"
  section (`companyName`, `websiteUrl`, `description`). One form, one submit.
- Logged in: only the "Company" section renders — identical to today's form.

**`schemas/schema.ts`** splits into a base `companySchema` (today's
`applicationSchema`, unchanged) and an `accountSchema` (email/password/name,
mirroring the password-complexity pattern `RegisterRequest` enforces
server-side), composed conditionally in the form rather than one flat schema
with always-optional account fields — so a logged-in user's submission can
never be blocked by a stray account-field validation error.

**BFF route** (`src/app/api/partners/applications/route.ts`) currently
validates against the company-only `applicationSchema` and forwards exactly
those three fields — it must accept and forward the account fields too, when
present:

```ts
const parsed = applicationSchema.merge(accountSchema.partial()).safeParse(body);
// ...
const resp = await applyDal(getCookieHeader(req), {
  companyName: parsed.data.companyName,
  description: parsed.data.description || undefined,
  websiteUrl: parsed.data.websiteUrl || undefined,
  email: parsed.data.email,
  password: parsed.data.password,
  firstName: parsed.data.firstName,
  lastName: parsed.data.lastName,
});
```

`applyDal`'s response already needs to propagate `Set-Cookie` back to the
browser for the anonymous branch's new session to take effect client-side —
confirm this falls out of the existing `buildProxyResponse`/`backendFetch`
machinery (the same machinery `/auth/register`'s own BFF route already
relies on for its cookie-setting to work) rather than building new plumbing.

**On success:** both branches redirect to the new `/status` page (Section
6) — replacing today's local-only `ApplicationSubmittedState` component
(`ApplyPageClient.tsx`'s `submitted` state), which only persists for the
current browser session and is lost on reload. `/status` is a real,
revisitable page backed by the new endpoint, so this component and its
"Refresh status" workaround (`ApplicationSubmittedState.tsx`, whose own
comment already says *"There's no backend endpoint to check an application's
status"*) are deleted, not kept alongside the new page.

## 6. Partner-web — Status Page & Routing

**New `/status` page** (`src/app/(auth)/status/page.tsx`, alongside
`/apply`), server-fetches `GET /partners/applications/me`:

- **No session at all** (anonymous visitor landed here directly — the fetch
  itself 401s, since this backend endpoint stays authenticated) → redirect
  to `/apply`, same destination as the 404 case below, since neither
  outcome gives this visitor anything to show.
- **404** → redirect to `/apply` (session exists, but no application).
- **PENDING** → "Your application is under review" — no action.
- **REJECTED** → shows `rejectionReason`; a "Reapply" link to `/apply`
  carries the rejected application's `companyName`/`websiteUrl`/`description`
  (already in the same response) so the form can pre-fill them — simplest
  passed via query params or a short-lived client state set right before
  navigating, not a second fetch.
- **APPROVED** → transient fallback only ("Approved! Redirecting…") — real
  navigation happens because the next request's role check already sends an
  approved user to `/dashboard`; this state should rarely render.

**`apply/page.tsx`**'s existing "already a partner → `/dashboard`" check
expands to route by application state instead of just role:

```ts
const application = await getMyApplicationDal(); // null on 404
if (roles.includes("ROLE_PARTNER")) redirect("/dashboard");
if (application) redirect("/status");
// else: no application yet — render the form
```

**`require-partner.ts`**'s blanket "any non-partner → `/apply`" becomes the
same state-aware check, so a non-partner with a PENDING/REJECTED application
lands on `/status`, not back on a form they already submitted.

**`proxy.ts` fix (required, not optional):** the middleware's
`isAuthenticated && isPublicPath` rule currently bounces *any* logged-in
visitor away from both `/login` and `/apply` straight to `/dashboard`,
unconditionally. Combined with `require-partner.ts` sending non-partners *to*
`/apply`, a logged-in non-partner hitting any gated page today would
infinite-redirect-loop the moment they also try to visit `/apply` directly
(middleware bounces them to `/dashboard`, whose own partner-check bounces
them back). This must narrow to `/login` only — `/apply` (and the new
`/status`) must stay reachable by an authenticated non-partner, since that's
the entire point of this feature:

```diff
- const PUBLIC_PATHS = new Set(["/login", "/apply"]);
+ const PUBLIC_PATHS = new Set(["/login", "/apply", "/status"]);
+ const BOUNCE_WHEN_AUTHENTICATED = new Set(["/login"]);
```

```diff
- if (isAuthenticated && isPublicPath) {
+ if (isAuthenticated && BOUNCE_WHEN_AUTHENTICATED.has(pathname)) {
```

(`/status` joins `PUBLIC_PATHS` too, even though its backend call requires
auth — an anonymous visitor hitting `/status` should see the page attempt
its server-side fetch and 404-redirect to `/apply` via the page's own logic,
not get intercepted by middleware with a generic `/login` redirect that loses
context about why they're there.)

## 7. Testing & Verification

**Backend:**
- Unit tests for `apply`'s branching: authenticated + no existing row
  (insert), authenticated + REJECTED row (reset-and-reuse), authenticated +
  PENDING/APPROVED row (rejected with the matching error), anonymous + new
  email (creates account, calls `authService.register`, sets cookies),
  anonymous + duplicate email (409).
- Unit tests for `GET /applications/me`: 404 when none, correct
  status/rejectionReason passthrough for PENDING/REJECTED/APPROVED.
- Regression test: reapplying resets the *same* row (no second row created;
  `rejectionReason`/`reviewedBy`/`reviewedAt` cleared).
- Integration test confirming `/partners/applications` is reachable with
  *no* `Authorization`/cookie at all through the real Spring Security filter
  chain (not a `@WebMvcTest` with `addFilters=false`) — closing the same
  class of gap flagged during the trailer-resilience work, this time from
  the start rather than as a follow-up fix.

**Frontend (partner-web):** no test framework in this repo (consistent with
the rest of the project) — verification is `npx tsc --noEmit` + manual
dev-server checks: anonymous apply (full form, lands on `/status` logged
in), logged-in apply (company-only form), `/status` in each state, reapply
pre-fill, and the corrected `proxy.ts`/`require-partner.ts` routing for a
logged-in non-partner (no more loop).

## Non-goals

- No change to admin's review UI or `PartnerPromotionService` — approval
  still just grants `ROLE_PARTNER` + profile, exactly as today.
- No change to how an *already-a-partner* user's profile/dashboard works.
- Email verification enforcement for the new account follows whatever the
  rest of the platform already does for `/auth/register` — this spec doesn't
  add or relax any verification-gating beyond that; "partner access requires
  admin approval" is already true today (`require-partner.ts`) and continues
  unchanged regardless of verification state.
