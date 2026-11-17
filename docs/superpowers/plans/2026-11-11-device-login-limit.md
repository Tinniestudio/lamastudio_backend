# Device Login Limit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the silent "evict the oldest session" behavior on login with an explicit 409 + device-picker flow (user chooses which device to sign out), and add a self-service "Manage Devices" screen so users can review and revoke their own sessions at any time.

**Architecture:** Part A (backend) splits session-limit checking out of `SessionServiceImpl.createSession` and into `AuthService.login()`, which now throws a new `DeviceLimitReachedException` carrying a short-lived single-use `resolutionToken` (signed via the existing `JwtTokenProvider` key, a new "purpose" claim) plus the caller's current device list. A new public `POST /auth/login/resolve-device-limit` endpoint consumes that token, revokes the chosen session, creates the new one, and fires a `DEVICE_LOGGED_OUT` notification through the existing single `@RabbitListener`. Part B (backend) adds a self-service `POST /auth/sessions/{sessionId}/revoke` endpoint with ownership/current-session/idempotency rules. Part C (frontend) widens two response layers (`backendFetch.ts`, `ApiError`) that currently drop a 409's extra fields, fixes the `BackendUser.sessions` → `BackendUser.devices.{active,max,sessions}` type mismatch, adds a `DevicePickerModal` wired into `LoginClient.tsx`, and adds a "Manage Devices" card to `ProfilePanelSecurity.tsx`.

**Tech Stack:** Spring Boot / Java / JPA / RabbitMQ / jjwt (server), Next.js / React / TypeScript / React Query (tinniestudio-client-web), JUnit 5 + Mockito (backend tests), manual + `tsc --noEmit` + `eslint` (frontend — no test framework in this repo).

**Spec:** `docs/superpowers/specs/2026-11-11-device-login-limit-design.md`

---

## Part A — Login-time device limit (409 + picker + resolve)

### Task 1: `SessionServiceImpl` — remove silent eviction, expose limit-check methods

**Files:**
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/auth/user/service/SessionService.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/auth/user/service/SessionServiceImpl.java`
- Create: `server/api-service/src/test/java/com/tinniestudio/api/modules/auth/user/service/SessionServiceImplTest.java`

- [ ] **Step 1: Write the failing tests**

Create `SessionServiceImplTest.java`:

```java
package com.tinniestudio.api.modules.auth.user.service;

import com.tinniestudio.api.modules.auth.user.entity.UserSession;
import com.tinniestudio.api.modules.auth.user.repository.UserSessionRepository;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.shared.cache.CacheService;
import com.tinniestudio.api.shared.entity.DomainEnums.SubscriptionStatus;
import com.tinniestudio.api.shared.entity.UserSubscription;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceImplTest {

    @Mock private UserSessionRepository userSessionRepository;
    @Mock private UserSubscriptionRepository userSubscriptionRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private CacheService cacheService;

    private SessionServiceImpl sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionServiceImpl(
                userSessionRepository, userSubscriptionRepository, passwordEncoder, cacheService);
    }

    @Test
    @DisplayName("createSession no longer evicts — just creates, even past the old limit")
    void createSession_doesNotEvictAnyoneRegardlessOfCount() {
        UUID userId = UUID.randomUUID();
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userSessionRepository.save(any(UserSession.class))).thenAnswer(inv -> {
            UserSession s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0) Chrome/100.0");

        sessionService.createSession(userId, "raw-token", request);

        verify(userSessionRepository, never()).findFirstByUserIdAndRevokedFalseOrderByCreatedAtAsc(any());
        verify(userSessionRepository, never()).save(argThat(UserSession::isRevoked));
    }

    @Test
    @DisplayName("countActiveSessions delegates to the repository's active count")
    void countActiveSessions_delegatesToRepository() {
        UUID userId = UUID.randomUUID();
        when(userSessionRepository.countByUserIdAndRevokedFalse(userId)).thenReturn(3L);

        assertThat(sessionService.countActiveSessions(userId)).isEqualTo(3L);
    }

    @Test
    @DisplayName("resolveMaxDevicesForUser reads the subscription's snapshotted maxDevices")
    void resolveMaxDevicesForUser_readsSnapshottedValue() {
        UUID userId = UUID.randomUUID();
        UserSubscription sub = new UserSubscription();
        sub.setMaxDevices(3);
        when(userSubscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub));

        assertThat(sessionService.resolveMaxDevicesForUser(userId)).isEqualTo(3);
    }

    @Test
    @DisplayName("resolveMaxDevicesForUser defaults to 1 when there is no active subscription")
    void resolveMaxDevicesForUser_defaultsToOne() {
        UUID userId = UUID.randomUUID();
        when(userSubscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(sessionService.resolveMaxDevicesForUser(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("selfRevokeSession: 404s when the session doesn't belong to the caller")
    void selfRevokeSession_wrongOwner_notFound() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(userSessionRepository.findByUserIdAndId(userId, sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.selfRevokeSession(userId, sessionId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Session not found");
    }

    @Test
    @DisplayName("selfRevokeSession: already-revoked session is an idempotent no-op")
    void selfRevokeSession_alreadyRevoked_isNoOp() {
        UUID userId = UUID.randomUUID();
        UserSession session = new UserSession();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.revoke(null);
        when(userSessionRepository.findByUserIdAndId(userId, session.getId())).thenReturn(Optional.of(session));

        sessionService.selfRevokeSession(userId, session.getId(), null);

        verify(userSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("selfRevokeSession: rejects revoking the caller's own current session")
    void selfRevokeSession_currentSession_rejected() {
        UUID userId = UUID.randomUUID();
        UserSession session = new UserSession();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        when(userSessionRepository.findByUserIdAndId(userId, session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> sessionService.selfRevokeSession(userId, session.getId(), session.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("current session");
    }

    @Test
    @DisplayName("selfRevokeSession: revokes a different, active, owned session and clears its cache key")
    void selfRevokeSession_success() {
        UUID userId = UUID.randomUUID();
        UUID currentSessionId = UUID.randomUUID();
        UserSession session = new UserSession();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        when(userSessionRepository.findByUserIdAndId(userId, session.getId())).thenReturn(Optional.of(session));

        sessionService.selfRevokeSession(userId, session.getId(), currentSessionId);

        assertThat(session.isRevoked()).isTrue();
        verify(userSessionRepository).save(session);
        verify(cacheService).delete(contains(session.getId().toString()));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run from `/home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server`:

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.auth.user.service.SessionServiceImplTest"
```

Expected: compile error (`countActiveSessions`, `resolveMaxDevicesForUser`, `selfRevokeSession` don't exist yet) or `UnsupportedOperationException`-style failures.

- [ ] **Step 3: Add the new methods to the `SessionService` interface**

In `SessionService.java`, add after `getActiveSessions`:

```java
    /**
     * Raw active-session count, used by the login flow to decide whether to block with a
     * device-limit conflict BEFORE creating a new session (unlike the old inline check inside
     * createSession, which created the session unconditionally after silently evicting).
     */
    long countActiveSessions(UUID userId);

    /**
     * Public wrapper around the subscription-plan-derived device cap, needed by AuthService to
     * build the 409 response body (maxDevices) before any session is touched.
     */
    int resolveMaxDevicesForUser(UUID userId);

    /**
     * Self-service revoke with three rules the admin-facing revokeSession() doesn't need:
     * (1) the session must belong to userId — a session that exists for a DIFFERENT user and one
     *     that doesn't exist at all are deliberately indistinguishable (404 either way, so this
     *     endpoint never leaks whether a given sessionId belongs to someone else);
     * (2) an already-revoked session is a successful no-op (idempotent — the caller's goal, "this
     *     device is signed out," is already true);
     * (3) the caller's OWN current session (currentSessionId) cannot be revoked this way — use
     *     logout instead, which also clears cookies.
     */
    void selfRevokeSession(UUID userId, UUID sessionId, UUID currentSessionId);
```

- [ ] **Step 4: Add the repository method they need**

In `UserSessionRepository.java`, add after `findByUserIdAndIdAndRevokedFalse`:

```java
    /**
     * Unlike findByUserIdAndIdAndRevokedFalse, this does NOT filter on revoked state — needed by
     * selfRevokeSession to distinguish "wrong owner" (empty here too) from "right owner, already
     * revoked" (present here, revoked=true), which the revoked-filtered query can't tell apart.
     */
    Optional<UserSession> findByUserIdAndId(UUID userId, UUID sessionId);
```

- [ ] **Step 5: Implement in `SessionServiceImpl`**

Replace the whole `createSession` method body (removing the eviction block) with:

```java
    @Override
    @Transactional
    public UUID createSession(UUID userId, String rawRefreshToken, HttpServletRequest request) {
        String userAgent = request != null ? request.getHeader("User-Agent") : null;
        String ipAddress = resolveClientIp(request);
        String fingerprint = buildFingerprint(userAgent, ipAddress);
        String deviceName = DeviceNameParser.parse(userAgent);

        UserSession session = new UserSession();
        session.setUserId(userId);
        session.setRefreshTokenHash(passwordEncoder.encode(rawRefreshToken));
        session.setDeviceFingerprint(fingerprint);
        session.setDeviceName(deviceName);
        session.setIpAddress(ipAddress);
        session.setExpiresAt(Instant.now().plus(SESSION_TTL));
        session = userSessionRepository.save(session);

        cacheService.set(sessionKey(userId, session.getId()), "1", SESSION_TTL);
        log.debug("Session created {} for user {}", session.getId(), userId);

        return session.getId();
    }
```

Add these three methods after `getActiveSessions` (before the `// ── Helpers ──` section):

```java
    // ── Device limit ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public long countActiveSessions(UUID userId) {
        return userSessionRepository.countByUserIdAndRevokedFalse(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public int resolveMaxDevicesForUser(UUID userId) {
        return resolveMaxDevices(userId);
    }

    @Override
    @Transactional
    public void selfRevokeSession(UUID userId, UUID sessionId, UUID currentSessionId) {
        UserSession session = userSessionRepository.findByUserIdAndId(userId, sessionId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Session not found"));

        if (session.isRevoked()) {
            return;
        }
        if (session.getId().equals(currentSessionId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Cannot revoke your current session this way — use logout instead");
        }

        session.revoke(null);
        userSessionRepository.save(session);
        cacheService.delete(sessionKey(userId, sessionId));
        log.info("User {} self-revoked session {}", userId, sessionId);
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.auth.user.service.SessionServiceImplTest"
```

Expected: all tests pass.

- [ ] **Step 7: Run the full module test suite to confirm nothing else broke**

```bash
./gradlew :api-service:test
```

Expected: same baseline pass/fail counts as before (plus the new test class), accepting the three pre-existing known-flaky failures (`ActuatorSecurityIntegrationTest.healthEndpointIsPubliclyAccessible`, `SubscriptionServiceTest$ProcessRenewal...anchorsNewPeriodToNowNotStaleRenewalDate`, intermittent `PartnerApplicationAuthIntegrationTest` rate-limiter flake) as baseline, not regressions.

- [ ] **Step 8: Commit**

```bash
git add server/api-service/src/main/java/com/tinniestudio/api/modules/auth/user/service/SessionService.java \
        server/api-service/src/main/java/com/tinniestudio/api/modules/auth/user/service/SessionServiceImpl.java \
        server/api-service/src/main/java/com/tinniestudio/api/modules/auth/user/repository/UserSessionRepository.java \
        server/api-service/src/test/java/com/tinniestudio/api/modules/auth/user/service/SessionServiceImplTest.java
git commit -m "feat: replace silent session eviction with explicit limit-check + self-revoke methods"
```

---

### Task 2: `JwtTokenProvider` — single-purpose device-limit resolution token

**Files:**
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/shared/security/jwt/JwtTokenProvider.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/auth/jwt/JwtTokenProviderTest.java`

- [ ] **Step 1: Write the failing tests**

Append to `JwtTokenProviderTest.java` (inside the `JwtTokenProviderTest` class):

```java
    @Test
    @DisplayName("generateDeviceLimitResolutionToken embeds the purpose claim and validates")
    void generateAndValidateDeviceLimitResolutionToken() {
        AppProperties props = buildProps(900_000L, 604_800_000L);
        JwtTokenProvider provider = new JwtTokenProvider(props);
        UUID userId = UUID.randomUUID();

        String token = provider.generateDeviceLimitResolutionToken(userId);

        assertThat(provider.validateDeviceLimitResolutionToken(token)).isTrue();
        Claims claims = provider.parseDeviceLimitResolutionToken(token);
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("purpose", String.class)).isEqualTo("device_limit_resolution");
    }

    @Test
    @DisplayName("validateDeviceLimitResolutionToken rejects a normal access token (wrong purpose)")
    void accessToken_isNotAValidResolutionToken() {
        AppProperties props = buildProps(900_000L, 604_800_000L);
        JwtTokenProvider provider = new JwtTokenProvider(props);
        User user = buildUser();

        String accessToken = provider.generateAccessToken(user, UUID.randomUUID());

        assertThat(provider.validateDeviceLimitResolutionToken(accessToken)).isFalse();
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.auth.jwt.JwtTokenProviderTest"
```

Expected: compile error — `generateDeviceLimitResolutionToken` etc. don't exist yet.

- [ ] **Step 3: Implement in `JwtTokenProvider.java`**

Add constants after `CLAIM_SESSION_ID`:

```java
    public static final String CLAIM_PURPOSE = "purpose";
    public static final String PURPOSE_DEVICE_LIMIT_RESOLUTION = "device_limit_resolution";
    private static final long DEVICE_LIMIT_RESOLUTION_EXPIRY_MS = 5 * 60 * 1000L;
```

Add methods after the Refresh Token section, before `// ── Expiry helpers ──`:

```java
    // ── Device Limit Resolution Token ────────────────────────────────────────
    // Single-use, 5-minute-lived token handed to the client in a 409 DEVICE_LIMIT_REACHED
    // response. Signed with the access-token key (no new secret needed) but carries a "purpose"
    // claim so it can never be mistaken for — or substituted with — a real access token: an
    // attacker who captured one couldn't use it to authenticate, only to complete the one
    // specific "pick a device to sign out" action it was issued for.

    public String generateDeviceLimitResolutionToken(UUID userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + DEVICE_LIMIT_RESOLUTION_EXPIRY_MS);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(appProperties.getJwt().getIssuer())
                .subject(userId.toString())
                .audience().add(AUDIENCE_USER).and()
                .claim(CLAIM_PURPOSE, PURPOSE_DEVICE_LIMIT_RESOLUTION)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getAccessTokenKey())
                .compact();
    }

    public Claims parseDeviceLimitResolutionToken(String token) {
        return parseClaims(token, getAccessTokenKey());
    }

    public boolean validateDeviceLimitResolutionToken(String token) {
        if (!validateToken(token, getAccessTokenKey(), "device limit resolution")) {
            return false;
        }
        try {
            Claims claims = parseDeviceLimitResolutionToken(token);
            return PURPOSE_DEVICE_LIMIT_RESOLUTION.equals(claims.get(CLAIM_PURPOSE, String.class));
        } catch (Exception ex) {
            return false;
        }
    }
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.auth.jwt.JwtTokenProviderTest"
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add server/api-service/src/main/java/com/tinniestudio/api/shared/security/jwt/JwtTokenProvider.java \
        server/api-service/src/test/java/com/tinniestudio/api/auth/jwt/JwtTokenProviderTest.java
git commit -m "feat: add single-use device-limit resolution token to JwtTokenProvider"
```

---

### Task 3: `DeviceLimitReachedException` + `GlobalExceptionHandler` mapping

**Files:**
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/auth/exception/DeviceLimitReachedException.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/shared/exception/GlobalExceptionHandler.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/shared/exception/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Write the failing test**

Append to `GlobalExceptionHandlerTest.java` (inside the class, add imports for `DeviceLimitReachedException`, `SessionDto`, `List` at the top of the file alongside the existing ones):

```java
    @Test
    void deviceLimitReached_returns409WithMaxDevicesResolutionTokenAndDevices() {
        com.tinniestudio.api.modules.auth.user.dto.SessionDto device =
                com.tinniestudio.api.modules.auth.user.dto.SessionDto.builder()
                        .sessionId(java.util.UUID.randomUUID())
                        .deviceName("Chrome on Windows")
                        .build();
        com.tinniestudio.api.modules.auth.exception.DeviceLimitReachedException ex =
                new com.tinniestudio.api.modules.auth.exception.DeviceLimitReachedException(
                        "Device limit reached", 3, "resolution-token-value", java.util.List.of(device));

        ResponseEntity<Object> response = handler.handleDeviceLimitReached(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) response.getBody();
        assertThat(body.get("maxDevices")).isEqualTo(3);
        assertThat(body.get("resolutionToken")).isEqualTo("resolution-token-value");
        assertThat(body.get("devices")).isEqualTo(java.util.List.of(device));
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> error = (java.util.Map<String, String>) body.get("error");
        assertThat(error.get("code")).isEqualTo("DEVICE_LIMIT_REACHED");
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.shared.exception.GlobalExceptionHandlerTest"
```

Expected: compile error — `DeviceLimitReachedException` and `handleDeviceLimitReached` don't exist yet.

- [ ] **Step 3: Create the exception**

```java
package com.tinniestudio.api.modules.auth.exception;

import com.tinniestudio.api.modules.auth.user.dto.SessionDto;

import java.util.List;

/**
 * Thrown by AuthService.login() when the user is already at their subscription's device cap.
 * Carries everything the frontend's device-picker modal needs: how many devices are allowed,
 * the current list to choose from, and a single-use token that proves — without re-sending the
 * password — that whichever device gets picked next really was chosen by the same login attempt.
 */
public class DeviceLimitReachedException extends RuntimeException {
    private final int maxDevices;
    private final String resolutionToken;
    private final List<SessionDto> devices;

    public DeviceLimitReachedException(String message, int maxDevices, String resolutionToken, List<SessionDto> devices) {
        super(message);
        this.maxDevices = maxDevices;
        this.resolutionToken = resolutionToken;
        this.devices = devices;
    }

    public int getMaxDevices() {
        return maxDevices;
    }

    public String getResolutionToken() {
        return resolutionToken;
    }

    public List<SessionDto> getDevices() {
        return devices;
    }
}
```

- [ ] **Step 4: Add the handler**

In `GlobalExceptionHandler.java`, add the import alongside the other `modules.auth.exception` imports:

```java
import com.tinniestudio.api.modules.auth.exception.DeviceLimitReachedException;
```

Add the handler method right after `handleBadCredentials`:

```java
    @ExceptionHandler(DeviceLimitReachedException.class)
    public ResponseEntity<Object> handleDeviceLimitReached(
            DeviceLimitReachedException ex,
            HttpServletRequest request
    ) {
        Map<String, Object> body = buildStandardError("DEVICE_LIMIT_REACHED", ex.getMessage(),
                HttpStatus.CONFLICT.value(), request.getServletPath());
        body.put("maxDevices", ex.getMaxDevices());
        body.put("resolutionToken", ex.getResolutionToken());
        body.put("devices", ex.getDevices());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
```

- [ ] **Step 5: Run to verify it passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.shared.exception.GlobalExceptionHandlerTest"
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add server/api-service/src/main/java/com/tinniestudio/api/modules/auth/exception/DeviceLimitReachedException.java \
        server/api-service/src/main/java/com/tinniestudio/api/shared/exception/GlobalExceptionHandler.java \
        server/api-service/src/test/java/com/tinniestudio/api/shared/exception/GlobalExceptionHandlerTest.java
git commit -m "feat: add DeviceLimitReachedException with 409 handler"
```

---

### Task 4: `DEVICE_LOGGED_OUT` notification event type

**Files:**
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumer.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumerTest.java`

- [ ] **Step 1: Write the failing test**

Add to `NotificationConsumerTest.java` (follow the existing test class's mock/setup pattern already in that file — inject the same mocks the class already has: `notificationService`, `userRepository`, `cacheService`, etc.):

```java
    @Test
    @DisplayName("DEVICE_LOGGED_OUT dispatches straight to the target user, no broadcast fan-out, no dedup lock")
    void deviceLoggedOut_sendsDirectlyToTargetUser() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> message = Map.of(
                "type", "DEVICE_LOGGED_OUT",
                "userId", userId.toString(),
                "deviceName", "Chrome on Windows"
        );

        consumer.handleNotificationEvent(message);

        verify(notificationService).sendNotification(
                eq(userId),
                eq(DomainEnums.NotificationEventType.DEVICE_LOGGED_OUT),
                eq("ACCOUNT_SECURITY"),
                eq(userId),
                eq(Map.of("deviceName", "Chrome on Windows"))
        );
        verify(cacheService, never()).setIfAbsent(any(), any(), any());
    }

    @Test
    @DisplayName("DEVICE_LOGGED_OUT falls back to a generic device name when none is provided")
    void deviceLoggedOut_missingDeviceName_fallsBackToGeneric() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> message = Map.of(
                "type", "DEVICE_LOGGED_OUT",
                "userId", userId.toString()
        );

        consumer.handleNotificationEvent(message);

        verify(notificationService).sendNotification(
                eq(userId),
                eq(DomainEnums.NotificationEventType.DEVICE_LOGGED_OUT),
                eq("ACCOUNT_SECURITY"),
                eq(userId),
                eq(Map.of("deviceName", "a device"))
        );
    }
```

Add `import static org.mockito.ArgumentMatchers.any;` to the test file's imports if not already present (check existing import block in that file before adding — do not duplicate).

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.consumer.NotificationConsumerTest"
```

Expected: compile error — `DomainEnums.NotificationEventType.DEVICE_LOGGED_OUT` doesn't exist, and `"DEVICE_LOGGED_OUT"` isn't handled by the switch yet (so the test fails even once it compiles, since `sendNotification` is never called).

- [ ] **Step 3: Add the enum value**

In `DomainEnums.java`, change:

```java
    public enum NotificationEventType {
        CONTENT_PROCESSED,
        CONTENT_APPROVED,
        CONTENT_REJECTED,
        APPLICATION_APPROVED,
        APPLICATION_REJECTED,
        ACCOUNT_SUSPENDED,
        ACCOUNT_BANNED,
        CONTENT_PUBLISHED,
        EPISODE_PUBLISHED
    }
```

to:

```java
    public enum NotificationEventType {
        CONTENT_PROCESSED,
        CONTENT_APPROVED,
        CONTENT_REJECTED,
        APPLICATION_APPROVED,
        APPLICATION_REJECTED,
        ACCOUNT_SUSPENDED,
        ACCOUNT_BANNED,
        CONTENT_PUBLISHED,
        EPISODE_PUBLISHED,
        DEVICE_LOGGED_OUT
    }
```

- [ ] **Step 4: Add the consumer case**

In `NotificationConsumer.java`, add a case to the switch in `handleNotificationEvent`:

```java
    @RabbitListener(queues = RabbitConfig.QUEUE_NOTIFICATIONS)
    public void handleNotificationEvent(Map<String, Object> message) {
        String type = (String) message.get("type");
        switch (type) {
            case "CONTENT_PROCESSED" -> handleContentProcessed(message);
            case "CONTENT_PUBLISHED" -> handleBroadcast(message, NotificationEventType.CONTENT_PUBLISHED);
            case "EPISODE_PUBLISHED" -> handleBroadcast(message, NotificationEventType.EPISODE_PUBLISHED);
            case "DEVICE_LOGGED_OUT" -> handleDeviceLoggedOut(message);
            case null, default -> log.debug("Ignoring notification event type: {}", type);
        }
    }
```

Add the new handler method after `handleContentProcessed` (before `handleBroadcast`):

```java
    // Unlike handleBroadcast, this targets exactly one user (the one who just got signed out of
    // a device) — no fan-out over findAllActiveUserIds(), and no dedup lock, since a given login
    // resolution can only ever fire this once (the resolution token is single-use, enforced in
    // AuthService before this message is even published).
    private void handleDeviceLoggedOut(Map<String, Object> message) {
        try {
            UUID userId = UUID.fromString((String) message.get("userId"));
            String deviceName = (String) message.getOrDefault("deviceName", "a device");
            notificationService.sendNotification(
                    userId,
                    NotificationEventType.DEVICE_LOGGED_OUT,
                    "ACCOUNT_SECURITY",
                    userId,
                    Map.of("deviceName", deviceName)
            );
        } catch (Exception e) {
            log.error("Error processing DEVICE_LOGGED_OUT notification: {}", e.getMessage(), e);
        }
    }
```

- [ ] **Step 5: Run to verify it passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.consumer.NotificationConsumerTest"
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add server/api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java \
        server/api-service/src/main/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumer.java \
        server/api-service/src/test/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumerTest.java
git commit -m "feat: add DEVICE_LOGGED_OUT notification event, dispatched to the signed-out user"
```

---

### Task 5: `AuthService.login()` — check limit before creating a session

**Files:**
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/auth/service/AuthService.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/auth/service/AuthServiceTest.java`

- [ ] **Step 1: Write the failing test**

Add to `AuthServiceTest.java`. First add `@Mock private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;` to the mock field list, and add `rabbitTemplate` as the last argument to the `new AuthService(...)` call in `setUp()`. Then add:

```java
    @Test
    @DisplayName("login: throws DeviceLimitReachedException when already at the device cap, and does NOT create a session")
    void login_atDeviceLimit_throwsWithoutCreatingSession() {
        com.tinniestudio.api.modules.auth.dto.LoginRequest request = new com.tinniestudio.api.modules.auth.dto.LoginRequest();
        request.setEmail("jane@example.com");
        request.setPassword("Password1!");

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("jane@example.com");
        user.setProvider(AuthProvider.LOCAL);
        user.setAccountStatus(AccountStatus.ACTIVE);

        Authentication authentication = mock(Authentication.class);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(sessionService.resolveMaxDevicesForUser(user.getId())).thenReturn(1);
        when(sessionService.countActiveSessions(user.getId())).thenReturn(1L);
        when(jwtTokenProvider.generateDeviceLimitResolutionToken(user.getId())).thenReturn("resolution-token");
        when(sessionService.getActiveSessions(user.getId())).thenReturn(java.util.List.of());

        HttpServletRequest httpRequest = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> authService.login(request, httpRequest, response))
                .isInstanceOf(com.tinniestudio.api.modules.auth.exception.DeviceLimitReachedException.class);

        verify(sessionService, never()).createSession(any(), any(), any());
    }

    @Test
    @DisplayName("login: under the device cap creates a session as before")
    void login_underDeviceLimit_createsSessionNormally() {
        com.tinniestudio.api.modules.auth.dto.LoginRequest request = new com.tinniestudio.api.modules.auth.dto.LoginRequest();
        request.setEmail("jane@example.com");
        request.setPassword("Password1!");

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("jane@example.com");
        user.setProvider(AuthProvider.LOCAL);
        user.setAccountStatus(AccountStatus.ACTIVE);
        UUID sessionId = UUID.randomUUID();

        Authentication authentication = mock(Authentication.class);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(sessionService.resolveMaxDevicesForUser(user.getId())).thenReturn(3);
        when(sessionService.countActiveSessions(user.getId())).thenReturn(1L);
        when(sessionService.createSession(eq(user.getId()), any(), any())).thenReturn(sessionId);
        when(authProfileService.getProfile(eq(user.getId()), eq(sessionId), any()))
                .thenReturn(AuthProfileResponse.builder()
                        .email("jane@example.com")
                        .roles(Set.of(RoleName.ROLE_USER.name()))
                        .provider("LOCAL").emailVerified(true).build());

        HttpServletRequest httpRequest = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();

        AuthProfileResponse result = authService.login(request, httpRequest, response);

        assertThat(result.getEmail()).isEqualTo("jane@example.com");
        verify(jwtTokenProvider, never()).generateDeviceLimitResolutionToken(any());
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.auth.service.AuthServiceTest"
```

Expected: compile error (constructor arg count mismatch — `rabbitTemplate` not yet a real dependency) and/or the first new test failing because `login()` doesn't check the limit yet.

- [ ] **Step 3: Add the `RabbitTemplate` dependency and the limit check**

In `AuthService.java`, add imports:

```java
import com.tinniestudio.api.modules.auth.exception.DeviceLimitReachedException;
import com.tinniestudio.api.modules.auth.user.dto.SessionDto;
import com.tinniestudio.api.shared.queue.RabbitConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Map;
```

Add the field next to the other dependencies:

```java
    private final RabbitTemplate rabbitTemplate;
```

Replace the body of `login()` from the `User user = ...` line through `issueTokenCookies(user, sessionId, response);` with:

```java
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        int maxDevices = sessionService.resolveMaxDevicesForUser(user.getId());
        long activeSessions = sessionService.countActiveSessions(user.getId());
        if (activeSessions >= maxDevices) {
            String resolutionToken = jwtTokenProvider.generateDeviceLimitResolutionToken(user.getId());
            List<SessionDto> devices = sessionService.getActiveSessions(user.getId());
            throw new DeviceLimitReachedException(
                    "You've reached your device limit. Choose a device to sign out to continue.",
                    maxDevices, resolutionToken, devices);
        }

        String rawRefreshToken = generateSecureToken();
        UUID sessionId = sessionService.createSession(user.getId(), rawRefreshToken, httpRequest);
        issueTokenCookies(user, sessionId, response);
```

Add the `resolveDeviceLimit` method right after `login()` (before `// ── Refresh ──`):

```java
    // ── Resolve device limit ──────────────────────────────────────────────────
    @Transactional
    public AuthProfileResponse resolveDeviceLimit(
            com.tinniestudio.api.modules.auth.dto.ResolveDeviceLimitRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        if (request == null) throw new BadRequestException("Request body is required");
        String resolutionToken = requireText(request.getResolutionToken(), "Resolution token is required");
        String sessionIdToRevokeStr = requireText(request.getSessionIdToRevoke(), "A device to sign out must be selected");

        if (!jwtTokenProvider.validateDeviceLimitResolutionToken(resolutionToken)) {
            throw new InvalidTokenException("Resolution token is invalid or expired");
        }

        Claims claims = jwtTokenProvider.parseDeviceLimitResolutionToken(resolutionToken);
        UUID userId = UUID.fromString(claims.getSubject());

        String usedKey = "tinnie:lock:device-limit-resolution:" + claims.getId();
        if (!cacheService.setIfAbsent(usedKey, "used", java.time.Duration.ofMinutes(5))) {
            throw new InvalidTokenException("Resolution token has already been used");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        UUID sessionIdToRevoke = UUID.fromString(sessionIdToRevokeStr);
        String evictedDeviceName = sessionService.getActiveSessions(userId).stream()
                .filter(d -> d.getSessionId().equals(sessionIdToRevoke))
                .map(SessionDto::getDeviceName)
                .findFirst()
                .orElse("a device");

        sessionService.selfRevokeSession(userId, sessionIdToRevoke, null);
        publishDeviceLoggedOutNotification(userId, evictedDeviceName);

        String rawRefreshToken = generateSecureToken();
        UUID newSessionId = sessionService.createSession(userId, rawRefreshToken, httpRequest);
        issueTokenCookies(user, newSessionId, response);

        log.info("User {} resolved device limit by signing out session {}", userId, sessionIdToRevoke);
        return authProfileService.getProfile(userId, newSessionId, "Login successful");
    }

    // Best-effort, same non-critical-failure handling as ContentService's publish helpers — a
    // failed notification must never block the login this method is in the middle of completing.
    private void publishDeviceLoggedOutNotification(UUID userId, String deviceName) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.QUEUE_NOTIFICATIONS, Map.of(
                    "type", "DEVICE_LOGGED_OUT",
                    "userId", userId.toString(),
                    "deviceName", deviceName
            ));
        } catch (Exception e) {
            log.warn("DEVICE_LOGGED_OUT notification publish failed (non-critical): {}", e.getMessage());
        }
    }
```

`AuthService` also needs `cacheService` injected (it currently has no `CacheService` field). Add the field and constructor dependency:

```java
    private final com.tinniestudio.api.shared.cache.CacheService cacheService;
```

(Lombok's `@RequiredArgsConstructor` will pick this up automatically from the field — no manual constructor to edit.)

- [ ] **Step 4: Update the test's constructor call**

In `AuthServiceTest.java`, add `@Mock private com.tinniestudio.api.shared.cache.CacheService cacheService;` to the mocks, and update the `new AuthService(...)` call in `setUp()` to pass `cacheService` and `rabbitTemplate` as the final two arguments, matching the field declaration order in `AuthService.java`.

- [ ] **Step 5: Run to verify it passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.auth.service.AuthServiceTest"
```

Expected: all pass, including the two new tests and every pre-existing test in the file (register/refresh/logout tests must still compile and pass against the widened constructor).

- [ ] **Step 6: Run the full module suite**

```bash
./gradlew :api-service:test
```

Expected: same accepted baseline as Task 1, Step 7.

- [ ] **Step 7: Commit**

```bash
git add server/api-service/src/main/java/com/tinniestudio/api/modules/auth/service/AuthService.java \
        server/api-service/src/test/java/com/tinniestudio/api/auth/service/AuthServiceTest.java
git commit -m "feat: check device limit before creating a login session; add resolve-device-limit flow"
```

---

### Task 6: `ResolveDeviceLimitRequest` DTO + `AuthController`/`SecurityConfig` wiring

**Files:**
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/auth/dto/ResolveDeviceLimitRequest.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/auth/controller/AuthController.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/auth/controller/AuthControllerTest.java`

- [ ] **Step 1: Write the failing test**

Add to `AuthControllerTest.java`:

```java
    @Test
    @DisplayName("POST /auth/login/resolve-device-limit delegates to authService and returns 200")
    void resolveDeviceLimit_returnsOk() throws Exception {
        ResolveDeviceLimitRequest request = new ResolveDeviceLimitRequest();
        request.setResolutionToken("resolution-token");
        request.setSessionIdToRevoke(UUID.randomUUID().toString());

        when(authService.resolveDeviceLimit(any(), any(), any()))
                .thenReturn(AuthProfileResponse.builder()
                        .email("jane@example.com")
                        .roles(Set.of("ROLE_USER"))
                        .provider("LOCAL").emailVerified(true).build());

        mockMvc.perform(postWithContext("/auth/login/resolve-device-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("jane@example.com"));
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.auth.controller.AuthControllerTest"
```

Expected: compile error — `ResolveDeviceLimitRequest` doesn't exist and the endpoint returns 404.

- [ ] **Step 3: Create the DTO**

```java
package com.tinniestudio.api.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(name = "ResolveDeviceLimitRequest", description = "Picks which device to sign out to complete a login that hit the device cap")
public class ResolveDeviceLimitRequest {

    @Schema(description = "Single-use token from the 409 DEVICE_LIMIT_REACHED response", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Resolution token is required")
    private String resolutionToken;

    @Schema(description = "sessionId of the device the user chose to sign out", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "A device to sign out must be selected")
    private String sessionIdToRevoke;
}
```

- [ ] **Step 4: Add the controller endpoint**

In `AuthController.java`, add the endpoint right after `login(...)`:

```java
    @Operation(summary = "Resolve a device-limit conflict from login", description = "Revokes the selected device's session and completes login for the new device. The resolution token is single-use and expires after 5 minutes.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Login completed",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AuthProfileResponse.class))),
        @ApiResponse(responseCode = "401", description = "Resolution token invalid, expired, or already used",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    })
    @RateLimit(maxRequests = 10, windowMinutes = 15, keyStrategy = "IP_ONLY")
    @PostMapping("/login/resolve-device-limit")
    @SecurityRequirements({})
    public ResponseEntity<AuthProfileResponse> resolveDeviceLimit(
            @Valid @RequestBody com.tinniestudio.api.modules.auth.dto.ResolveDeviceLimitRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        return ResponseEntity.ok(authService.resolveDeviceLimit(request, httpRequest, response));
    }
```

- [ ] **Step 5: Add the public-endpoint allowlist entries**

In `SecurityConfig.java`, add to `PUBLIC_ENDPOINTS` (this endpoint authenticates via the resolution token in the request body, not a session cookie, so it must be public exactly like `/auth/login` is — forgetting this entry is a known gotcha: `WebMvcTest`-based controller tests pass even without it, since they don't load the real security filter chain):

```java
        "/auth/login/resolve-device-limit",
        "/api/v1/auth/login/resolve-device-limit",
```

(Add these two lines alongside the existing `/auth/login` / `/api/v1/auth/login` pair.)

- [ ] **Step 6: Run to verify it passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.auth.controller.AuthControllerTest"
```

Expected: all pass.

- [ ] **Step 7: Run the full module suite**

```bash
./gradlew :api-service:test
```

Expected: same accepted baseline.

- [ ] **Step 8: Commit**

```bash
git add server/api-service/src/main/java/com/tinniestudio/api/modules/auth/dto/ResolveDeviceLimitRequest.java \
        server/api-service/src/main/java/com/tinniestudio/api/modules/auth/controller/AuthController.java \
        server/api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java \
        server/api-service/src/test/java/com/tinniestudio/api/auth/controller/AuthControllerTest.java
git commit -m "feat: wire resolve-device-limit endpoint, allowlist it as a public auth endpoint"
```

---

## Part B — Self-service device management

### Task 7: `SessionController` — authenticated self-revoke endpoint

**Files:**
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/auth/controller/SessionController.java`
- Create: `server/api-service/src/test/java/com/tinniestudio/api/auth/controller/SessionControllerTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.tinniestudio.api.auth.controller;

import com.tinniestudio.api.modules.auth.controller.SessionController;
import com.tinniestudio.api.modules.auth.user.service.AuthProfileService;
import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SessionController.class)
@AutoConfigureMockMvc(addFilters = false)
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private SessionService sessionService;
    @MockBean private UserRepository userRepository;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private UserDetailsServiceImpl userDetailsService;
    @MockBean private AuthProfileService authProfileService;

    private static final String CONTEXT_PATH = "/api/v1";

    @Test
    @DisplayName("POST /auth/sessions/{id}/revoke delegates to sessionService.selfRevokeSession and returns 200")
    void revoke_returnsOk() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        RequestPostProcessor principal = user(new User(
                userId.toString(), "n/a", List.of()));

        doNothing().when(sessionService).selfRevokeSession(eq(userId), eq(sessionId), any());

        mockMvc.perform(post(CONTEXT_PATH + "/auth/sessions/" + sessionId + "/revoke")
                        .contextPath(CONTEXT_PATH)
                        .with(principal))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.auth.controller.SessionControllerTest"
```

Expected: compile error — `SessionController` doesn't exist yet.

- [ ] **Step 3: Implement the controller**

```java
package com.tinniestudio.api.modules.auth.controller;

import com.tinniestudio.api.modules.auth.user.service.SessionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth/sessions")
@RequiredArgsConstructor
@Tag(name = "Sessions", description = "Self-service device/session management for the authenticated user.")
public class SessionController {

    private final SessionService sessionService;

    @Operation(summary = "Revoke one of your own sessions", description = "Signs out a specific device. Already-revoked sessions are a no-op; your own current session cannot be revoked this way — use logout instead.")
    @PostMapping("/{sessionId}/revoke")
    public ResponseEntity<Map<String, String>> revoke(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID sessionId,
            HttpServletRequest request) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        String sessionIdAttr = (String) request.getAttribute("sessionId");
        UUID currentSessionId = sessionIdAttr != null ? UUID.fromString(sessionIdAttr) : null;

        sessionService.selfRevokeSession(userId, sessionId, currentSessionId);
        return ResponseEntity.ok(Map.of("message", "Device signed out"));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.auth.controller.SessionControllerTest"
```

Expected: pass. (No `SecurityConfig` changes needed — this endpoint is authenticated by default, same as `/auth/me`.)

- [ ] **Step 5: Run the full module suite**

```bash
./gradlew :api-service:test
```

Expected: same accepted baseline.

- [ ] **Step 6: Commit**

```bash
git add server/api-service/src/main/java/com/tinniestudio/api/modules/auth/controller/SessionController.java \
        server/api-service/src/test/java/com/tinniestudio/api/auth/controller/SessionControllerTest.java
git commit -m "feat: add authenticated self-service session revoke endpoint"
```

---

## Part C — Frontend (tinniestudio-client-web)

### Task 8: Widen `backendFetch.ts` and `ApiError` to carry extra error-body fields

**Files:**
- Modify: `tinniestudio-client-web/src/lib/server/backendFetch.ts`
- Modify: `tinniestudio-client-web/src/lib/errors/api-error.ts`

- [ ] **Step 1: Edit `backendFetch.ts`'s axios-error-mapping catch block**

Both `backendFetch.ts` (server-side, in the BFF layer) and `ApiError` (browser-side) currently extract only `message` from a non-2xx backend response and discard everything else. A 409 `DEVICE_LIMIT_REACHED` response's `maxDevices`/`resolutionToken`/`devices` fields would silently vanish at the FIRST of these two layers before ever reaching the browser — this step fixes that layer.

Replace:

```ts
  } catch (err: unknown) {
    // Axios error mapping
    if (axios.isAxiosError(err)) {
      const axiosErr = err;
      const status = axiosErr.response?.status ?? 500;
      const backendMessage = axiosErr.response?.data?.message ?? axiosErr.message;
      return {
        success: false,
        message: typeof backendMessage === 'string' ? backendMessage : JSON.stringify(backendMessage),
        error: {
          code: String(axiosErr.code ?? status),
          message: typeof backendMessage === 'string' ? backendMessage : axiosErr.message,
        },
        status,
        headers: axiosErr.response?.headers as Record<string, string | string[] | undefined> | undefined,
      };
    }
```

with:

```ts
  } catch (err: unknown) {
    // Axios error mapping
    if (axios.isAxiosError(err)) {
      const axiosErr = err;
      const status = axiosErr.response?.status ?? 500;
      const responseData = axiosErr.response?.data as Record<string, unknown> | undefined;
      const backendMessage = responseData?.message ?? axiosErr.message;
      const backendErrorCode = (responseData?.error as { code?: string } | undefined)?.code;
      return {
        // Spread first so extra top-level fields the backend added (e.g. maxDevices,
        // resolutionToken, devices on a DEVICE_LIMIT_REACHED 409) survive the proxy hop —
        // the explicit fields below then override with correctly-typed values.
        ...(responseData ?? {}),
        success: false,
        message: typeof backendMessage === 'string' ? backendMessage : JSON.stringify(backendMessage),
        error: {
          code: String(backendErrorCode ?? axiosErr.code ?? status),
          message: typeof backendMessage === 'string' ? backendMessage : axiosErr.message,
        },
        status,
        headers: axiosErr.response?.headers as Record<string, string | string[] | undefined> | undefined,
      } as ApiResponse<T>;
    }
```

- [ ] **Step 2: Widen `ApiError` with a `raw` escape hatch**

In `api-error.ts`, replace:

```ts
type ApiErrorOptions = {
  message: string;
  code?: string;
  status?: number;
  details?: Record<string, string[]>;
  cause?: unknown;
};

export class ApiError extends Error {
  code: string;
  status?: number;
  details?: Record<string, string[]>;

  constructor(options: ApiErrorOptions) {
    super(options.message);
    this.name = "ApiError";
    this.code = options.code ?? "UNKNOWN";
    this.status = options.status;
    this.details = options.details;
    if (options.cause !== undefined) {
      (this as { cause?: unknown }).cause = options.cause;
    }
  }

  static fromApiResponse<T>(
    resp: ApiResponse<T> | undefined,
    fallbackMessage = "Request failed"
  ): ApiError {
    const message =
      resp?.error?.message ??
      resp?.message ??
      fallbackMessage;
    const code =
      resp?.error?.code ??
      String(resp?.status ?? "UNKNOWN");

    return new ApiError({
      message,
      code,
      status: resp?.status,
      details: resp?.error?.details,
    });
  }
```

with:

```ts
type ApiErrorOptions = {
  message: string;
  code?: string;
  status?: number;
  details?: Record<string, string[]>;
  // Full backend response body, for error responses that carry extra fields beyond
  // {code, message, details} — e.g. DEVICE_LIMIT_REACHED's {maxDevices, resolutionToken, devices}.
  raw?: Record<string, unknown>;
  cause?: unknown;
};

export class ApiError extends Error {
  code: string;
  status?: number;
  details?: Record<string, string[]>;
  raw?: Record<string, unknown>;

  constructor(options: ApiErrorOptions) {
    super(options.message);
    this.name = "ApiError";
    this.code = options.code ?? "UNKNOWN";
    this.status = options.status;
    this.details = options.details;
    this.raw = options.raw;
    if (options.cause !== undefined) {
      (this as { cause?: unknown }).cause = options.cause;
    }
  }

  static fromApiResponse<T>(
    resp: ApiResponse<T> | undefined,
    fallbackMessage = "Request failed"
  ): ApiError {
    const message =
      resp?.error?.message ??
      resp?.message ??
      fallbackMessage;
    const code =
      resp?.error?.code ??
      String(resp?.status ?? "UNKNOWN");

    return new ApiError({
      message,
      code,
      status: resp?.status,
      details: resp?.error?.details,
      raw: resp as unknown as Record<string, unknown> | undefined,
    });
  }
```

- [ ] **Step 3: Type-check**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
```

Expected: no new errors.

- [ ] **Step 4: Commit**

```bash
git add src/lib/server/backendFetch.ts src/lib/errors/api-error.ts
git commit -m "fix: preserve full backend error body through backendFetch and ApiError"
```

---

### Task 9: Fix `BackendUser` device-shape mismatch + add device-limit types

**Files:**
- Modify: `tinniestudio-client-web/src/types/auth.type.ts`

- [ ] **Step 1: Fix the type**

The backend's `AuthProfileResponse` has always returned `devices: { active, max, sessions }` (confirmed by reading `AuthProfileResponse.java`) — the frontend type's flat top-level `sessions: DeviceSession[]` field never matched what the backend actually sends, so no consumer could ever have worked against real data. Replace:

```ts
export interface BackendUser {
  userId: string;
  email: string;
  firstName?: string | null;
  lastName?: string | null;
  displayName?: string | null;
  avatarUrl?: string | null;
  phoneNumber?: string | null;
  dateOfBirth?: string | null;
  provider: string;
  emailVerified: boolean;
  bio?: string | null;
  languageCode?: string | null;
  countryCode?: string | null;
  timezone?: string | null;
  notificationEmail: boolean;
  roles: string[];
  createdAt: string;
  subscription: UserSubscription | null;
  sessions: DeviceSession[];
  canWatch: boolean;
}

export interface DeviceSession {
  sessionId: string;
  deviceName: string;
  ipAddress: string;
  lastUsedAt: string;
  current: boolean;
}
```

with:

```ts
export interface BackendUser {
  userId: string;
  email: string;
  firstName?: string | null;
  lastName?: string | null;
  displayName?: string | null;
  avatarUrl?: string | null;
  phoneNumber?: string | null;
  dateOfBirth?: string | null;
  provider: string;
  emailVerified: boolean;
  bio?: string | null;
  languageCode?: string | null;
  countryCode?: string | null;
  timezone?: string | null;
  notificationEmail: boolean;
  roles: string[];
  createdAt: string;
  subscription: UserSubscription | null;
  devices: {
    active: number;
    max: number;
    sessions: DeviceSession[];
  };
  canWatch: boolean;
}

export interface DeviceSession {
  sessionId: string;
  deviceName: string;
  ipAddress: string;
  lastUsedAt: string;
  current: boolean;
}

// Extra top-level fields the backend adds to a 409 DEVICE_LIMIT_REACHED response body
// (see ApiError.raw) — picked up by LoginClient's device-picker flow.
export interface DeviceLimitErrorPayload {
  maxDevices: number;
  resolutionToken: string;
  devices: DeviceSession[];
}

export interface ResolveDeviceLimitRequest {
  resolutionToken: string;
  sessionIdToRevoke: string;
}
```

- [ ] **Step 2: Type-check**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
```

Expected: no new errors (the repo-wide `.sessions` grep in research confirmed zero existing consumers of the old field, so this is a pure rename with no call-site breakage).

- [ ] **Step 3: Commit**

```bash
git add src/types/auth.type.ts
git commit -m "fix: correct BackendUser.sessions to the backend's real devices.{active,max,sessions} shape"
```

---

### Task 10: `resolveDeviceLimit` mutation + BFF route

**Files:**
- Modify: `tinniestudio-client-web/src/features/auth/constants/auth.ts`
- Create: `tinniestudio-client-web/src/app/api/auth/login/resolve-device-limit/route.ts`
- Modify: `tinniestudio-client-web/src/features/auth/service/mutations.ts`

- [ ] **Step 1: Add the backend path constant**

In `auth.ts`, add to `AUTH_CONSTANTS`:

```ts
    RESOLVE_DEVICE_LIMIT: "/auth/login/resolve-device-limit",
```

- [ ] **Step 2: Create the BFF route**

```ts
import backendFetch from '@/lib/server/backendFetch';
import { AUTH_CONSTANTS } from '@/features/auth/constants/auth';
import { buildProxyResponse, forwardCredentials } from '@/lib/server/bff';
import { NextResponse } from 'next/server';

export async function POST(req: Request) {
  const body = await req.json().catch(() => null);
  const resolutionToken = typeof body?.resolutionToken === 'string' ? body.resolutionToken : null;
  const sessionIdToRevoke = typeof body?.sessionIdToRevoke === 'string' ? body.sessionIdToRevoke : null;

  if (!resolutionToken || !sessionIdToRevoke) {
    const message = 'resolutionToken and sessionIdToRevoke are required';
    return NextResponse.json({ success: false, error: { code: 'VALIDATION', message }, message }, { status: 400 });
  }

  const resp = await backendFetch({
    url: AUTH_CONSTANTS.RESOLVE_DEVICE_LIMIT,
    method: 'POST',
    data: { resolutionToken, sessionIdToRevoke },
    headers: forwardCredentials(req)
  });

  return buildProxyResponse(resp);
}
```

- [ ] **Step 3: Add the mutation function**

In `mutations.ts`, add the import and function:

```ts
import type {
  BackendUser,
  ForgotPasswordRequest,
  LoginRequest,
  LoginResponse,
  RegisterRequest,
  ResetPasswordRequest,
  ResolveDeviceLimitRequest,
} from "@/types";
```

```ts
export async function resolveDeviceLimit(payload: ResolveDeviceLimitRequest): Promise<LoginResponse> {
  return bffFetch<LoginResponse>('/api/auth/login/resolve-device-limit', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}
```

- [ ] **Step 4: Type-check**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
```

Expected: no new errors.

- [ ] **Step 5: Commit**

```bash
git add src/features/auth/constants/auth.ts \
        src/app/api/auth/login/resolve-device-limit/route.ts \
        src/features/auth/service/mutations.ts
git commit -m "feat: add resolveDeviceLimit mutation and BFF proxy route"
```

---

### Task 11: `DevicePickerModal` + wire into `LoginClient.tsx`

**Files:**
- Create: `tinniestudio-client-web/src/features/auth/components/DevicePickerModal.tsx`
- Modify: `tinniestudio-client-web/src/features/auth/components/LoginClient.tsx`
- Modify: `tinniestudio-client-web/src/features/auth/service/mutations.ts` (export check only — already exported in Task 10)

- [ ] **Step 1: Create the modal**

```tsx
'use client';

import { useState } from 'react';
import { Laptop, Smartphone, Monitor } from 'lucide-react';

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import type { DeviceSession } from '@/types';

interface DevicePickerModalProps {
  open: boolean;
  maxDevices: number;
  devices: DeviceSession[];
  isSubmitting: boolean;
  error: string | null;
  onConfirm: (sessionId: string) => void;
  onOpenChange: (open: boolean) => void;
}

function deviceIcon(deviceName: string) {
  const lower = deviceName.toLowerCase();
  if (lower.includes('ios') || lower.includes('android')) return Smartphone;
  if (lower.includes('windows') || lower.includes('macos') || lower.includes('linux')) return Laptop;
  return Monitor;
}

export function DevicePickerModal({
  open,
  maxDevices,
  devices,
  isSubmitting,
  error,
  onConfirm,
  onOpenChange,
}: DevicePickerModalProps) {
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className='bg-tls-primary2 border-white/10'>
        <DialogHeader>
          <DialogTitle className='text-white'>Device limit reached</DialogTitle>
          <DialogDescription className='text-white/60'>
            Your plan allows {maxDevices} active device{maxDevices === 1 ? '' : 's'}. Choose one to
            sign out to continue logging in on this device.
          </DialogDescription>
        </DialogHeader>

        <div className='space-y-2 max-h-72 overflow-y-auto'>
          {devices.map(device => {
            const Icon = deviceIcon(device.deviceName);
            const isSelected = selectedSessionId === device.sessionId;
            return (
              <button
                key={device.sessionId}
                type='button'
                onClick={() => setSelectedSessionId(device.sessionId)}
                className={`w-full flex items-center gap-3 p-3 rounded-xl border text-left transition-colors ${
                  isSelected
                    ? 'border-tls-secondary bg-tls-secondary/10'
                    : 'border-white/10 hover:bg-white/5'
                }`}
              >
                <Icon className='w-5 h-5 text-white/60 shrink-0' />
                <div className='flex-1 min-w-0'>
                  <p className='text-sm font-medium text-white truncate'>{device.deviceName}</p>
                  <p className='text-xs text-white/40'>{device.ipAddress}</p>
                </div>
              </button>
            );
          })}
        </div>

        {error && (
          <p role='alert' className='text-red-400 text-sm'>
            {error}
          </p>
        )}

        <DialogFooter>
          <Button
            onClick={() => selectedSessionId && onConfirm(selectedSessionId)}
            disabled={!selectedSessionId || isSubmitting}
            aria-busy={isSubmitting}
            className='bg-tls-secondary hover:bg-tls-secondary/90 text-tls-primary rounded-sm w-full'
          >
            {isSubmitting ? 'Signing out device…' : 'Sign out this device and continue'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

- [ ] **Step 2: Wire into `LoginClient.tsx`**

Add imports:

```ts
import { ApiError } from '@/lib/errors';
import type { DeviceLimitErrorPayload } from '@/types';
import { DevicePickerModal } from './DevicePickerModal';
import { resolveDeviceLimit } from '../service/mutations';
```

Add state and a second mutation, right after the existing `mutation` declaration:

```ts
  const [deviceLimitPayload, setDeviceLimitPayload] = useState<DeviceLimitErrorPayload | null>(null);
  const [resolveError, setResolveError] = useState<string | null>(null);

  const resolveMutation = useApiMutation(async (vars: { resolutionToken: string; sessionIdToRevoke: string }) => {
    return resolveDeviceLimit(vars);
  });
```

Replace the `catch` block in `onSubmit` from:

```ts
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : String(err ?? 'Login failed');
      toast.error(
        message || 'Login failed. Please check your credentials and try again.'
      );
      console.error(err);
    } finally {
```

to:

```ts
    } catch (err: unknown) {
      if (err instanceof ApiError && err.code === 'DEVICE_LIMIT_REACHED' && err.raw) {
        setDeviceLimitPayload(err.raw as unknown as DeviceLimitErrorPayload);
        setIsLoading(false);
        return;
      }
      const message =
        err instanceof Error ? err.message : String(err ?? 'Login failed');
      toast.error(
        message || 'Login failed. Please check your credentials and try again.'
      );
      console.error(err);
    } finally {
```

Add a handler function and render the modal. Add the handler right after `onSubmit`:

```ts
  async function onResolveDeviceLimit(sessionIdToRevoke: string) {
    if (!deviceLimitPayload) return;
    setResolveError(null);
    try {
      const resp = await resolveMutation.mutateAsync({
        resolutionToken: deviceLimitPayload.resolutionToken,
        sessionIdToRevoke,
      });
      setAuth(resp);
      setDeviceLimitPayload(null);
      toast.success('Login successful!');
      const cookieReturnTo = getReturnToFromCookie();
      const redirectTo = getSafeReturnTo(cookieReturnTo || safeReturnTo);
      clearReturnToCookie();
      router.push(redirectTo);
    } catch (err: unknown) {
      setResolveError(
        err instanceof Error ? err.message : 'Could not sign out that device. Please try again.'
      );
    }
  }
```

Finally, render the modal right before the closing `</Card>` tag at the end of the component's JSX:

```tsx
      {deviceLimitPayload && (
        <DevicePickerModal
          open
          maxDevices={deviceLimitPayload.maxDevices}
          devices={deviceLimitPayload.devices}
          isSubmitting={resolveMutation.isPending}
          error={resolveError}
          onConfirm={onResolveDeviceLimit}
          onOpenChange={next => {
            if (!next) {
              setDeviceLimitPayload(null);
              setResolveError(null);
            }
          }}
        />
      )}
    </Card>
```

(This replaces the final standalone `</Card>` — keep everything else in the existing JSX unchanged.)

- [ ] **Step 3: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
npx eslint src/features/auth/components/LoginClient.tsx src/features/auth/components/DevicePickerModal.tsx
```

Expected: no errors.

- [ ] **Step 4: Manual verification**

Start the dev server and manually drive the flow: log in on a plan at its device cap from a browser where you already have an active session (or seed a session row directly in the DB), confirm the picker modal appears with the device list, select a device, confirm login completes and the previous device's next API call now fails with 401 (session revoked).

```bash
npm run dev
```

- [ ] **Step 5: Commit**

```bash
git add src/features/auth/components/DevicePickerModal.tsx src/features/auth/components/LoginClient.tsx
git commit -m "feat: show device-picker modal on 409 DEVICE_LIMIT_REACHED during login"
```

---

### Task 12: `NotificationBell` deep-link for `ACCOUNT_SECURITY`

**Files:**
- Modify: `tinniestudio-client-web/src/features/notifications/components/NotificationBell.tsx`

- [ ] **Step 1: Add the case**

In `resolveHref`, add a case before `default`:

```ts
function resolveHref(notification: NotificationResponse): string | null {
  switch (notification.referenceType) {
    case 'CONTENT':
      return notification.referenceId ? `/watch/${notification.referenceId}` : null;
    case 'ACCOUNT_SECURITY':
      return '/account?action=security';
    default:
      return null;
  }
}
```

- [ ] **Step 2: Type-check**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
```

Expected: no new errors.

- [ ] **Step 3: Commit**

```bash
git add src/features/notifications/components/NotificationBell.tsx
git commit -m "feat: deep-link DEVICE_LOGGED_OUT notifications to the account security tab"
```

---

### Task 13: "Manage Devices" card in `ProfilePanelSecurity.tsx`

**Files:**
- Modify: `tinniestudio-client-web/src/features/account/components/ProfilePanelSecurity.tsx`

- [ ] **Step 1: Add the self-revoke mutation**

In `tinniestudio-client-web/src/features/account/service/mutations.ts`, add:

```ts
export async function revokeSession(sessionId: string): Promise<{ message?: string } | void> {
  return bffFetch<{ message?: string } | void>(`/api/account/sessions/${sessionId}/revoke`, {
    method: 'POST',
  });
}
```

(Check the top of that file for the existing `bffFetch` import and add `revokeSession` alongside the other exported functions — do not duplicate the import.)

Create the BFF route `tinniestudio-client-web/src/app/api/account/sessions/[sessionId]/revoke/route.ts`:

```ts
import backendFetch from '@/lib/server/backendFetch';
import { buildProxyResponse, forwardCredentials } from '@/lib/server/bff';

export async function POST(req: Request, { params }: { params: Promise<{ sessionId: string }> }) {
  const { sessionId } = await params;
  const resp = await backendFetch({
    url: `/auth/sessions/${sessionId}/revoke`,
    method: 'POST',
    headers: forwardCredentials(req),
  });

  return buildProxyResponse(resp);
}
```

- [ ] **Step 2: Add a `useApiMutation` hook for it**

In `tinniestudio-client-web/src/features/account/service/queries.ts` or wherever the panel imports its hooks from — add to `mutations.ts` directly since this is a mutation, not a query:

```ts
import { useApiMutation } from '@/hooks';
```

(Only add this import if it isn't already imported in that file — check first.)

```ts
export function useRevokeSession() {
  return useApiMutation((sessionId: string) => revokeSession(sessionId));
}
```

- [ ] **Step 3: Add the "Active Devices" card to `ProfilePanelSecurity.tsx`**

Add imports:

```tsx
import { useState } from 'react';
import { Laptop, Smartphone, Monitor, LogOut } from 'lucide-react';
import { useRevokeSession } from '../service/mutations';
```

(Merge `useState` into the existing React import if one already exists in the file; merge the lucide icons into the existing `lucide-react` import line rather than adding a second one.)

Add state near the top of the component body, right after `const changePasswordMutation = useChangePassword();`:

```tsx
  const [localDevices, setLocalDevices] = useState(user.devices.sessions);
  const [revokingSessionId, setRevokingSessionId] = useState<string | null>(null);
  const revokeSessionMutation = useRevokeSession();

  const handleRevoke = async (sessionId: string) => {
    setRevokingSessionId(sessionId);
    try {
      await revokeSessionMutation.mutateAsync(sessionId);
      setLocalDevices(prev => prev.filter(d => d.sessionId !== sessionId));
      toast.success('Device signed out');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to sign out device';
      toast.error(message);
    } finally {
      setRevokingSessionId(null);
    }
  };

  function deviceIcon(deviceName: string) {
    const lower = deviceName.toLowerCase();
    if (lower.includes('ios') || lower.includes('android')) return Smartphone;
    if (lower.includes('windows') || lower.includes('macos') || lower.includes('linux')) return Laptop;
    return Monitor;
  }
```

Add a new `Card` right after the closing `</Card>` of the "Authentication" card and before the "System Logs" `<Card>`:

```tsx
      <Card className='bg-white/5 border-white/10 backdrop-blur-md rounded-3xl overflow-hidden'>
        <CardHeader className='p-4 md:p-8 pb-4'>
          <div className='flex items-center gap-3'>
            <div className='p-2 rounded-xl bg-tls-secondary/10 border border-tls-secondary/20'>
              <Laptop className='w-5 h-5 text-tls-secondary' />
            </div>
            <div>
              <CardTitle className='text-xl font-semibold text-white font-manrope'>
                Active Devices
              </CardTitle>
              <p className='text-sm text-white/40'>
                {localDevices.length} of {user.devices.max} device
                {user.devices.max === 1 ? '' : 's'} in use.
              </p>
            </div>
          </div>
        </CardHeader>
        <CardContent className='p-4 md:p-8 pt-4 space-y-3'>
          {localDevices.length === 0 ? (
            <p className='text-sm text-white/40'>No active devices.</p>
          ) : (
            localDevices.map(device => {
              const Icon = deviceIcon(device.deviceName);
              return (
                <div
                  key={device.sessionId}
                  className='flex items-center gap-4 p-4 rounded-2xl bg-white/5 border border-white/10'
                >
                  <Icon className='w-5 h-5 text-white/60 shrink-0' />
                  <div className='flex-1 min-w-0'>
                    <p className='text-sm font-semibold text-white flex items-center gap-2'>
                      {device.deviceName}
                      {device.current && (
                        <span className='text-xs font-normal text-tls-secondary'>This device</span>
                      )}
                    </p>
                    <p className='text-xs text-white/40'>
                      {device.ipAddress} · Last active {formatDate(device.lastUsedAt)}
                    </p>
                  </div>
                  {!device.current && (
                    <Button
                      variant='outline'
                      size='sm'
                      disabled={revokingSessionId === device.sessionId}
                      onClick={() => handleRevoke(device.sessionId)}
                      className='border-white/10 text-white/70 hover:bg-white/10 rounded-xl'
                    >
                      <LogOut className='w-4 h-4 mr-2' />
                      {revokingSessionId === device.sessionId ? 'Signing out…' : 'Sign out'}
                    </Button>
                  )}
                </div>
              );
            })
          )}
        </CardContent>
      </Card>
```

- [ ] **Step 4: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
npx eslint src/features/account/components/ProfilePanelSecurity.tsx src/features/account/service/mutations.ts
```

Expected: no errors.

- [ ] **Step 5: Manual verification**

```bash
npm run dev
```

Log in, go to Account → Security, confirm the device list renders with the current device marked and un-revokable, revoke a different device, confirm it disappears from the list and that device's next request gets a 401.

- [ ] **Step 6: Commit**

```bash
git add src/features/account/components/ProfilePanelSecurity.tsx \
        src/features/account/service/mutations.ts \
        src/app/api/account/sessions/\[sessionId\]/revoke/route.ts
git commit -m "feat: add self-service Manage Devices card to account security settings"
```

---

## Self-Review Notes

- **Spec coverage:** Part A (409 response shape, resolution token, resolve endpoint, DEVICE_LOGGED_OUT notification, frontend picker modal) — Tasks 1–6, 8–11. Part B (BackendUser.devices fix, self-revoke endpoint, Manage Devices UI) — Tasks 7, 9, 13. The "passive — next API call fails" eviction behavior falls out naturally from `isSessionActive` already being checked on every authenticated request; no new code needed for that part of the spec.
- **Plumbing fix justified:** Tasks 8 was added after tracing the exact response path and confirming TWO layers (`backendFetch.ts`, `ApiError`) silently dropped the 409's extra fields — without it, Task 11's modal would never receive `maxDevices`/`resolutionToken`/`devices` no matter how correct the backend is.
- **Type consistency check:** `DeviceLimitErrorPayload` (Task 9) matches exactly what `GlobalExceptionHandler.handleDeviceLimitReached` (Task 3) puts on the response body (`maxDevices`, `resolutionToken`, `devices`). `ResolveDeviceLimitRequest` (frontend, Task 9) matches `ResolveDeviceLimitRequest` (backend DTO, Task 6) field-for-field (`resolutionToken`, `sessionIdToRevoke`). `SessionDto.deviceName/ipAddress/lastUsedAt/sessionId/current` (backend) matches `DeviceSession` (frontend) field-for-field.
