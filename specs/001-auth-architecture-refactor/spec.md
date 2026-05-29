# Feature Specification: Multi-Actor Auth Architecture Refactor

**Feature Branch**: `001-auth-architecture-refactor`

**Created**: 2026-05-29

**Status**: Draft

**Input**: User description: "Multi-actor auth, RBAC, subscription-aware session management, admin isolation, device/session orchestration, refresh token governance, /auth/me aggregation, super admin bootstrap"

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Admin System Bootstrap (Priority: P1)

A platform operator needs to create the first super admin account securely before any admin operations can occur. They provide a cryptographically random bootstrap token set in the server environment, along with their email and password, and receive a fully credentialed super admin account.

**Why this priority**: No admin functionality can be used — including creating sub-admins or managing users — until a super admin exists. This is the prerequisite for all subsequent admin operations.

**Independent Test**: Can be tested end-to-end by setting the bootstrap env token, calling the bootstrap endpoint, and verifying the super admin can subsequently log in and access admin-only routes.

**Acceptance Scenarios**:

1. **Given** `ADMIN_BOOTSTRAP_TOKEN` is set in environment and no super admin exists, **When** a request is submitted with the matching token, email, and password, **Then** a super admin account is created and the endpoint becomes permanently disabled.
2. **Given** a super admin already exists, **When** the bootstrap endpoint is called, **Then** the request is rejected with an error indicating the endpoint is no longer available.
3. **Given** an incorrect bootstrap token is submitted, **When** the endpoint receives the request, **Then** the request is rejected without revealing whether a super admin exists.

---

### User Story 2 - Separate Admin Authentication Flow (Priority: P1)

An admin logs in via a dedicated admin login endpoint, receives admin-scoped JWT tokens stored in separate cookies, and can only access admin-protected routes. A regular user JWT cannot grant access to any admin endpoint, even if the user's account is somehow compromised.

**Why this priority**: Security boundary between admin and user auth is fundamental — all admin functionality depends on this isolation being in place and correct.

**Independent Test**: Can be tested by verifying admin login issues tokens with the `admin` audience claim, that user tokens are rejected on admin endpoints, and that admin tokens are rejected on user endpoints.

**Acceptance Scenarios**:

1. **Given** valid admin credentials, **When** the admin logs in, **Then** separate `admin_access_token` and `admin_refresh_token` cookies are set, scoped to admin routes only.
2. **Given** a user access token, **When** it is submitted to any `/auth/admin/**` endpoint, **Then** it is rejected with a 401 even if the underlying user account has elevated roles.
3. **Given** an admin access token, **When** it is submitted to a user-only endpoint, **Then** it is rejected.
4. **Given** an admin successfully logs in when a previous admin session exists, **When** the new login is issued, **Then** the previous admin session is automatically revoked.

---

### User Story 3 - Sub-Admin Creation by Super Admin (Priority: P2)

A super admin creates a new admin account (MODERATOR role) for a new hire. The new admin can then log in and access moderator-level admin routes.

**Why this priority**: Without the ability to delegate admin access, the super admin becomes a bottleneck and single point of failure for all platform management operations.

**Independent Test**: Can be tested by logging in as super admin, registering a moderator, logging in as that moderator, and verifying route access matches the MODERATOR role grants.

**Acceptance Scenarios**:

1. **Given** an authenticated super admin, **When** a register-admin request is submitted with valid details, **Then** a new admin account is created with the specified role.
2. **Given** an authenticated moderator (non-super-admin), **When** an attempt is made to register a new admin, **Then** the request is rejected with a 403.
3. **Given** an attempt to create a second super admin, **When** the request is submitted by the existing super admin, **Then** the request is rejected — only one super admin is permitted in the system.

---

### User Story 4 - Subscription-Aware Device Session Enforcement (Priority: P2)

A user on a free plan logs into a second device. The system automatically revokes the oldest active session to enforce the one-device limit for their plan, and the previously active device's refresh token stops working.

**Why this priority**: Device limits are a core monetization boundary. Without enforcement, free users have unlimited concurrent access, undermining paid tier value.

**Independent Test**: Can be tested by logging in from two separate sessions under a free plan account and verifying the first session's refresh token is invalidated after the second login.

**Acceptance Scenarios**:

1. **Given** a free-plan user with one active session, **When** they log in from a second device, **Then** the oldest session is revoked and the new session is created — total active sessions never exceeds the plan limit.
2. **Given** a gold-plan user with three active sessions, **When** they attempt to log in from a fourth device, **Then** the oldest session is revoked and the new session is created.
3. **Given** a user attempts to refresh using a revoked session's token, **When** the refresh request is made, **Then** a 401 is returned and no new tokens are issued.

---

### User Story 5 - Refresh Token Rotation and Revocation (Priority: P2)

A user refreshes their access token. The old refresh token is immediately invalidated. If the same old refresh token is reused (replay attack), the system detects it and revokes the entire session.

**Why this priority**: Without token rotation, stolen refresh tokens remain valid indefinitely. Replay detection is the primary defense against session hijacking.

**Independent Test**: Can be tested by refreshing once, then attempting to use the original refresh token again and confirming the session is fully revoked.

**Acceptance Scenarios**:

1. **Given** a valid refresh token, **When** a refresh request is made, **Then** a new access token is issued and a new refresh token is set — the old refresh token is invalidated.
2. **Given** a replay of a previously rotated refresh token, **When** the stale token is submitted, **Then** the entire session is revoked and a 401 is returned.
3. **Given** a valid admin refresh token, **When** an admin refresh request is made, **Then** the same rotation and replay-detection rules apply.

---

### User Story 6 - Enriched `/auth/me` Profile Aggregation (Priority: P2)

A logged-in user calls `/auth/me` and receives a single response containing their identity, active subscription details (plan, device limits, content quota), and a list of their active sessions with device names and which session is current.

**Why this priority**: The frontend requires this unified view for onboarding screens, account settings, and subscription upgrade prompts. Piecemeal queries from multiple endpoints create latency and frontend complexity.

**Independent Test**: Can be tested by calling `/auth/me` with a valid session and verifying all three data domains (identity, subscription, sessions) are present with correct values.

**Acceptance Scenarios**:

1. **Given** an authenticated user with an active subscription and sessions, **When** `/auth/me` is called, **Then** the response includes user identity, subscription plan info, content quota status, and a sessions list with the current session flagged.
2. **Given** a free-plan user who has used 1 of 2 content watches, **When** `/auth/me` is called, **Then** `contentWatchesUsed: 1`, `contentWatchesLimit: 2`, `canWatch: true` are returned.
3. **Given** a free-plan user who has exhausted their content quota, **When** `/auth/me` is called, **Then** `canWatch: false` is returned.

---

### User Story 7 - Free Tier Content Quota Enforcement (Priority: P3)

A free-plan user attempts to stream content after using their 2-watch lifetime quota. The request is blocked with a clear error prompting them to upgrade.

**Why this priority**: Content quota is the primary conversion lever. Its correct enforcement is required before launching the platform commercially.

**Independent Test**: Can be tested by exhausting a free-plan account's quota and confirming subsequent stream attempts are blocked with `upgrade_required`.

**Acceptance Scenarios**:

1. **Given** a free-plan user under their quota, **When** a stream request is made, **Then** the request is allowed and the watch counter increments immediately.
2. **Given** a free-plan user at their quota limit, **When** a stream request is made, **Then** a 403 with `reason: "upgrade_required"` is returned.
3. **Given** an admin sets a plan's content limit to 0, **When** any free-plan user attempts to stream, **Then** all free-tier stream requests are blocked.

---

### User Story 8 - Admin Force-Logout and Session Revocation (Priority: P3)

An admin revokes all active sessions for a specific user (e.g., after a security incident). The user is immediately logged out from all devices, and any in-flight access tokens expire within 15 minutes.

**Why this priority**: Session revocation is a compliance and security capability required for incident response. Without it, admins cannot contain compromised accounts.

**Independent Test**: Can be tested by admin-revoking all sessions for a user, then verifying the user's refresh token returns 401 immediately.

**Acceptance Scenarios**:

1. **Given** an admin triggers a global logout for a user, **When** the command is executed, **Then** all active sessions for that user are revoked in both the database and cache — refresh tokens immediately return 401.
2. **Given** a user has had sessions revoked by an admin, **When** they attempt to use an already-issued access token (under 15 min old), **Then** the access token still works until it expires (accepted tradeoff — access tokens are stateless).
3. **Given** an admin revokes a single specific session by session ID, **When** the user attempts to refresh from that device, **Then** only that session is invalidated; other sessions remain active.

---

### User Story 9 - Coupon Application at Plan Upgrade (Priority: P3)

A user selects a paid plan during checkout and enters a coupon code. The system validates the coupon (active, within date window, usage limit not reached, not previously used by this user), applies the discount, and records the redemption.

**Why this priority**: Coupon-driven acquisition is a planned growth mechanism. Incorrect validation (accepting expired/exhausted codes) creates revenue loss.

**Independent Test**: Can be tested by applying a valid coupon, verifying the discount is reflected, then attempting to reuse the same coupon on the same user account and confirming rejection.

**Acceptance Scenarios**:

1. **Given** a valid, active coupon within its date window with remaining uses, **When** a user submits it at checkout, **Then** the discount is applied and the coupon's use count increments.
2. **Given** a coupon already used by this user, **When** the same user submits it again, **Then** it is rejected with `reason: "already_used"`.
3. **Given** an expired coupon, **When** any user submits it, **Then** it is rejected with `reason: "expired"`.
4. **Given** a coupon that has reached its maximum use count, **When** any user submits it, **Then** it is rejected with `reason: "limit_reached"`.

---

### Edge Cases

- What happens when a user's subscription plan changes mid-session (e.g., downgrade from Gold to Free)? Active sessions exceeding the new limit should be revoked on next refresh, not immediately.
- What happens if Redis is unavailable during a refresh-token check? The system must fall back to the database as the authoritative session store rather than returning 500 or incorrectly accepting the request.
- What happens if an admin password reset token is valid but the new password fails validation (too weak)? The token must be invalidated on that failure attempt.
- What happens when the bootstrap endpoint is called after the bootstrap token env var is removed? The endpoint must return an appropriate error (disabled or not found) without leaking system state.
- What happens if a user has no active UserSubscription? The system must handle this gracefully, defaulting to FREE-tier behavior.

---

## Requirements *(mandatory)*

### Functional Requirements

#### Admin System

- **FR-001**: System MUST maintain a separate `admins` table isolated from the `users` table, with its own authentication flow, tokens, cookies, and sessions.
- **FR-002**: System MUST enforce that exactly one super admin exists at any time; attempts to create a second super admin must be rejected.
- **FR-003**: System MUST provide a one-time bootstrap endpoint (`POST /auth/admin/bootstrap`) that creates the initial super admin, gated by an environment-supplied token, and permanently disabled after first successful use.
- **FR-004**: System MUST restrict admin sub-account creation to the super admin role only.
- **FR-005**: System MUST issue admin JWT tokens with a separate audience claim (`aud: "admin"`) and a separate signing secret, making admin tokens cryptographically distinct from user tokens.
- **FR-006**: System MUST store admin session state (refresh tokens) and enforce a single active session per admin — new login revokes the previous admin session.
- **FR-007**: Admin password reset MUST invalidate the reset token on any failed attempt (including weak-password validation failures); reset tokens expire in 15 minutes; rate-limited to 1 request per 60 minutes per IP; successful reset revokes all active admin sessions and notifies the super admin by email.

#### User Auth

- **FR-008**: System MUST preserve all existing user auth endpoints and response shapes (no breaking changes to `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/verify-email`, `/auth/forgot-password`, `/auth/reset-password`, OAuth2 endpoints).
- **FR-009**: System MUST create a `UserSession` record on every successful login, storing a hashed refresh token, device fingerprint (SHA-256 of User-Agent + IP), device name, IP address, and expiry.
- **FR-010**: System MUST enforce device limits per subscription plan on login: when the active session count equals or exceeds the plan's `maxDevices`, the oldest session must be automatically revoked before the new session is created.
- **FR-011**: System MUST rotate the refresh token on every refresh request; replaying a previously rotated refresh token must trigger immediate full session revocation.
- **FR-012**: System MUST validate refresh tokens against the session store (cache first, then database) — a session not found in either store must be rejected with a 401.
- **FR-013**: System MUST revoke user sessions (individual or all) upon admin instruction, with immediate effect on refresh token acceptance.

#### RBAC

- **FR-014**: User roles MUST be array-based (`ROLE_USER`, `ROLE_PARTNER`) stored via a join table, allowing multiple roles per user.
- **FR-015**: Admin roles MUST be array-based (`SUPER_ADMIN`, `MODERATOR`) stored via an element collection on the Admin entity.
- **FR-016**: `ROLE_ADMIN` and `ROLE_SUPER_ADMIN` MUST be removed from the user role enum; admin roles exist only on the Admin entity.

#### Session & Cache

- **FR-017**: Session state MUST be stored in both the database (primary — for audit and persistence) and a fast cache (for revocation checks), using key scheme `session:{userId}:{sessionId}` with a 7-day TTL.
- **FR-018**: Access token validation MUST remain fully stateless (signature + expiry check only) — no database or cache lookup per request.
- **FR-019**: On logout, the system MUST revoke the session in the database and purge the corresponding cache entry.

#### `/auth/me` Aggregation

- **FR-020**: `GET /auth/me` MUST return an enriched response including: user identity fields, subscription plan details (plan name, status, maxDevices, contentWatchesUsed, contentWatchesLimit, canWatch, expiresAt), and a list of active sessions with device names, IPs, last-used timestamps, and a `current: true` flag on the calling session.
- **FR-021**: The response MUST be additive — all fields present in the current `/auth/me` response must remain present.

#### Content Quota

- **FR-022**: System MUST gate streaming access via `CapabilityService.canWatch(userId)`, returning `false` when the user's `contentWatchesUsed >= plan.contentLimit` (and `contentLimit` is not null).
- **FR-023**: The content watch counter MUST increment immediately upon stream request, not on completion.
- **FR-024**: The content limit MUST be configurable per subscription plan by admins; setting it to `0` disables free-tier content access entirely.
- **FR-025**: When a user registers, a FREE-plan `UserSubscription` MUST be auto-created with `contentWatchesUsed = 0`.

#### Coupon System

- **FR-026**: System MUST validate coupons against four rules before applying: active status, valid date window, remaining usage capacity, and uniqueness per user.
- **FR-027**: On successful payment, the system MUST atomically create the subscription, increment the coupon's use count, and record the redemption — ensuring a single user cannot redeem the same coupon twice.
- **FR-028**: Invalid coupons MUST return a 400 with a specific machine-readable reason: `expired`, `already_used`, `not_found`, or `limit_reached`.

### Key Entities

- **Admin**: Platform administrator with separate identity, credentials, roles (SUPER_ADMIN / MODERATOR), and session lifecycle from users.
- **AdminSession**: Tracks a single active admin session with a hashed refresh token, IP, and revocation state.
- **UserSession**: Tracks an active user session across devices, including device fingerprint, device name, refresh token hash, and revocation state.
- **SubscriptionPlan**: Defines plan-level constraints including `maxDevices` and `contentLimit` (admin-editable).
- **UserSubscription**: Links a user to a plan, tracking `contentWatchesUsed` and plan status.
- **Coupon**: A discount code with type (percentage/fixed), usage limits, date window, and active status.
- **CouponRedemption**: Join record ensuring exactly one redemption per user per coupon, linked to the subscription created at upgrade.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user JWT token cannot be accepted by any admin-gated endpoint — zero false-positive admin authorizations across all test scenarios.
- **SC-002**: Free-plan users are blocked from simultaneous multi-device access — no more than 1 concurrent active session per free-plan account at any time.
- **SC-003**: A replayed rotated refresh token triggers session revocation within the same request cycle — no window exists where a stolen rotated token is accepted.
- **SC-004**: The `/auth/me` endpoint returns all three data domains (identity, subscription, sessions) in a single round-trip — no additional endpoint calls required by the frontend for a complete user profile.
- **SC-005**: Free-tier content quota enforcement blocks the 3rd stream attempt for a new user within the same session — no bypass via concurrent requests.
- **SC-006**: Admin force-logout invalidates refresh tokens immediately — subsequent refresh attempts for the revoked session return 401 in under 100ms.
- **SC-007**: The bootstrap endpoint can only be successfully called once — all subsequent calls return an error regardless of token validity.
- **SC-008**: Coupon validation rejects a previously redeemed coupon for the same user — no duplicate redemptions are possible even under concurrent checkout attempts.
- **SC-009**: All existing `/auth/**` endpoint contracts (request/response shapes, HTTP status codes) remain unchanged — zero frontend breaking changes introduced by the refactor.

---

## Assumptions

- The existing Spring Boot modular structure (`modules/auth/`, `modules/user/`, `modules/billing/`) will be preserved; admin auth lives in `modules/auth/admin/`.
- The plan device limits are: FREE=1, SILVER=1, GOLD=3, with admin per-user override capability.
- Access tokens have a 15-minute lifetime; refresh tokens have a 7-day lifetime — these values are environment-configurable.
- Device fingerprinting (SHA-256 of User-Agent + IP) is used for display purposes only (device name labeling in session list) and not for security decisions — a fingerprint change never blocks a login.
- The free-tier content quota defaults to 2 lifetime watches; this value is admin-configurable per plan.
- WebSocket session invalidation is out of scope for this refactor and will be addressed in a future phase.
- Stripe / payment processor integration is out of scope; the coupon system defines the validation and redemption contract only.
- Suspicious login tracking and admin session analytics dashboards are out of scope for this phase.
- Email notification infrastructure (for admin password reset alerts to super admin) is assumed to already exist or will be wired to an existing email service.
- The `FREE_TIER_CONTENT_LIMIT` environment variable serves as a fallback default when the plan's `contentLimit` is not set.
