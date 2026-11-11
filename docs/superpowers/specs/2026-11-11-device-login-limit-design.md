# Multi-Device Login Limit — Design

## Background

CLAUDE.md: "multiple device login for Gold plan, and device control to ensure it
doesn't pass number of device."

Current state: the device cap is already enforced and already correct numerically.
`SessionServiceImpl.createSession()` (`SessionServiceImpl.java:46-80`) checks active
session count against `UserSubscription.maxDevices` (FREE=1, SILVER=1, GOLD=3, seeded
in `V5__add_subscription_tables.sql`) and, if at the limit, **silently revokes the
oldest active session** before creating the new one. A login never fails and the
user is never asked anything.

Per decision during brainstorming, this silent-swap behavior is replaced with an
explicit choice at login time, plus a separate self-service devices page for
out-of-band management.

## Part A — Device picker at login

### API contract change: `POST /auth/login`

- Credentials validate exactly as today.
- Active session count is checked **before** any session is created (previously the
  check and the create/evict happened together inside `createSession`).
- **Under the limit**: unchanged — session created, cookies issued, `200` with the
  profile response. No behavior change for the common case (includes `register()`,
  which always has a count of 0 and never reaches this path).
- **At the limit**: no session is created. Respond `409 Conflict`:
  ```
  {
    "error": "DEVICE_LIMIT_REACHED",
    "maxDevices": 3,
    "resolutionToken": "<short-lived signed token>",
    "devices": [
      { "sessionId": "...", "deviceName": "Chrome on Windows", "ipAddress": "...", "lastUsedAt": "..." },
      ...
    ]
  }
  ```
  `deviceName` reuses the existing `DeviceNameParser` (UA-derived label). No
  "location" field — per decision during brainstorming, no GeoIP capability exists
  in this codebase and adding one is out of scope here (a separate follow-up if
  wanted later).
- Applies uniformly across all plans, not just GOLD — same code path, just a
  different `maxDevices` value. A SILVER/FREE user hitting their 1-device limit gets
  the same picker instead of the old silent swap.

### `resolutionToken`

A short-lived (~5 minute), signed, purpose-scoped token carrying the userId, reusing
the existing `JwtTokenProvider`/`AbstractJwtTokenProvider` signing infrastructure
(not a new token system) with a distinct purpose claim so it can't be used as a real
access/refresh token. Stateless — no server-side record needed to issue it.

### New endpoint: `POST /auth/login/resolve-device-limit`

Body: `{ resolutionToken, sessionIdToRevoke }`.

1. Verify the token's signature, expiry, and purpose claim; extract `userId`.
2. Verify `sessionIdToRevoke` belongs to that `userId` (ownership check — reject
   otherwise, never act on another user's session).
3. **Single-use enforcement**: mark the token's `jti` as consumed via
   `cacheService.setIfAbsent` (same pattern as `SubscriptionExpirationJob`'s lock),
   TTL matching the token's own expiry. A replayed token is rejected — can't be used
   to spawn a second session.
4. Revoke `sessionIdToRevoke`, then create the new session exactly like the normal
   login path (refresh token, cookies, profile) — same response shape as a normal
   successful login, `200`.
5. Fire `DEVICE_LOGGED_OUT` (new `NotificationEventType`, IN_APP + EMAIL via the
   generalized `sendNotification()` from the new-content-notifications spec) to the
   evicted session's **user**, naming the new device so they can tell if it was
   really them. This replaces the old silent-eviction code path as the trigger
   point — the silent path no longer exists after this change.

### Frontend

The login mutation distinguishes the `409 DEVICE_LIMIT_REACHED` shape from a plain
credential failure (401/400). On it, show a modal listing the returned devices;
user picks one; client calls `resolve-device-limit` with the chosen `sessionId` and
the carried `resolutionToken`; on success, complete login exactly as the normal path
(store profile, redirect).

### Edge cases

- User abandons the picker → nothing was created; the stateless token simply expires
  after 5 minutes.
- Token expires before they pick → resolve call fails with a clear "selection
  expired, log in again" message — no partial state to clean up, no session was ever
  created.
- Chosen session already gone by resolve time (evicted/expired by another concurrent
  action) → treat as success anyway, proceed to create the new session rather than
  erroring on an already-satisfied intent.
- Boot mechanism for the evicted device: **passive**, per decision during
  brainstorming — no WebSocket/push infrastructure exists in this codebase and
  building one is out of scope (a much larger, separate effort). The evicted
  session's refresh token is already revoked the moment the swap happens; its next
  API call or token refresh gets a `401` and the client's existing auth-error
  handling redirects to login.

## Part B — Self-service Manage Devices page

Independent of Part A — lets a user view/revoke their own devices anytime while
already logged in, not just at the moment of hitting the limit.

### Bug found and fixed: `BackendUser.sessions` type mismatch

`tinniestudio-client-web/src/types/auth.type.ts` declares `BackendUser.sessions:
DeviceSession[]` at the top level. The actual `/auth/me` response (backend
`AuthProfileResponse`, `AuthProfileResponse.java`) nests sessions under `devices: {
active, max, sessions }` — confirmed the BFF route (`src/app/api/auth/me/route.ts`)
is a pure passthrough proxy, no reshaping. So `user.sessions` has always been
`undefined` at runtime — almost certainly why no devices UI was ever built against
it (it never actually worked), not merely that nobody got to it. Note:
`AuthProfileResponse`'s session-marking logic is correct (`current` is properly set
by comparing against the caller's own session id, `AuthProfileResponse.java:47-55`)
— this is a different, separate `SessionDto.current` bug exists in the unrelated
`/users/me` endpoint (`UserProfileResponse`, hardcoded `false`), which this spec does
not touch since nothing in the frontend consumes that path for sessions.

**Fix**: correct `BackendUser` to match the real shape —
`devices: { active: number; max: number; sessions: DeviceSession[] }` — and update
whatever reads `user.sessions` (currently nothing, confirmed via repo-wide search) to
read `user.devices.sessions` instead.

### New endpoint: `POST /auth/sessions/{sessionId}/revoke`

Self-service counterpart to the existing admin-only `SessionService.revokeSession`.
Scoped to the caller:
- Ownership check — `404` if the session doesn't belong to the caller (no existence
  leak).
- Rejects revoking the caller's own *current* session with a clear message pointing
  at the existing logout flow instead (revoking your own active session while using
  it is a confusing, already-solved case).
- Idempotent against an already-revoked session — no-op success, not an error.

### UI

New "Manage Devices" section in `ProfilePanelSecurity.tsx` (the existing
account-security tab): device list (name, IP, last-active, a "this device" badge for
`current`), an "X of Y devices" indicator from `devices.active`/`devices.max`, and a
revoke button per row — disabled/hidden on the current-device row.

## Testing

**Backend:**
- Login under the limit: unchanged behavior (regression test against the pre-change
  path).
- Login at the limit: `409` with the correct device list, correct `maxDevices`, and
  no session row created.
- `resolve-device-limit` happy path: revokes the chosen session, creates the new one,
  returns a normal `200` profile.
- `resolve-device-limit` with an expired, tampered, wrong-purpose, or replayed token:
  each rejected.
- `resolve-device-limit` with a `sessionIdToRevoke` not owned by the token's user:
  rejected.
- `DEVICE_LOGGED_OUT` notification fires to the evicted user's both channels per
  their preference, naming the new device.
- Self-revoke: ownership check, current-session rejection, idempotency on an
  already-revoked session.
- `register()` path unaffected (count always 0 at signup).

**Frontend** (type-check + lint + manual — no test framework in this repo):
- A 4th login attempt on a 3-device-limit account shows the picker with the correct
  3 devices; completing it finishes login normally; the evicted device's next API
  call 401s and redirects to login.
- Manage Devices page renders counts/badges correctly; revoke removes the row from
  the list; the current-device row has no (or a disabled) revoke action.
- Type-check passes with the corrected `BackendUser.devices` shape and its updated
  call site.
