# Public Partner Application (Server) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `POST /partners/applications` reachable without a session — an anonymous caller creates a real account inline (reusing `/auth/register`'s logic) and the applicant can self-check their application status, which also fixes a latent duplicate-row bug in today's apply flow.

**Architecture:** `PartnerController.apply` branches on whether `@AuthenticationPrincipal` resolved a principal (session present) or not (anonymous) — the JWT filter already runs on this path regardless of the new public allowlist entry, so this falls out of existing infrastructure. `PartnerApplicationServiceImpl.apply` switches from a blind insert to an upsert via the already-existing (but previously unused) `findByUserId`. A new `GET /partners/applications/me` closes the documented "no self-service status check" gap.

**Tech Stack:** Spring Boot, Spring Security (method + URL-level `@PreAuthorize`/`PUBLIC_ENDPOINTS`), Spring Data JPA, JUnit 5, Mockito, AssertJ, Testcontainers (Postgres).

**Companion plan:** `tinniestudio-partner-web/docs/superpowers/plans/2026-10-08-public-partner-application-partner-web.md` depends on the endpoints built here. Do this plan first.

---

### Task 1: Open the endpoint — `@PreAuthorize` + `PUBLIC_ENDPOINTS`

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/partner/controller/PartnerController.java:38-48`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/partner/controller/PartnerControllerTest.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/integration/PartnerApplicationAuthIntegrationTest.java` (new)

- [ ] **Step 1: Write the failing real-filter-chain integration test**

This is the only way to prove the endpoint is actually unauthenticated-reachable — `PartnerControllerTest` uses `@WebMvcTest` + `addFilters=false`, which bypasses real Spring Security and can't catch a missing `PUBLIC_ENDPOINTS` entry (this exact gap bit the trailer-resilience work last week). Mirrors `AdminBusinessEndpointAuthIntegrationTest`'s pattern.

```java
package com.tinniestudio.api.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test: a new "public" endpoint can be coded with no @AuthenticationPrincipal
 * requirement and still 401 in the real app if SecurityConfig.PUBLIC_ENDPOINTS doesn't list it
 * (the filter chain's trailing .anyRequest().authenticated() catches it first). WebMvcTest-based
 * controller tests use addFilters=false and can't catch this — only a real filter chain can.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@ExtendWith(SpringExtension.class)
class PartnerApplicationAuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_partner_app_auth_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired private MockMvc mockMvc;

    @Test
    void noCookie_applicationEndpointIsReachable_notUnauthorized() throws Exception {
        String body = """
            {"companyName":"Acme Corp","email":"anon-apply-test@example.com",
             "password":"Str0ng!Pass","firstName":"Anon","lastName":"Applicant"}
            """;

        mockMvc.perform(post("/api/v1/partners/applications").contextPath("/api/v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.integration.PartnerApplicationAuthIntegrationTest"`
Expected: FAIL with 401 — the endpoint isn't public yet, and the controller doesn't yet accept the anonymous request body shape (compile will actually succeed since `PartnerApplicationRequest` doesn't have `email`/`password`/`firstName`/`lastName` yet — Jackson will just ignore the unknown fields unless `FAIL_ON_UNKNOWN_PROPERTIES` is on; check this doesn't itself cause a 400 before worrying about the 401 — if it does, that's still "not 201", consistent with the expected failure here). The real blocker is the 401.

- [ ] **Step 3: Change the method-level `@PreAuthorize` on `apply`**

In `PartnerController.java`, change:
```diff
    @Operation(summary = "Apply to become a partner")
-   @PreAuthorize("isAuthenticated()")
+   @PreAuthorize("permitAll()")
    @RateLimit(maxRequests = 3, windowMinutes = 60, keyStrategy = "USER_OR_IP",
               errorMessage = "Too many applications. Please try again later.")
    @PostMapping("/applications")
```

(Leave the rest of `apply`'s signature alone for now — Task 2 changes it to take `HttpServletResponse` and branch on a nullable principal. This task is purely about opening the gate.)

- [ ] **Step 4: Add the `PUBLIC_ENDPOINTS` entries**

In `SecurityConfig.java`, add to the `PUBLIC_ENDPOINTS` array (anywhere in the list; suggested right after the trailer entries, since both are "exact path, single business endpoint" exceptions rather than broad prefixes):

```diff
        "/playback/manifest/episode/*/trailer",
        "/api/v1/playback/manifest/episode/*/trailer",
+       "/partners/applications",
+       "/api/v1/partners/applications",
        "/webhooks/stripe",
```

- [ ] **Step 5: Run the integration test again to verify it now gets past the 401**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.integration.PartnerApplicationAuthIntegrationTest"`
Expected: still FAIL, but now with a 400 (validation failure, since `PartnerApplicationRequest` doesn't accept the account fields yet) rather than 401 — confirms the auth gate is open; Task 2 makes the request body itself valid.

- [ ] **Step 6: Update the existing `PartnerControllerTest.apply_returns201` stub**

This test isn't broken by this step alone (the service signature hasn't changed yet), so no edit needed here — it's updated in Task 2 once the signature changes.

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/partner/controller/PartnerController.java api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java api-service/src/test/java/com/tinniestudio/api/integration/PartnerApplicationAuthIntegrationTest.java
git commit -m "feat: open the partner application endpoint to unauthenticated callers"
```

---

### Task 2: Anonymous branch — create a real account via `AuthService.register`

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/partner/dto/PartnerApplicationRequest.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/partner/controller/PartnerController.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/admin/service/PartnerApplicationService.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/admin/service/PartnerApplicationServiceImpl.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/admin/service/PartnerApplicationServiceTest.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/partner/controller/PartnerControllerTest.java`

- [ ] **Step 1: Add the optional account fields to `PartnerApplicationRequest`**

```diff
 @Getter @Setter @NoArgsConstructor
 public class PartnerApplicationRequest {
     @NotBlank
     @Size(max = 255)
     private String companyName;

     @Size(max = 2000)
     private String description;

     @Size(max = 500)
     private String websiteUrl;
+
+    // Required only when the caller is anonymous — enforced in
+    // PartnerApplicationServiceImpl.apply, not via @NotNull, since these must stay
+    // absent/ignored for an authenticated caller applying with their existing account.
+    @Email
+    @Size(max = 255)
+    private String email;
+
+    @Size(min = 8, max = 128)
+    @Pattern(
+        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]+$",
+        message = "Password must contain at least one uppercase letter, lowercase letter, digit, and special character"
+    )
+    private String password;
+
+    @Size(max = 100)
+    private String firstName;
+
+    @Size(max = 100)
+    private String lastName;
 }
```

Add the needed imports: `jakarta.validation.constraints.Email` and `jakarta.validation.constraints.Pattern` (alongside the existing `NotBlank`/`Size` imports).

- [ ] **Step 2: Write the failing service tests**

Add to `PartnerApplicationServiceTest.java`. First, update the `@Mock`/`@InjectMocks` field list to add the two new dependencies:

```diff
     @Mock PartnerApplicationRepository applicationRepo;
     @Mock PartnerPromotionService partnerPromotionService;
     @Mock AuditLogService auditLogService;
+    @Mock com.tinniestudio.api.modules.auth.service.AuthService authService;
     @InjectMocks PartnerApplicationServiceImpl applicationService;
```

Update the two existing `apply`-related tests to the new signature (they currently call `applicationService.apply(userId, req)` and stub `applicationRepo.existsByUserIdAndStatus`):

```java
    @Test
    void apply_authenticated_createsPendingApplication() {
        UUID userId = UUID.randomUUID();
        org.springframework.security.core.userdetails.UserDetails principal =
            principalFor(userId);
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.empty());
        when(applicationRepo.save(any())).thenAnswer(i -> {
            PartnerApplication saved = i.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme Corp");
        req.setDescription("We make great content");

        PartnerApplicationResponse result = applicationService.apply(principal, req, mock(jakarta.servlet.http.HttpServletResponse.class));

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.companyName()).isEqualTo("Acme Corp");
        verify(authService, never()).register(any(), any());
    }

    @Test
    void apply_authenticated_alreadyPending_throwsBadRequest() {
        UUID userId = UUID.randomUUID();
        org.springframework.security.core.userdetails.UserDetails principal = principalFor(userId);
        PartnerApplication existing = makePendingApp(UUID.randomUUID(), userId);
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.of(existing));

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme");

        assertThatThrownBy(() -> applicationService.apply(principal, req, mock(jakarta.servlet.http.HttpServletResponse.class)))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void apply_authenticated_alreadyApproved_throwsBadRequest() {
        UUID userId = UUID.randomUUID();
        org.springframework.security.core.userdetails.UserDetails principal = principalFor(userId);
        PartnerApplication existing = makePendingApp(UUID.randomUUID(), userId);
        existing.setStatus(PartnerApplicationStatus.APPROVED);
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.of(existing));

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme");

        assertThatThrownBy(() -> applicationService.apply(principal, req, mock(jakarta.servlet.http.HttpServletResponse.class)))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("already a partner");
    }

    @Test
    void apply_authenticated_previouslyRejected_resetsAndReusesTheSameRow() {
        UUID userId = UUID.randomUUID();
        org.springframework.security.core.userdetails.UserDetails principal = principalFor(userId);
        UUID appId = UUID.randomUUID();
        PartnerApplication existing = makePendingApp(appId, userId);
        existing.setStatus(PartnerApplicationStatus.REJECTED);
        existing.setRejectionReason("Not enough detail");
        existing.setReviewedBy(UUID.randomUUID());
        existing.setReviewedAt(java.time.Instant.now());
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.of(existing));
        when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme Corp Reapplied");

        PartnerApplicationResponse result = applicationService.apply(principal, req, mock(jakarta.servlet.http.HttpServletResponse.class));

        assertThat(result.id()).isEqualTo(appId);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.companyName()).isEqualTo("Acme Corp Reapplied");
        assertThat(result.rejectionReason()).isNull();
        assertThat(existing.getReviewedBy()).isNull();
        assertThat(existing.getReviewedAt()).isNull();
        verify(applicationRepo, times(1)).save(any());
    }

    @Test
    void apply_anonymous_createsAccountViaAuthServiceThenApplication() {
        UUID newUserId = UUID.randomUUID();
        var authResponse = com.tinniestudio.api.modules.auth.user.dto.AuthProfileResponse.builder()
            .userId(newUserId).email("new-partner@example.com").build();
        when(authService.register(any(), any())).thenReturn(authResponse);
        when(applicationRepo.findByUserId(newUserId)).thenReturn(java.util.Optional.empty());
        when(applicationRepo.save(any())).thenAnswer(i -> {
            PartnerApplication saved = i.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("New Co");
        req.setEmail("new-partner@example.com");
        req.setPassword("Str0ng!Pass");
        req.setFirstName("New");
        req.setLastName("Partner");

        PartnerApplicationResponse result = applicationService.apply(null, req, mock(jakarta.servlet.http.HttpServletResponse.class));

        assertThat(result.companyName()).isEqualTo("New Co");
        assertThat(result.userId()).isEqualTo(newUserId);
        verify(authService).register(argThat(r ->
            r.getEmail().equals("new-partner@example.com") && r.getPassword().equals("Str0ng!Pass")
        ), any());
    }

    @Test
    void apply_anonymous_duplicateEmail_bubblesUpEmailAlreadyExistsException() {
        when(authService.register(any(), any()))
            .thenThrow(new com.tinniestudio.api.modules.auth.exception.EmailAlreadyExistsException(
                "Email address is already registered"));

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("New Co");
        req.setEmail("existing@example.com");
        req.setPassword("Str0ng!Pass");
        req.setFirstName("New");
        req.setLastName("Partner");

        assertThatThrownBy(() -> applicationService.apply(null, req, mock(jakarta.servlet.http.HttpServletResponse.class)))
            .isInstanceOf(com.tinniestudio.api.modules.auth.exception.EmailAlreadyExistsException.class);

        verify(applicationRepo, never()).save(any());
    }
```

Add this helper near `makePendingApp` in the same test class:

```java
    private org.springframework.security.core.userdetails.UserDetails principalFor(UUID userId) {
        org.springframework.security.core.userdetails.UserDetails principal =
            mock(org.springframework.security.core.userdetails.UserDetails.class);
        lenient().when(principal.getUsername()).thenReturn(userId.toString());
        return principal;
    }
```

Add `import static org.mockito.Mockito.lenient;` and `import static org.mockito.Mockito.mock;` and `import static org.mockito.ArgumentMatchers.argThat;` to the existing static imports if not already covered by the `import static org.mockito.Mockito.*;` wildcard (it is — `mock`/`lenient` are both `Mockito.*` static methods, already covered; `argThat` is `ArgumentMatchers.*`, already covered by the existing wildcard too — no new import lines actually needed, the wildcards already in the file cover all of this).

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.admin.service.PartnerApplicationServiceTest"`
Expected: compilation failure — `apply(UserDetails, PartnerApplicationRequest, HttpServletResponse)` doesn't exist yet (current signature is `apply(UUID, PartnerApplicationRequest)`), `authService` field doesn't exist on the service yet.

- [ ] **Step 4: Update the `PartnerApplicationService` interface**

```diff
+import org.springframework.security.core.userdetails.UserDetails;
+import jakarta.servlet.http.HttpServletResponse;
+
 public interface PartnerApplicationService {
-    PartnerApplicationResponse apply(UUID userId, PartnerApplicationRequest req);
+    PartnerApplicationResponse apply(UserDetails principal, PartnerApplicationRequest req, HttpServletResponse response);
     Page<PartnerApplicationResponse> list(PartnerApplicationStatus status, Pageable pageable);
     PartnerApplicationResponse approve(UUID applicationId, UUID adminId);
     PartnerApplicationResponse reject(UUID applicationId, RejectApplicationRequest req, UUID adminId);
+    PartnerApplicationResponse getByUserId(UUID userId);
 }
```

(`getByUserId` is added here too, for Task 3 — declaring it now avoids a second interface-edit task.)

- [ ] **Step 5: Rewrite `apply` in `PartnerApplicationServiceImpl`**

```java
    @Override
    @Transactional
    public PartnerApplicationResponse apply(UserDetails principal, PartnerApplicationRequest req, HttpServletResponse response) {
        UUID userId;
        if (principal != null) {
            userId = com.tinniestudio.api.shared.security.CurrentUser.id(principal);
        } else {
            RegisterRequest registerReq = new RegisterRequest();
            registerReq.setEmail(req.getEmail());
            registerReq.setPassword(req.getPassword());
            registerReq.setFirstName(req.getFirstName());
            registerReq.setLastName(req.getLastName());
            AuthProfileResponse newAccount = authService.register(registerReq, response);
            userId = newAccount.getUserId();
        }

        Optional<PartnerApplication> existing = applicationRepo.findByUserId(userId);
        if (existing.isPresent()) {
            PartnerApplicationStatus status = existing.get().getStatus();
            if (status == PartnerApplicationStatus.PENDING) {
                throw new BadRequestException("A pending partner application already exists");
            }
            if (status == PartnerApplicationStatus.APPROVED) {
                throw new BadRequestException("You are already a partner");
            }
            // REJECTED: reset and reuse the same row
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

        PartnerApplication app = new PartnerApplication();
        app.setUserId(userId);
        app.setCompanyName(req.getCompanyName());
        app.setDescription(req.getDescription());
        app.setWebsiteUrl(req.getWebsiteUrl());
        return PartnerApplicationResponse.from(applicationRepo.save(app));
    }
```

Add the needed imports at the top of `PartnerApplicationServiceImpl.java`:

```diff
 import com.tinniestudio.api.modules.partner.repository.PartnerApplicationRepository;
 import com.tinniestudio.api.modules.partner.service.PartnerPromotionService;
+import com.tinniestudio.api.modules.auth.dto.RegisterRequest;
+import com.tinniestudio.api.modules.auth.service.AuthService;
+import com.tinniestudio.api.modules.auth.user.dto.AuthProfileResponse;
 import com.tinniestudio.api.shared.entity.*;
 import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
 import com.tinniestudio.api.shared.exception.BadRequestException;
 import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
+import org.springframework.security.core.userdetails.UserDetails;
+import jakarta.servlet.http.HttpServletResponse;
 import lombok.RequiredArgsConstructor;
 import org.springframework.data.domain.Page;
 import org.springframework.data.domain.Pageable;
 import org.springframework.stereotype.Service;
 import org.springframework.transaction.annotation.Transactional;
 import java.time.Instant;
+import java.util.Optional;
 import java.util.UUID;
```

Add the new constructor dependency (Lombok `@RequiredArgsConstructor` picks this up automatically from field order):

```diff
     private final PartnerApplicationRepository applicationRepo;
     private final PartnerPromotionService partnerPromotionService;
     private final AuditLogService auditLogService;
+    private final AuthService authService;
```

- [ ] **Step 6: Update the controller to match the new service signature**

```diff
     @Operation(summary = "Apply to become a partner")
     @PreAuthorize("permitAll()")
     @RateLimit(maxRequests = 3, windowMinutes = 60, keyStrategy = "USER_OR_IP",
                errorMessage = "Too many applications. Please try again later.")
     @PostMapping("/applications")
     public ResponseEntity<PartnerApplicationResponse> apply(
             @AuthenticationPrincipal UserDetails principal,
-            @Valid @RequestBody PartnerApplicationRequest req) {
-        UUID userId = CurrentUser.id(principal);
-        return ResponseEntity.status(HttpStatus.CREATED).body(applicationService.apply(userId, req));
+            @Valid @RequestBody PartnerApplicationRequest req,
+            HttpServletResponse response) {
+        return ResponseEntity.status(HttpStatus.CREATED).body(applicationService.apply(principal, req, response));
     }
```

Add `import jakarta.servlet.http.HttpServletResponse;` to `PartnerController.java`'s imports. (`CurrentUser` is no longer used directly in this method, but is likely still used elsewhere in the file for the other endpoints — leave the import alone; don't remove it.)

- [ ] **Step 7: Update `PartnerControllerTest.apply_returns201` for the new service call shape**

```diff
     @Test
     @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
     void apply_returns201() throws Exception {
         PartnerApplicationRequest req = new PartnerApplicationRequest();
         req.setCompanyName("Acme Corp");
         req.setDescription("We make great content");
         req.setWebsiteUrl("https://acme.com");

-        when(applicationService.apply(any(), any())).thenReturn(sampleApplication());
+        when(applicationService.apply(any(), any(), any())).thenReturn(sampleApplication());

         mockMvc.perform(post("/partners/applications")
                 .contentType(MediaType.APPLICATION_JSON)
                 .content(objectMapper.writeValueAsString(req)))
             .andExpect(status().isCreated())
             .andExpect(jsonPath("$.data.companyName").value("Acme Corp"));
     }

+    @Test
+    void apply_anonymous_returns201() throws Exception {
+        PartnerApplicationRequest req = new PartnerApplicationRequest();
+        req.setCompanyName("Acme Corp");
+        req.setEmail("anon@example.com");
+        req.setPassword("Str0ng!Pass");
+        req.setFirstName("Anon");
+        req.setLastName("Applicant");
+
+        when(applicationService.apply(any(), any(), any())).thenReturn(sampleApplication());
+
+        // Deliberately no @WithMockUser — proves this endpoint's controller layer doesn't
+        // require a populated principal to execute.
+        mockMvc.perform(post("/partners/applications")
+                .contentType(MediaType.APPLICATION_JSON)
+                .content(objectMapper.writeValueAsString(req)))
+            .andExpect(status().isCreated())
+            .andExpect(jsonPath("$.data.companyName").value("Acme Corp"));
+    }
```

- [ ] **Step 8: Run both test files to verify they pass**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.admin.service.PartnerApplicationServiceTest" --tests "com.tinniestudio.api.modules.partner.controller.PartnerControllerTest"`
Expected: PASS — all tests green (7 in the service test: the original 5 unrelated to `apply` — `approve_*`/`reject_*` — plus the 6 new/updated `apply_*` ones described above; 9 in the controller test: the original 8 plus the new `apply_anonymous_returns201`).

- [ ] **Step 9: Run the integration test from Task 1 again**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.integration.PartnerApplicationAuthIntegrationTest"`
Expected: PASS — 201 Created, no auth required, a real account actually created against Testcontainers Postgres.

- [ ] **Step 10: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/partner/dto/PartnerApplicationRequest.java api-service/src/main/java/com/tinniestudio/api/modules/partner/controller/PartnerController.java api-service/src/main/java/com/tinniestudio/api/modules/admin/service/PartnerApplicationService.java api-service/src/main/java/com/tinniestudio/api/modules/admin/service/PartnerApplicationServiceImpl.java api-service/src/test/java/com/tinniestudio/api/modules/admin/service/PartnerApplicationServiceTest.java api-service/src/test/java/com/tinniestudio/api/modules/partner/controller/PartnerControllerTest.java
git commit -m "feat: anonymous partner applications create a real account via AuthService.register"
```

---

### Task 3: Reuse-one-row upsert is already in place — add the repository regression test

Task 2 already implemented the upsert logic (it had to, to write a coherent `apply` method). This task adds a repository-level regression test proving `findByUserId` really does return the single existing row rather than Spring Data silently picking "first of many" if duplicates ever exist from before this fix shipped.

**Files:**
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/partner/repository/PartnerApplicationRepositoryTest.java` (new)

- [ ] **Step 1: Write the test**

```java
package com.tinniestudio.api.modules.partner.repository;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.modules.role.repository.RoleRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import com.tinniestudio.api.shared.entity.PartnerApplication;
import com.tinniestudio.api.shared.entity.RoleName;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for PartnerApplicationServiceImpl.apply's upsert logic: findByUserId must
 * resolve to exactly one row per user once the reuse-the-same-row fix is in place (previously
 * apply() blindly inserted, so a user could accumulate multiple rows — this proves the new
 * single-row-per-user invariant holds at the repository layer, independent of the service's
 * own logic being correct).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureTestEntityManager
@Testcontainers
@ActiveProfiles("test")
class PartnerApplicationRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_partner_app_repo_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired private PartnerApplicationRepository applicationRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private RoleRepository roleRepo;

    private UUID newUser() {
        User user = new User();
        user.setEmail("repo-test-" + System.nanoTime() + "@example.com");
        user.setPasswordHash("irrelevant");
        roleRepo.findByName(RoleName.ROLE_USER).ifPresent(user::addRole);
        return userRepo.saveAndFlush(user).getId();
    }

    @Test
    void findByUserId_returnsTheSingleRowForThatUser() {
        UUID userId = newUser();
        PartnerApplication app = new PartnerApplication();
        app.setUserId(userId);
        app.setCompanyName("Acme Corp");
        applicationRepo.saveAndFlush(app);

        Optional<PartnerApplication> found = applicationRepo.findByUserId(userId);

        assertThat(found).isPresent();
        assertThat(found.get().getCompanyName()).isEqualTo("Acme Corp");
    }

    @Test
    void findByUserId_resettingAndReusingTheSameRow_doesNotCreateASecondRow() {
        UUID userId = newUser();
        PartnerApplication app = new PartnerApplication();
        app.setUserId(userId);
        app.setCompanyName("First Attempt");
        app.setStatus(PartnerApplicationStatus.REJECTED);
        PartnerApplication saved = applicationRepo.saveAndFlush(app);

        // Simulate the service's reset-and-reuse path directly at the repository layer.
        saved.setCompanyName("Second Attempt");
        saved.setStatus(PartnerApplicationStatus.PENDING);
        applicationRepo.saveAndFlush(saved);

        long countForUser = applicationRepo.findByUserId(userId)
            .map(a -> 1L).orElse(0L);
        assertThat(countForUser).isEqualTo(1L);
        assertThat(applicationRepo.findByUserId(userId).get().getCompanyName()).isEqualTo("Second Attempt");
        assertThat(applicationRepo.findByUserId(userId).get().getId()).isEqualTo(saved.getId());
    }
}
```

(`ContentRepository`/`ContentTypeRepository` imports listed above aren't actually used by this test — remove them if your IDE/compiler flags them; they were included by mistake when drafting against the trailer-resilience test file as a template and aren't needed here, since `PartnerApplication` has no content relationship.)

- [ ] **Step 2: Run the test to verify it fails to compile first, then passes**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.partner.repository.PartnerApplicationRepositoryTest"`
Expected: PASS (2 tests) once any import issues from the note above are cleaned up — this is a straightforward repository test against already-existing, unmodified `findByUserId`/`saveAndFlush` methods, so there's no "make it fail first" step with new production code — the behavior under test already exists.

- [ ] **Step 3: Commit**

```bash
git add api-service/src/test/java/com/tinniestudio/api/modules/partner/repository/PartnerApplicationRepositoryTest.java
git commit -m "test: add repository-level regression coverage for one-application-per-user"
```

---

### Task 4: Self-service status endpoint

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/admin/service/PartnerApplicationServiceImpl.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/partner/controller/PartnerController.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/admin/service/PartnerApplicationServiceTest.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/partner/controller/PartnerControllerTest.java`

- [ ] **Step 1: Write the failing service tests**

Add to `PartnerApplicationServiceTest.java`:

```java
    @Test
    void getByUserId_returnsTheApplication() {
        UUID userId = UUID.randomUUID();
        PartnerApplication app = makePendingApp(UUID.randomUUID(), userId);
        app.setRejectionReason(null);
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.of(app));

        PartnerApplicationResponse result = applicationService.getByUserId(userId);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.companyName()).isEqualTo("Acme Corp");
    }

    @Test
    void getByUserId_noApplication_throwsResourceNotFound() {
        UUID userId = UUID.randomUUID();
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> applicationService.getByUserId(userId))
            .isInstanceOf(com.tinniestudio.api.shared.exception.ResourceNotFoundException.class);
    }

    @Test
    void getByUserId_rejected_includesRejectionReason() {
        UUID userId = UUID.randomUUID();
        PartnerApplication app = makePendingApp(UUID.randomUUID(), userId);
        app.setStatus(PartnerApplicationStatus.REJECTED);
        app.setRejectionReason("Incomplete submission");
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.of(app));

        PartnerApplicationResponse result = applicationService.getByUserId(userId);

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.rejectionReason()).isEqualTo("Incomplete submission");
    }
```

- [ ] **Step 2: Run the tests to verify they fail to compile**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.admin.service.PartnerApplicationServiceTest"`
Expected: compilation failure — `getByUserId` doesn't exist on `PartnerApplicationServiceImpl` yet (it's already declared on the interface from Task 2 Step 4).

- [ ] **Step 3: Implement `getByUserId`**

Add to `PartnerApplicationServiceImpl.java`, after `apply`:

```java
    @Override
    @Transactional(readOnly = true)
    public PartnerApplicationResponse getByUserId(UUID userId) {
        return applicationRepo.findByUserId(userId)
            .map(PartnerApplicationResponse::from)
            .orElseThrow(() -> new ResourceNotFoundException("No application found"));
    }
```

- [ ] **Step 4: Run the service tests to verify they pass**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.admin.service.PartnerApplicationServiceTest"`
Expected: PASS (all tests, including the 3 new ones).

- [ ] **Step 5: Write the failing controller test**

Add to `PartnerControllerTest.java`:

```java
    @Test
    @WithMockUser(username = PARTNER_ID, roles = "USER")
    void getMyApplication_returns200() throws Exception {
        when(applicationService.getByUserId(any())).thenReturn(sampleApplication());

        mockMvc.perform(get("/partners/applications/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PENDING"));
    }
```

(Note `roles = "USER"`, not `"PARTNER"` — this endpoint is for a non-partner applicant checking their status, which is the entire point.)

- [ ] **Step 6: Run the controller test to verify it fails**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.partner.controller.PartnerControllerTest"`
Expected: FAIL — `GET /partners/applications/me` doesn't exist yet (404), or the class-level `@PreAuthorize("hasRole('PARTNER')")` rejects a `ROLE_USER` principal (403) — either way, not 200.

- [ ] **Step 7: Add the controller endpoint**

In `PartnerController.java`, add after `apply`:

```java
    @Operation(summary = "Get the authenticated user's own partner application, if any")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/applications/me")
    public ResponseEntity<PartnerApplicationResponse> getMyApplication(
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(applicationService.getByUserId(userId));
    }
```

(`@PreAuthorize("isAuthenticated()")` overrides the class-level `hasRole('PARTNER')`, same pattern `apply()` already used before Task 1 opened it further — this endpoint stays authenticated, just not partner-restricted.)

- [ ] **Step 8: Run the controller test to verify it passes**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.partner.controller.PartnerControllerTest"`
Expected: PASS — all tests green.

- [ ] **Step 9: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/admin/service/PartnerApplicationServiceImpl.java api-service/src/main/java/com/tinniestudio/api/modules/partner/controller/PartnerController.java api-service/src/test/java/com/tinniestudio/api/modules/admin/service/PartnerApplicationServiceTest.java api-service/src/test/java/com/tinniestudio/api/modules/partner/controller/PartnerControllerTest.java
git commit -m "feat: add self-service GET /partners/applications/me status endpoint"
```

---

## Summary

After this plan: `POST /partners/applications` is reachable with or without a session — anonymous callers get a real account created via the exact same path `/auth/register` uses, logged-in callers behave exactly as before. The upsert-by-`findByUserId` logic replaces today's blind-insert (which never checked for REJECTED/APPROVED rows), so reapplying after rejection resets the same row instead of silently accumulating duplicates. `GET /partners/applications/me` closes the documented self-service status gap, authenticated but not partner-restricted. A real-filter-chain integration test proves the whole thing is actually unauthenticated-reachable, not just coded to look that way.
