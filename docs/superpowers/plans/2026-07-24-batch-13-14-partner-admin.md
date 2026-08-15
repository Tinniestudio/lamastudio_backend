# Batch 13+14 — Partner Portal + Admin Moderation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Partner Portal (self-service dashboard, profile, content management, uploads) and Admin Moderation system (user management, partner application flow, admin dashboard, audit log).

**Architecture:** Two new module packages — `modules/partner/` and `modules/admin/` — each with controller/service/repository/dto layers. Shared entities (`PartnerProfile`, `PartnerApplication`, `AuditLog`) live in `shared/entity/`. Admin dashboard uses `RabbitAdmin` (added to `RabbitConfig`) for live queue depth. Token revocation on suspension/ban reuses existing `SessionService.revokeAllUserSessions()`.

**Tech Stack:** Spring Boot 3.3.5 / Java 21, JPA + Flyway (next migration V35), `RabbitAdmin` (Spring AMQP), `StorageService.uploadFile()` for logo upload, `@PreAuthorize` role guards, `@WebMvcTest` + `@MockBean` for controller tests, Mockito for service unit tests.

---

## Critical Context — Read Before Starting

- **`AdminContentController`** (`modules/content/controller/`) already has `approve`, `reject`, `publish`, `archive`, `submit`, `feature`, `create`, `update`, `delete`. **Do NOT duplicate these endpoints.**
- **`AdminReviewController`** already exists. Do NOT duplicate.
- **`AdminSubscriptionController`** already exists. Do NOT duplicate.
- **`SessionService.revokeAllUserSessions(userId, adminId)`** already exists in `modules/auth/user/service/` — use it to revoke tokens on suspend/ban.
- **`StorageService.uploadFile(key, bytes, contentType)`** exists in `shared/storage/` — use for server-side logo uploads.
- **Role guard pattern**: use `@PreAuthorize("hasRole('PARTNER')")` or `@PreAuthorize("hasRole('ADMIN')")` on controllers. All endpoints go through the user JWT filter chain.
- **Response envelope**: all 2xx responses wrapped by `SuccessResponseWrapper`. Tests assert `$.data.*`.
- **`userId()` helper**: `@AuthenticationPrincipal UserDetails p` → `UUID.fromString(p.getUsername())`. Throws `AuthenticationCredentialsNotFoundException` if null.
- **Test mock beans**: every `@WebMvcTest` must `@MockBean` `JwtTokenProvider`, `JwtAuthenticationFilter`, `UserDetailsServiceImpl`.
- **`RabbitAdmin`** is NOT yet in `RabbitConfig` — add it in Task 12.
- **`file_size_bytes`** column does not yet exist on `upload_sessions` — added in V38.
- **`AccountStatus`** enum: currently `ACTIVE`, `SUSPENDED`, `DELETED`. Add `BAN` in Task 2.
- **Partner application uniqueness**: only one PENDING application per user allowed (partial unique index). Rejected users may re-apply.

---

## File Map

**New migrations:**
- `V35__add_partner_profiles.sql`
- `V36__add_partner_applications.sql`
- `V37__add_audit_logs.sql`
- `V38__add_upload_file_size.sql`

**New shared entities:**
- `shared/entity/PartnerProfile.java`
- `shared/entity/PartnerApplication.java`
- `shared/entity/AuditLog.java`

**Modified shared:**
- `shared/entity/DomainEnums.java` — add `BAN` to `AccountStatus`, add `PartnerApplicationStatus`
- `shared/queue/RabbitConfig.java` — add `RabbitAdmin` bean

**New partner module (`modules/partner/`):**
- `repository/PartnerProfileRepository.java`
- `repository/PartnerApplicationRepository.java`
- `dto/PartnerProfileResponse.java`
- `dto/UpdatePartnerProfileRequest.java`
- `dto/PartnerApplicationRequest.java`
- `dto/PartnerApplicationResponse.java`
- `dto/PartnerDashboardResponse.java`
- `dto/PartnerUploadsResponse.java`
- `service/PartnerService.java`
- `service/PartnerServiceImpl.java`
- `controller/PartnerController.java`

**New admin module (`modules/admin/`):**
- `repository/AuditLogRepository.java`
- `dto/AdminDashboardResponse.java`
- `dto/AdminUserResponse.java`
- `dto/UpdateUserRequest.java`
- `dto/UpdateUserStatusRequest.java`
- `dto/RejectApplicationRequest.java`
- `dto/AuditLogResponse.java`
- `service/AdminUserService.java`
- `service/AdminUserServiceImpl.java`
- `service/AdminDashboardService.java`
- `service/AdminDashboardServiceImpl.java`
- `service/AuditLogService.java`
- `service/AuditLogServiceImpl.java`
- `service/PartnerApplicationService.java`
- `service/PartnerApplicationServiceImpl.java`
- `controller/AdminUserController.java`
- `controller/AdminDashboardController.java`
- `controller/AdminPartnerApplicationController.java`
- `controller/AuditLogController.java`

**Modified existing:**
- `modules/content/controller/AdminContentController.java` — add `GET /admin/contents`
- `modules/upload/controller/UploadController.java` — capture `file_size_bytes` on session complete
- `modules/user/repository/UserRepository.java` — add admin query methods

---

## Task 1: DB Migrations V35–V38

**Files:**
- Create: `api-service/src/main/resources/db/migration/V35__add_partner_profiles.sql`
- Create: `api-service/src/main/resources/db/migration/V36__add_partner_applications.sql`
- Create: `api-service/src/main/resources/db/migration/V37__add_audit_logs.sql`
- Create: `api-service/src/main/resources/db/migration/V38__add_upload_file_size.sql`

- [ ] **Step 1: Write V35 — partner_profiles**

```sql
-- V35__add_partner_profiles.sql
CREATE TABLE IF NOT EXISTS partner_profiles (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID         NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    company_name             VARCHAR(255),
    website_url              VARCHAR(500),
    bio                      TEXT,
    logo_url                 VARCHAR(500),
    revenue_share_percentage NUMERIC(5,2) NOT NULL DEFAULT 70.00,
    is_verified              BOOLEAN      NOT NULL DEFAULT true,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_partner_profiles_user ON partner_profiles(user_id);
```

- [ ] **Step 2: Write V36 — partner_applications**

```sql
-- V36__add_partner_applications.sql
CREATE TABLE IF NOT EXISTS partner_applications (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    company_name     VARCHAR(255) NOT NULL,
    description      TEXT,
    website_url      VARCHAR(500),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    rejection_reason TEXT,
    reviewed_by      UUID        REFERENCES users(id),
    reviewed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Only one PENDING application per user; rejected users may re-apply
CREATE UNIQUE INDEX uq_partner_app_user_pending
    ON partner_applications(user_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_partner_applications_status ON partner_applications(status);
CREATE INDEX idx_partner_applications_user   ON partner_applications(user_id);
```

- [ ] **Step 3: Write V37 — audit_logs**

```sql
-- V37__add_audit_logs.sql
CREATE TABLE IF NOT EXISTS audit_logs (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id    UUID,
    actor_type  VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    action      VARCHAR(100) NOT NULL,
    target_type VARCHAR(50),
    target_id   UUID,
    reason      TEXT,
    metadata    TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_actor   ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_target  ON audit_logs(target_type, target_id);
CREATE INDEX idx_audit_logs_created ON audit_logs(created_at DESC);
```

- [ ] **Step 4: Write V38 — upload file size**

```sql
-- V38__add_upload_file_size.sql
ALTER TABLE upload_sessions
    ADD COLUMN IF NOT EXISTS file_size_bytes BIGINT NOT NULL DEFAULT 0;
```

- [ ] **Step 5: Verify migrations apply cleanly**

```bash
./gradlew :api-service:flywayMigrate
```
Expected: `Successfully applied 4 migrations`

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/resources/db/migration/V35__add_partner_profiles.sql \
        api-service/src/main/resources/db/migration/V36__add_partner_applications.sql \
        api-service/src/main/resources/db/migration/V37__add_audit_logs.sql \
        api-service/src/main/resources/db/migration/V38__add_upload_file_size.sql
git commit -m "feat(partner-admin): add DB migrations V35-V38 (partner_profiles, partner_applications, audit_logs, file_size_bytes)"
```

---

## Task 2: Enums, Entities, Repositories

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/shared/entity/PartnerProfile.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/shared/entity/PartnerApplication.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/shared/entity/AuditLog.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/partner/repository/PartnerProfileRepository.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/partner/repository/PartnerApplicationRepository.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/admin/repository/AuditLogRepository.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/user/repository/UserRepository.java`

- [ ] **Step 1: Add enums to DomainEnums.java**

In `DomainEnums.java`, inside the outer class, add after the existing enums:

```java
public enum PartnerApplicationStatus {
    PENDING, APPROVED, REJECTED
}
```

Also add `BAN` to `AccountStatus`:
```java
public enum AccountStatus {
    ACTIVE,
    SUSPENDED,
    BAN,
    DELETED
}
```

- [ ] **Step 2: Create PartnerProfile entity**

```java
package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "partner_profiles")
@Getter @Setter @NoArgsConstructor
public class PartnerProfile extends BaseEntity {

    @Column(nullable = false, unique = true)
    private UUID userId;

    private String companyName;
    private String websiteUrl;

    @Column(columnDefinition = "TEXT")
    private String bio;

    private String logoUrl;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal revenueSharePercentage = BigDecimal.valueOf(70.00);

    @Column(nullable = false)
    private Boolean isVerified = true;
}
```

- [ ] **Step 3: Create PartnerApplication entity**

```java
package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "partner_applications")
@Getter @Setter @NoArgsConstructor
public class PartnerApplication extends BaseEntity {

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String companyName;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String websiteUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartnerApplicationStatus status = PartnerApplicationStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String rejectionReason;

    private UUID reviewedBy;
    private Instant reviewedAt;
}
```

- [ ] **Step 4: Create AuditLog entity**

```java
package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Getter @Setter @NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID actorId;

    @Column(nullable = false)
    private String actorType = "ADMIN";

    @Column(nullable = false)
    private String action;

    private String targetType;
    private UUID targetId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @CreationTimestamp
    private Instant createdAt;
}
```

- [ ] **Step 5: Create PartnerProfileRepository**

```java
package com.tinniestudio.api.modules.partner.repository;

import com.tinniestudio.api.shared.entity.PartnerProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PartnerProfileRepository extends JpaRepository<PartnerProfile, UUID> {
    Optional<PartnerProfile> findByUserId(UUID userId);
    boolean existsByUserId(UUID userId);
}
```

- [ ] **Step 6: Create PartnerApplicationRepository**

```java
package com.tinniestudio.api.modules.partner.repository;

import com.tinniestudio.api.shared.entity.PartnerApplication;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PartnerApplicationRepository extends JpaRepository<PartnerApplication, UUID> {
    Page<PartnerApplication> findByStatusOrderByCreatedAtDesc(PartnerApplicationStatus status, Pageable pageable);
    Page<PartnerApplication> findAllByOrderByCreatedAtDesc(Pageable pageable);
    boolean existsByUserIdAndStatus(UUID userId, PartnerApplicationStatus status);
    Optional<PartnerApplication> findByUserId(UUID userId);
}
```

- [ ] **Step 7: Create AuditLogRepository**

```java
package com.tinniestudio.api.modules.admin.repository;

import com.tinniestudio.api.shared.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Page<AuditLog> findByActorIdOrderByCreatedAtDesc(UUID actorId, Pageable pageable);
    Page<AuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, UUID targetId, Pageable pageable);
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
```

- [ ] **Step 8: Add admin query methods to UserRepository**

Add to the existing `UserRepository.java`:

```java
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

// Add these methods:
Page<User> findAllByOrderByCreatedAtDesc(Pageable pageable);

@Query("""
    SELECT u FROM User u
    WHERE (:role IS NULL OR EXISTS (
        SELECT r FROM u.roles r WHERE r.name = :role
    ))
    AND (:status IS NULL OR u.accountStatus = :status)
    AND (:search IS NULL OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%')))
    ORDER BY u.createdAt DESC
    """)
Page<User> findByFilters(
    @Param("role")   String role,
    @Param("status") AccountStatus status,
    @Param("search") String search,
    Pageable pageable
);

long countByCreatedAtAfter(java.time.Instant after);
```

- [ ] **Step 9: Verify compilation**

```bash
./gradlew :api-service:compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/ \
        api-service/src/main/java/com/tinniestudio/api/modules/partner/repository/ \
        api-service/src/main/java/com/tinniestudio/api/modules/admin/repository/ \
        api-service/src/main/java/com/tinniestudio/api/modules/user/repository/UserRepository.java
git commit -m "feat(partner-admin): add PartnerProfile, PartnerApplication, AuditLog entities and repositories"
```

---

## Task 3: AuditLogService TDD

**Files:**
- Create: `modules/admin/service/AuditLogService.java`
- Create: `modules/admin/service/AuditLogServiceImpl.java`
- Create: `modules/admin/dto/AuditLogResponse.java`
- Test: `src/test/java/com/tinniestudio/api/modules/admin/service/AuditLogServiceTest.java`

- [ ] **Step 1: Create AuditLogResponse record**

```java
package com.tinniestudio.api.modules.admin.dto;

import com.tinniestudio.api.shared.entity.AuditLog;
import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
    UUID id,
    UUID actorId,
    String actorType,
    String action,
    String targetType,
    UUID targetId,
    String reason,
    String metadata,
    Instant createdAt
) {
    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
            log.getId(), log.getActorId(), log.getActorType(),
            log.getAction(), log.getTargetType(), log.getTargetId(),
            log.getReason(), log.getMetadata(), log.getCreatedAt()
        );
    }
}
```

- [ ] **Step 2: Write failing tests**

```java
package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AuditLogResponse;
import com.tinniestudio.api.modules.admin.repository.AuditLogRepository;
import com.tinniestudio.api.shared.entity.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock AuditLogRepository auditLogRepo;
    @InjectMocks AuditLogServiceImpl auditLogService;

    @Test
    void log_savesEntryWithCorrectFields() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        when(auditLogRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        auditLogService.log("USER_SUSPENDED", actorId, "USER", targetId, "Violation", null);

        verify(auditLogRepo).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getAction()).isEqualTo("USER_SUSPENDED");
        assertThat(saved.getActorId()).isEqualTo(actorId);
        assertThat(saved.getTargetType()).isEqualTo("USER");
        assertThat(saved.getTargetId()).isEqualTo(targetId);
        assertThat(saved.getReason()).isEqualTo("Violation");
    }

    @Test
    void listAll_returnsPaginatedAuditLogs() {
        AuditLog entry = new AuditLog();
        ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());
        entry.setAction("USER_SUSPENDED");
        entry.setActorType("ADMIN");
        when(auditLogRepo.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(entry)));

        var result = auditLogService.listAll(Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).action()).isEqualTo("USER_SUSPENDED");
    }
}
```

- [ ] **Step 3: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "*.AuditLogServiceTest"
```
Expected: `FAILED — AuditLogServiceImpl not found`

- [ ] **Step 4: Create AuditLogService interface**

```java
package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AuditLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface AuditLogService {
    void log(String action, UUID actorId, String targetType, UUID targetId, String reason, String metadata);
    Page<AuditLogResponse> listAll(Pageable pageable);
    Page<AuditLogResponse> listByTarget(String targetType, UUID targetId, Pageable pageable);
}
```

- [ ] **Step 5: Create AuditLogServiceImpl**

```java
package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AuditLogResponse;
import com.tinniestudio.api.modules.admin.repository.AuditLogRepository;
import com.tinniestudio.api.shared.entity.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository auditLogRepo;

    @Override
    @Transactional
    public void log(String action, UUID actorId, String targetType, UUID targetId, String reason, String metadata) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorType(actorId != null ? "ADMIN" : "SYSTEM");
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setReason(reason);
        entry.setMetadata(metadata);
        auditLogRepo.save(entry);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> listAll(Pageable pageable) {
        return auditLogRepo.findAllByOrderByCreatedAtDesc(pageable).map(AuditLogResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> listByTarget(String targetType, UUID targetId, Pageable pageable) {
        return auditLogRepo.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId, pageable)
            .map(AuditLogResponse::from);
    }
}
```

- [ ] **Step 6: Run tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.AuditLogServiceTest"
```
Expected: `2 tests passed`

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/admin/service/AuditLogService*.java \
        api-service/src/main/java/com/tinniestudio/api/modules/admin/dto/AuditLogResponse.java \
        api-service/src/test/java/com/tinniestudio/api/modules/admin/service/AuditLogServiceTest.java
git commit -m "feat(partner-admin): implement AuditLogService with log/list operations (TDD)"
```

---

## Task 4: AdminUserService TDD

**Files:**
- Create: `modules/admin/service/AdminUserService.java`
- Create: `modules/admin/service/AdminUserServiceImpl.java`
- Create: `modules/admin/dto/AdminUserResponse.java`
- Create: `modules/admin/dto/UpdateUserStatusRequest.java`
- Create: `modules/admin/dto/UpdateUserRequest.java`
- Test: `src/test/java/.../admin/service/AdminUserServiceTest.java`

- [ ] **Step 1: Create DTOs**

```java
// AdminUserResponse.java
package com.tinniestudio.api.modules.admin.dto;

import com.tinniestudio.api.shared.entity.User;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record AdminUserResponse(
    UUID id,
    String email,
    String accountStatus,
    Set<String> roles,
    boolean emailVerified,
    Instant createdAt
) {
    public static AdminUserResponse from(User u) {
        return new AdminUserResponse(
            u.getId(), u.getEmail(),
            u.getAccountStatus().name(),
            u.getRoles().stream().map(r -> r.getName().name()).collect(Collectors.toSet()),
            u.isEmailVerified(),
            u.getCreatedAt()
        );
    }
}
```

```java
// UpdateUserStatusRequest.java
package com.tinniestudio.api.modules.admin.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class UpdateUserStatusRequest {
    @NotNull
    private AccountStatus status;
    private String reason;
}
```

```java
// UpdateUserRequest.java
package com.tinniestudio.api.modules.admin.dto;

import jakarta.validation.constraints.Email;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class UpdateUserRequest {
    @Email
    private String email;
    private Boolean emailVerified;
}
```

- [ ] **Step 2: Write failing tests**

```java
package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AdminUserResponse;
import com.tinniestudio.api.modules.admin.dto.UpdateUserStatusRequest;
import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock UserRepository userRepo;
    @Mock SessionService sessionService;
    @Mock AuditLogService auditLogService;
    @InjectMocks AdminUserServiceImpl adminUserService;

    private User makeUser(UUID id, AccountStatus status) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setEmail("test@example.com");
        u.setAccountStatus(status);
        u.setRoles(Set.of());
        return u;
    }

    @Test
    void listUsers_returnsPagedUsers() {
        UUID id = UUID.randomUUID();
        when(userRepo.findByFilters(isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(java.util.List.of(makeUser(id, AccountStatus.ACTIVE))));

        var result = adminUserService.listUsers(null, null, null, Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).accountStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void updateStatus_suspended_revokesTokens() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User user = makeUser(userId, AccountStatus.ACTIVE);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(userRepo.save(any())).thenReturn(user);

        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setStatus(AccountStatus.SUSPENDED);
        req.setReason("TOS violation");

        adminUserService.updateStatus(userId, req, adminId);

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.SUSPENDED);
        verify(sessionService).revokeAllUserSessions(userId, adminId);
        verify(auditLogService).log(eq("USER_SUSPENDED"), eq(adminId), eq("USER"), eq(userId), eq("TOS violation"), isNull());
    }

    @Test
    void updateStatus_ban_revokesTokens() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User user = makeUser(userId, AccountStatus.ACTIVE);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(userRepo.save(any())).thenReturn(user);

        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setStatus(AccountStatus.BAN);

        adminUserService.updateStatus(userId, req, adminId);

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.BAN);
        verify(sessionService).revokeAllUserSessions(userId, adminId);
    }

    @Test
    void softDelete_setsDeletedStatus() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User user = makeUser(userId, AccountStatus.ACTIVE);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(userRepo.save(any())).thenReturn(user);

        adminUserService.softDelete(userId, adminId);

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.DELETED);
        verify(auditLogService).log(eq("USER_DELETED"), eq(adminId), eq("USER"), eq(userId), isNull(), isNull());
    }

    @Test
    void getById_notFound_throwsAppException() {
        UUID userId = UUID.randomUUID();
        when(userRepo.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserService.getById(userId))
            .isInstanceOf(AppException.class);
    }
}
```

- [ ] **Step 3: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "*.AdminUserServiceTest"
```
Expected: `FAILED — AdminUserServiceImpl not found`

- [ ] **Step 4: Create AdminUserService interface**

```java
package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AdminUserResponse;
import com.tinniestudio.api.modules.admin.dto.UpdateUserRequest;
import com.tinniestudio.api.modules.admin.dto.UpdateUserStatusRequest;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface AdminUserService {
    Page<AdminUserResponse> listUsers(String role, AccountStatus status, String search, Pageable pageable);
    AdminUserResponse getById(UUID userId);
    AdminUserResponse update(UUID userId, UpdateUserRequest req);
    void updateStatus(UUID userId, UpdateUserStatusRequest req, UUID adminId);
    void softDelete(UUID userId, UUID adminId);
}
```

- [ ] **Step 5: Create AdminUserServiceImpl**

```java
package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AdminUserResponse;
import com.tinniestudio.api.modules.admin.dto.UpdateUserRequest;
import com.tinniestudio.api.modules.admin.dto.UpdateUserStatusRequest;
import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.AppException;
import com.tinniestudio.api.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.EnumSet;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private final UserRepository userRepo;
    private final SessionService sessionService;
    private final AuditLogService auditLogService;

    private static final EnumSet<AccountStatus> TOKEN_REVOKE_STATUSES =
        EnumSet.of(AccountStatus.SUSPENDED, AccountStatus.BAN);

    @Override
    @Transactional(readOnly = true)
    public Page<AdminUserResponse> listUsers(String role, AccountStatus status, String search, Pageable pageable) {
        return userRepo.findByFilters(role, status, search, pageable).map(AdminUserResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserResponse getById(UUID userId) {
        return userRepo.findById(userId)
            .map(AdminUserResponse::from)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "User not found"));
    }

    @Override
    @Transactional
    public AdminUserResponse update(UUID userId, UpdateUserRequest req) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "User not found"));
        if (req.getEmail() != null) user.setEmail(req.getEmail());
        if (req.getEmailVerified() != null) user.setEmailVerified(req.getEmailVerified());
        return AdminUserResponse.from(userRepo.save(user));
    }

    @Override
    @Transactional
    public void updateStatus(UUID userId, UpdateUserStatusRequest req, UUID adminId) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "User not found"));
        user.setAccountStatus(req.getStatus());
        userRepo.save(user);
        if (TOKEN_REVOKE_STATUSES.contains(req.getStatus())) {
            sessionService.revokeAllUserSessions(userId, adminId);
        }
        auditLogService.log(
            "USER_" + req.getStatus().name(),
            adminId, "USER", userId,
            req.getReason(), null
        );
    }

    @Override
    @Transactional
    public void softDelete(UUID userId, UUID adminId) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "User not found"));
        user.setAccountStatus(AccountStatus.DELETED);
        userRepo.save(user);
        auditLogService.log("USER_DELETED", adminId, "USER", userId, null, null);
    }
}
```

- [ ] **Step 6: Run tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.AdminUserServiceTest"
```
Expected: `5 tests passed`

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/admin/service/AdminUserService*.java \
        api-service/src/main/java/com/tinniestudio/api/modules/admin/dto/ \
        api-service/src/test/java/com/tinniestudio/api/modules/admin/service/AdminUserServiceTest.java
git commit -m "feat(partner-admin): implement AdminUserService with status management and token revocation (TDD)"
```

---

## Task 5: PartnerApplicationService TDD

**Files:**
- Create: `modules/admin/service/PartnerApplicationService.java`
- Create: `modules/admin/service/PartnerApplicationServiceImpl.java`
- Create: `modules/admin/dto/PartnerApplicationResponse.java`
- Create: `modules/admin/dto/RejectApplicationRequest.java`
- Test: `src/test/java/.../admin/service/PartnerApplicationServiceTest.java`

- [ ] **Step 1: Create DTOs**

```java
// PartnerApplicationResponse.java
package com.tinniestudio.api.modules.admin.dto;

import com.tinniestudio.api.shared.entity.PartnerApplication;
import java.time.Instant;
import java.util.UUID;

public record PartnerApplicationResponse(
    UUID id,
    UUID userId,
    String companyName,
    String description,
    String websiteUrl,
    String status,
    String rejectionReason,
    UUID reviewedBy,
    Instant reviewedAt,
    Instant createdAt
) {
    public static PartnerApplicationResponse from(PartnerApplication a) {
        return new PartnerApplicationResponse(
            a.getId(), a.getUserId(), a.getCompanyName(), a.getDescription(),
            a.getWebsiteUrl(), a.getStatus().name(), a.getRejectionReason(),
            a.getReviewedBy(), a.getReviewedAt(), a.getCreatedAt()
        );
    }
}
```

```java
// RejectApplicationRequest.java
package com.tinniestudio.api.modules.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class RejectApplicationRequest {
    @NotBlank
    private String reason;
}
```

- [ ] **Step 2: Create PartnerApplicationRequest in partner.dto**

```java
// modules/partner/dto/PartnerApplicationRequest.java
package com.tinniestudio.api.modules.partner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class PartnerApplicationRequest {
    @NotBlank
    @Size(max = 255)
    private String companyName;

    @Size(max = 2000)
    private String description;

    @Size(max = 500)
    private String websiteUrl;
}
```

- [ ] **Step 3: Write failing tests**

```java
package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.dto.RejectApplicationRequest;
import com.tinniestudio.api.modules.partner.dto.PartnerApplicationRequest;
import com.tinniestudio.api.modules.partner.repository.PartnerApplicationRepository;
import com.tinniestudio.api.modules.partner.repository.PartnerProfileRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import com.tinniestudio.api.shared.entity.PartnerApplication;
import com.tinniestudio.api.shared.entity.Role;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartnerApplicationServiceTest {

    @Mock PartnerApplicationRepository applicationRepo;
    @Mock PartnerProfileRepository profileRepo;
    @Mock UserRepository userRepo;
    @Mock com.tinniestudio.api.modules.role.RoleRepository roleRepo;
    @Mock AuditLogService auditLogService;
    @InjectMocks PartnerApplicationServiceImpl applicationService;

    private User makeUser(UUID id) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setEmail("user@test.com");
        u.setRoles(new java.util.HashSet<>());
        return u;
    }

    @Test
    void apply_createsPendingApplication() {
        UUID userId = UUID.randomUUID();
        when(applicationRepo.existsByUserIdAndStatus(userId, PartnerApplicationStatus.PENDING)).thenReturn(false);
        when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme Corp");
        req.setDescription("We make content");

        PartnerApplicationResponse result = applicationService.apply(userId, req);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.companyName()).isEqualTo("Acme Corp");
    }

    @Test
    void apply_alreadyPending_throwsConflict() {
        UUID userId = UUID.randomUUID();
        when(applicationRepo.existsByUserIdAndStatus(userId, PartnerApplicationStatus.PENDING)).thenReturn(true);

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme");

        assertThatThrownBy(() -> applicationService.apply(userId, req))
            .isInstanceOf(AppException.class);
    }

    @Test
    void approve_assignsPartnerRoleAndCreatesProfile() {
        UUID appId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User user = makeUser(userId);

        PartnerApplication app = new PartnerApplication();
        ReflectionTestUtils.setField(app, "id", appId);
        app.setUserId(userId);
        app.setCompanyName("Acme");
        app.setStatus(PartnerApplicationStatus.PENDING);

        Role partnerRole = new Role();
        partnerRole.setName(com.tinniestudio.api.shared.entity.DomainEnums.RoleName.PARTNER);

        when(applicationRepo.findById(appId)).thenReturn(Optional.of(app));
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepo.findByName(com.tinniestudio.api.shared.entity.DomainEnums.RoleName.PARTNER))
            .thenReturn(Optional.of(partnerRole));
        when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepo.save(any())).thenReturn(user);
        when(profileRepo.existsByUserId(userId)).thenReturn(false);
        when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PartnerApplicationResponse result = applicationService.approve(appId, adminId);

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(profileRepo).save(any());
        verify(auditLogService).log(eq("PARTNER_APPLICATION_APPROVED"), eq(adminId), eq("PARTNER_APPLICATION"), eq(appId), isNull(), isNull());
    }

    @Test
    void reject_setsRejectedStatusAndReason() {
        UUID appId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        PartnerApplication app = new PartnerApplication();
        ReflectionTestUtils.setField(app, "id", appId);
        app.setUserId(UUID.randomUUID());
        app.setStatus(PartnerApplicationStatus.PENDING);

        when(applicationRepo.findById(appId)).thenReturn(Optional.of(app));
        when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        RejectApplicationRequest req = new RejectApplicationRequest();
        req.setReason("Incomplete submission");

        PartnerApplicationResponse result = applicationService.reject(appId, req, adminId);

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.rejectionReason()).isEqualTo("Incomplete submission");
        verify(auditLogService).log(contains("REJECTED"), eq(adminId), eq("PARTNER_APPLICATION"), eq(appId), contains("Incomplete"), isNull());
    }
}
```

- [ ] **Step 4: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "*.PartnerApplicationServiceTest"
```
Expected: `FAILED — PartnerApplicationServiceImpl not found`

- [ ] **Step 5: Create PartnerApplicationService interface**

```java
package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.dto.RejectApplicationRequest;
import com.tinniestudio.api.modules.partner.dto.PartnerApplicationRequest;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface PartnerApplicationService {
    PartnerApplicationResponse apply(UUID userId, PartnerApplicationRequest req);
    Page<PartnerApplicationResponse> list(PartnerApplicationStatus status, Pageable pageable);
    PartnerApplicationResponse approve(UUID applicationId, UUID adminId);
    PartnerApplicationResponse reject(UUID applicationId, RejectApplicationRequest req, UUID adminId);
}
```

- [ ] **Step 6: Create PartnerApplicationServiceImpl**

```java
package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.dto.RejectApplicationRequest;
import com.tinniestudio.api.modules.partner.dto.PartnerApplicationRequest;
import com.tinniestudio.api.modules.partner.repository.PartnerApplicationRepository;
import com.tinniestudio.api.modules.partner.repository.PartnerProfileRepository;
import com.tinniestudio.api.modules.role.RoleRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.exception.AppException;
import com.tinniestudio.api.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PartnerApplicationServiceImpl implements PartnerApplicationService {

    private final PartnerApplicationRepository applicationRepo;
    private final PartnerProfileRepository profileRepo;
    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final AuditLogService auditLogService;

    @Override
    @Transactional
    public PartnerApplicationResponse apply(UUID userId, PartnerApplicationRequest req) {
        if (applicationRepo.existsByUserIdAndStatus(userId, PartnerApplicationStatus.PENDING)) {
            throw new AppException(ErrorCode.CONFLICT, "A pending partner application already exists");
        }
        PartnerApplication app = new PartnerApplication();
        app.setUserId(userId);
        app.setCompanyName(req.getCompanyName());
        app.setDescription(req.getDescription());
        app.setWebsiteUrl(req.getWebsiteUrl());
        return PartnerApplicationResponse.from(applicationRepo.save(app));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PartnerApplicationResponse> list(PartnerApplicationStatus status, Pageable pageable) {
        return (status != null
            ? applicationRepo.findByStatusOrderByCreatedAtDesc(status, pageable)
            : applicationRepo.findAllByOrderByCreatedAtDesc(pageable))
            .map(PartnerApplicationResponse::from);
    }

    @Override
    @Transactional
    public PartnerApplicationResponse approve(UUID applicationId, UUID adminId) {
        PartnerApplication app = applicationRepo.findById(applicationId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Application not found"));

        app.setStatus(PartnerApplicationStatus.APPROVED);
        app.setReviewedBy(adminId);
        app.setReviewedAt(Instant.now());
        applicationRepo.save(app);

        User user = userRepo.findById(app.getUserId())
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "User not found"));
        Role partnerRole = roleRepo.findByName(RoleName.PARTNER)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "PARTNER role not found"));
        user.addRole(partnerRole);
        userRepo.save(user);

        if (!profileRepo.existsByUserId(app.getUserId())) {
            PartnerProfile profile = new PartnerProfile();
            profile.setUserId(app.getUserId());
            profile.setCompanyName(app.getCompanyName());
            profile.setWebsiteUrl(app.getWebsiteUrl());
            profileRepo.save(profile);
        }

        auditLogService.log("PARTNER_APPLICATION_APPROVED", adminId, "PARTNER_APPLICATION", applicationId, null, null);
        return PartnerApplicationResponse.from(app);
    }

    @Override
    @Transactional
    public PartnerApplicationResponse reject(UUID applicationId, RejectApplicationRequest req, UUID adminId) {
        PartnerApplication app = applicationRepo.findById(applicationId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Application not found"));
        app.setStatus(PartnerApplicationStatus.REJECTED);
        app.setRejectionReason(req.getReason());
        app.setReviewedBy(adminId);
        app.setReviewedAt(Instant.now());
        applicationRepo.save(app);
        auditLogService.log("PARTNER_APPLICATION_REJECTED", adminId, "PARTNER_APPLICATION", applicationId, req.getReason(), null);
        return PartnerApplicationResponse.from(app);
    }
}
```

- [ ] **Step 7: Run tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.PartnerApplicationServiceTest"
```
Expected: `4 tests passed`

- [ ] **Step 8: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/admin/service/PartnerApplicationService*.java \
        api-service/src/main/java/com/tinniestudio/api/modules/admin/dto/PartnerApplicationResponse.java \
        api-service/src/main/java/com/tinniestudio/api/modules/admin/dto/RejectApplicationRequest.java \
        api-service/src/main/java/com/tinniestudio/api/modules/partner/dto/PartnerApplicationRequest.java \
        api-service/src/test/java/com/tinniestudio/api/modules/admin/service/PartnerApplicationServiceTest.java
git commit -m "feat(partner-admin): implement PartnerApplicationService with apply/approve/reject flow (TDD)"
```

---

## Task 6: AdminDashboardService TDD

**Files:**
- Create: `modules/admin/service/AdminDashboardService.java`
- Create: `modules/admin/service/AdminDashboardServiceImpl.java`
- Create: `modules/admin/dto/AdminDashboardResponse.java`
- Modify: `shared/queue/RabbitConfig.java` — add `RabbitAdmin` bean
- Test: `src/test/java/.../admin/service/AdminDashboardServiceTest.java`

- [ ] **Step 1: Add RabbitAdmin bean to RabbitConfig**

Add to `RabbitConfig.java` (after the `MessageConverter` bean):

```java
import org.springframework.amqp.rabbit.core.RabbitAdmin;

@Bean
public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
    return new RabbitAdmin(connectionFactory);
}
```

- [ ] **Step 2: Create AdminDashboardResponse**

```java
package com.tinniestudio.api.modules.admin.dto;

import java.math.BigDecimal;
import java.util.Map;

public record AdminDashboardResponse(
    long totalUsers,
    long newUsersThisWeek,
    long activeSubscriptions,
    BigDecimal revenueThisMonth,
    long contentInReview,
    long processingFailures,
    long storageBytesUsed,
    Map<String, Integer> queueDepths
) {}
```

- [ ] **Step 3: Write failing tests**

```java
package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AdminDashboardResponse;
import com.tinniestudio.api.modules.billing.repository.PaymentRepository;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

    @Mock UserRepository userRepo;
    @Mock UserSubscriptionRepository subscriptionRepo;
    @Mock PaymentRepository paymentRepo;
    @Mock ContentRepository contentRepo;
    @Mock VideoAssetRepository videoAssetRepo;
    @Mock UploadSessionRepository uploadSessionRepo;
    @Mock RabbitAdmin rabbitAdmin;
    @InjectMocks AdminDashboardServiceImpl dashboardService;

    @Test
    void getDashboard_returnsAggregatedStats() {
        when(userRepo.count()).thenReturn(500L);
        when(userRepo.countByCreatedAtAfter(any(Instant.class))).thenReturn(12L);
        when(subscriptionRepo.countByStatus(any())).thenReturn(200L);
        when(paymentRepo.sumAmountAfter(any(Instant.class))).thenReturn(new BigDecimal("5000.00"));
        when(contentRepo.countByStatus(any())).thenReturn(3L);
        when(videoAssetRepo.countByProcessingStatus(any())).thenReturn(2L);
        when(uploadSessionRepo.sumFileSizeBytes()).thenReturn(1_000_000_000L);
        when(rabbitAdmin.getQueueProperties(any())).thenReturn(null);

        AdminDashboardResponse result = dashboardService.getDashboard();

        assertThat(result.totalUsers()).isEqualTo(500L);
        assertThat(result.newUsersThisWeek()).isEqualTo(12L);
        assertThat(result.activeSubscriptions()).isEqualTo(200L);
        assertThat(result.storageBytesUsed()).isEqualTo(1_000_000_000L);
    }
}
```

- [ ] **Step 4: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "*.AdminDashboardServiceTest"
```
Expected: `FAILED — AdminDashboardServiceImpl not found`

- [ ] **Step 5: Add sumFileSizeBytes to UploadSessionRepository**

Add to existing `UploadSessionRepository.java`:

```java
@Query("SELECT COALESCE(SUM(u.fileSizeBytes), 0) FROM UploadSession u WHERE u.uploadStatus = 'COMPLETED'")
Long sumFileSizeBytes();
```

Also add to `UploadSession` entity:
```java
@Column(nullable = false)
private Long fileSizeBytes = 0L;
```

Add to `PaymentRepository.java`:
```java
@Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.createdAt >= :after AND p.status = 'SUCCEEDED'")
BigDecimal sumAmountAfter(@Param("after") Instant after);
```

Add to `VideoAssetRepository.java`:
```java
long countByProcessingStatus(com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus status);
```

- [ ] **Step 6: Create AdminDashboardService interface**

```java
package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AdminDashboardResponse;

public interface AdminDashboardService {
    AdminDashboardResponse getDashboard();
}
```

- [ ] **Step 7: Create AdminDashboardServiceImpl**

```java
package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AdminDashboardResponse;
import com.tinniestudio.api.modules.billing.repository.PaymentRepository;
import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.queue.RabbitConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Service
@RequiredArgsConstructor
public class AdminDashboardServiceImpl implements AdminDashboardService {

    private final UserRepository userRepo;
    private final UserSubscriptionRepository subscriptionRepo;
    private final PaymentRepository paymentRepo;
    private final ContentRepository contentRepo;
    private final VideoAssetRepository videoAssetRepo;
    private final UploadSessionRepository uploadSessionRepo;
    private final RabbitAdmin rabbitAdmin;

    private static final List<String> MONITORED_QUEUES = List.of(
        RabbitConfig.QUEUE_VIDEO_PROCESS,
        RabbitConfig.QUEUE_VIDEO_FAILED,
        RabbitConfig.QUEUE_NOTIFICATIONS,
        RabbitConfig.QUEUE_ANALYTICS_INGEST
    );

    @Override
    @Transactional(readOnly = true)
    public AdminDashboardResponse getDashboard() {
        Instant oneWeekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        Instant startOfMonth = Instant.now().truncatedTo(ChronoUnit.DAYS)
            .minus(Instant.now().atZone(java.time.ZoneOffset.UTC).getDayOfMonth() - 1L, ChronoUnit.DAYS);

        Map<String, Integer> queueDepths = new HashMap<>();
        for (String queue : MONITORED_QUEUES) {
            Properties props = rabbitAdmin.getQueueProperties(queue);
            int count = props != null ? (int) props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT) : 0;
            queueDepths.put(queue, count);
        }

        return new AdminDashboardResponse(
            userRepo.count(),
            userRepo.countByCreatedAtAfter(oneWeekAgo),
            subscriptionRepo.countByStatus(SubscriptionStatus.ACTIVE),
            paymentRepo.sumAmountAfter(startOfMonth),
            contentRepo.countByStatus(ContentStatus.REVIEW),
            videoAssetRepo.countByProcessingStatus(ProcessingStatus.FAILED),
            uploadSessionRepo.sumFileSizeBytes(),
            queueDepths
        );
    }
}
```

- [ ] **Step 8: Run tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.AdminDashboardServiceTest"
```
Expected: `1 test passed`

- [ ] **Step 9: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/queue/RabbitConfig.java \
        api-service/src/main/java/com/tinniestudio/api/modules/admin/service/AdminDashboardService*.java \
        api-service/src/main/java/com/tinniestudio/api/modules/admin/dto/AdminDashboardResponse.java \
        api-service/src/test/java/com/tinniestudio/api/modules/admin/service/AdminDashboardServiceTest.java
git commit -m "feat(partner-admin): implement AdminDashboardService with RabbitAdmin queue depth (TDD)"
```

---

## Task 7: PartnerService TDD

**Files:**
- Create: `modules/partner/service/PartnerService.java`
- Create: `modules/partner/service/PartnerServiceImpl.java`
- Create: `modules/partner/dto/PartnerProfileResponse.java`
- Create: `modules/partner/dto/UpdatePartnerProfileRequest.java`
- Create: `modules/partner/dto/PartnerDashboardResponse.java`
- Test: `src/test/java/.../partner/service/PartnerServiceTest.java`

- [ ] **Step 1: Create partner DTOs**

```java
// PartnerProfileResponse.java
package com.tinniestudio.api.modules.partner.dto;

import com.tinniestudio.api.shared.entity.PartnerProfile;
import java.math.BigDecimal;
import java.util.UUID;

public record PartnerProfileResponse(
    UUID id,
    UUID userId,
    String companyName,
    String websiteUrl,
    String bio,
    String logoUrl,
    BigDecimal revenueSharePercentage,
    Boolean isVerified
) {
    public static PartnerProfileResponse from(PartnerProfile p) {
        return new PartnerProfileResponse(
            p.getId(), p.getUserId(), p.getCompanyName(), p.getWebsiteUrl(),
            p.getBio(), p.getLogoUrl(), p.getRevenueSharePercentage(), p.getIsVerified()
        );
    }
}
```

```java
// UpdatePartnerProfileRequest.java
package com.tinniestudio.api.modules.partner.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class UpdatePartnerProfileRequest {
    @Size(max = 255)
    private String companyName;
    @Size(max = 500)
    private String websiteUrl;
    @Size(max = 2000)
    private String bio;
}
```

```java
// PartnerDashboardResponse.java
package com.tinniestudio.api.modules.partner.dto;

import com.tinniestudio.api.modules.admin.dto.AuditLogResponse;
import java.util.List;

public record PartnerDashboardResponse(
    long publishedContentCount,
    long contentInReview,
    long activeUploads,
    long totalViewCount,
    List<AuditLogResponse> recentActivity
) {}
```

- [ ] **Step 2: Write failing tests**

```java
package com.tinniestudio.api.modules.partner.service;

import com.tinniestudio.api.modules.admin.service.AuditLogService;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.partner.dto.PartnerDashboardResponse;
import com.tinniestudio.api.modules.partner.dto.PartnerProfileResponse;
import com.tinniestudio.api.modules.partner.dto.UpdatePartnerProfileRequest;
import com.tinniestudio.api.modules.partner.repository.PartnerProfileRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.PartnerProfile;
import com.tinniestudio.api.shared.exception.AppException;
import com.tinniestudio.api.shared.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartnerServiceTest {

    @Mock PartnerProfileRepository profileRepo;
    @Mock ContentRepository contentRepo;
    @Mock VideoAssetRepository videoAssetRepo;
    @Mock AuditLogService auditLogService;
    @Mock StorageService storageService;
    @InjectMocks PartnerServiceImpl partnerService;

    private PartnerProfile makeProfile(UUID userId) {
        PartnerProfile p = new PartnerProfile();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setUserId(userId);
        p.setCompanyName("Acme");
        p.setIsVerified(true);
        return p;
    }

    @Test
    void getProfile_returnsPartnerProfile() {
        UUID userId = UUID.randomUUID();
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.of(makeProfile(userId)));

        PartnerProfileResponse result = partnerService.getProfile(userId);

        assertThat(result.companyName()).isEqualTo("Acme");
    }

    @Test
    void getProfile_notFound_throwsAppException() {
        UUID userId = UUID.randomUUID();
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> partnerService.getProfile(userId))
            .isInstanceOf(AppException.class);
    }

    @Test
    void updateProfile_appliesNonNullFields() {
        UUID userId = UUID.randomUUID();
        PartnerProfile profile = makeProfile(userId);
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdatePartnerProfileRequest req = new UpdatePartnerProfileRequest();
        req.setBio("We make great content");

        PartnerProfileResponse result = partnerService.updateProfile(userId, req);

        assertThat(profile.getBio()).isEqualTo("We make great content");
        assertThat(profile.getCompanyName()).isEqualTo("Acme"); // unchanged
    }

    @Test
    void uploadLogo_storesFileAndUpdatesProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        PartnerProfile profile = makeProfile(userId);
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(storageService.uploadFile(contains("partner-logos"), any(), any()))
            .thenReturn("https://cdn.test/logo.jpg");
        when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "logo.jpg", "image/jpeg", new byte[]{1, 2, 3});
        String url = partnerService.uploadLogo(userId, file);

        assertThat(url).isEqualTo("https://cdn.test/logo.jpg");
        assertThat(profile.getLogoUrl()).isEqualTo("https://cdn.test/logo.jpg");
    }
}
```

- [ ] **Step 3: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "*.PartnerServiceTest"
```
Expected: `FAILED — PartnerServiceImpl not found`

- [ ] **Step 4: Create PartnerService interface**

```java
package com.tinniestudio.api.modules.partner.service;

import com.tinniestudio.api.modules.partner.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.UUID;

public interface PartnerService {
    PartnerProfileResponse getProfile(UUID userId);
    PartnerProfileResponse updateProfile(UUID userId, UpdatePartnerProfileRequest req);
    String uploadLogo(UUID userId, MultipartFile file) throws IOException;
    PartnerDashboardResponse getDashboard(UUID userId);
    Page<Object> getUploads(UUID userId, String status, Pageable pageable);
    Page<Object> getContents(UUID userId, Pageable pageable);
}
```

- [ ] **Step 5: Create PartnerServiceImpl**

```java
package com.tinniestudio.api.modules.partner.service;

import com.tinniestudio.api.modules.admin.dto.AuditLogResponse;
import com.tinniestudio.api.modules.admin.repository.AuditLogRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.partner.dto.*;
import com.tinniestudio.api.modules.partner.repository.PartnerProfileRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.entity.PartnerProfile;
import com.tinniestudio.api.shared.exception.AppException;
import com.tinniestudio.api.shared.exception.ErrorCode;
import com.tinniestudio.api.shared.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PartnerServiceImpl implements PartnerService {

    private final PartnerProfileRepository profileRepo;
    private final ContentRepository contentRepo;
    private final VideoAssetRepository videoAssetRepo;
    private final AuditLogRepository auditLogRepo;
    private final StorageService storageService;

    private PartnerProfile requireProfile(UUID userId) {
        return profileRepo.findByUserId(userId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Partner profile not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public PartnerProfileResponse getProfile(UUID userId) {
        return PartnerProfileResponse.from(requireProfile(userId));
    }

    @Override
    @Transactional
    public PartnerProfileResponse updateProfile(UUID userId, UpdatePartnerProfileRequest req) {
        PartnerProfile profile = requireProfile(userId);
        if (req.getCompanyName() != null) profile.setCompanyName(req.getCompanyName());
        if (req.getWebsiteUrl() != null) profile.setWebsiteUrl(req.getWebsiteUrl());
        if (req.getBio() != null) profile.setBio(req.getBio());
        return PartnerProfileResponse.from(profileRepo.save(profile));
    }

    @Override
    @Transactional
    public String uploadLogo(UUID userId, MultipartFile file) throws IOException {
        PartnerProfile profile = requireProfile(userId);
        String ext = StringUtils.getFilenameExtension(file.getOriginalFilename());
        String key = "partner-logos/" + profile.getId() + "/logo." + ext;
        String url = storageService.uploadFile(key, file.getBytes(), file.getContentType());
        profile.setLogoUrl(url);
        profileRepo.save(profile);
        return url;
    }

    @Override
    @Transactional(readOnly = true)
    public PartnerDashboardResponse getDashboard(UUID userId) {
        requireProfile(userId);
        long published = contentRepo.countByCreatedByAndStatus(userId, ContentStatus.PUBLISHED);
        long inReview  = contentRepo.countByCreatedByAndStatus(userId, ContentStatus.REVIEW);
        long processing = videoAssetRepo.countByContentCreatedByAndProcessingStatus(userId, ProcessingStatus.PROCESSING);
        long totalViews = contentRepo.sumViewCountByCreatedBy(userId);

        List<AuditLogResponse> activity = auditLogRepo
            .findByActorIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 10))
            .map(AuditLogResponse::from)
            .getContent();

        return new PartnerDashboardResponse(published, inReview, processing, totalViews, activity);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Object> getUploads(UUID userId, String status, Pageable pageable) {
        // Delegated to UploadSessionRepository — returns upload sessions scoped to partner's content
        // Returns raw page; controller maps to UploadSessionResponse (already exists in upload module)
        throw new UnsupportedOperationException("Implemented via UploadSessionRepository in controller");
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Object> getContents(UUID userId, Pageable pageable) {
        throw new UnsupportedOperationException("Implemented via ContentRepository in controller");
    }
}
```

> **Note:** `getUploads` and `getContents` are thin wrappers handled directly in the controller using existing repositories. See Task 11.

- [ ] **Step 6: Add missing repository methods**

Add to `ContentRepository.java`:
```java
long countByCreatedByAndStatus(UUID createdBy, ContentStatus status);

@Query("SELECT COALESCE(SUM(c.viewCount), 0) FROM Content c WHERE c.createdBy = :createdBy")
Long sumViewCountByCreatedBy(@Param("createdBy") UUID createdBy);
```

Add to `VideoAssetRepository.java`:
```java
@Query("SELECT COUNT(va) FROM VideoAsset va WHERE va.content.createdBy = :createdBy AND va.processingStatus = :status")
long countByContentCreatedByAndProcessingStatus(@Param("createdBy") UUID createdBy, @Param("status") ProcessingStatus status);
```

- [ ] **Step 7: Run tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.PartnerServiceTest"
```
Expected: `4 tests passed`

- [ ] **Step 8: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/partner/ \
        api-service/src/test/java/com/tinniestudio/api/modules/partner/service/PartnerServiceTest.java
git commit -m "feat(partner-admin): implement PartnerService with profile/dashboard/logo upload (TDD)"
```

---

## Task 8: AdminUserController TDD

**Files:**
- Create: `modules/admin/controller/AdminUserController.java`
- Test: `src/test/java/.../admin/controller/AdminUserControllerTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.tinniestudio.api.modules.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.admin.dto.AdminUserResponse;
import com.tinniestudio.api.modules.admin.dto.UpdateUserStatusRequest;
import com.tinniestudio.api.modules.admin.service.AdminUserService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminUserController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminUserControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean AdminUserService adminUserService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    private static final String CONTEXT = "/api/v1";
    private static final String ADMIN_ID = "550e8400-e29b-41d4-a716-446655440000";

    private MockHttpServletRequestBuilder getCtx(String path) {
        return get(CONTEXT + path).contextPath(CONTEXT);
    }

    private AdminUserResponse sampleUser(UUID id) {
        return new AdminUserResponse(id, "test@example.com", "ACTIVE", Set.of("USER"), true, Instant.now());
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void listUsers_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(adminUserService.listUsers(isNull(), isNull(), isNull(), any()))
            .thenReturn(new PageImpl<>(List.of(sampleUser(id))));

        mockMvc.perform(getCtx("/admin/users"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].email").value("test@example.com"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void getUserById_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(adminUserService.getById(id)).thenReturn(sampleUser(id));

        mockMvc.perform(getCtx("/admin/users/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void updateUserStatus_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setStatus(AccountStatus.SUSPENDED);
        req.setReason("TOS violation");

        doNothing().when(adminUserService).updateStatus(any(), any(), any());

        mockMvc.perform(patch(CONTEXT + "/admin/users/" + userId + "/status").contextPath(CONTEXT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void deleteUser_returns204() throws Exception {
        UUID userId = UUID.randomUUID();
        doNothing().when(adminUserService).softDelete(any(), any());

        mockMvc.perform(delete(CONTEXT + "/admin/users/" + userId).contextPath(CONTEXT))
            .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "*.AdminUserControllerTest"
```
Expected: `FAILED — AdminUserController not found`

- [ ] **Step 3: Create AdminUserController**

```java
package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.modules.admin.dto.AdminUserResponse;
import com.tinniestudio.api.modules.admin.dto.UpdateUserRequest;
import com.tinniestudio.api.modules.admin.dto.UpdateUserStatusRequest;
import com.tinniestudio.api.modules.admin.service.AdminUserService;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Admin - Users", description = "Admin user management")
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "List all users with optional filters")
    @GetMapping
    public ResponseEntity<Page<AdminUserResponse>> listUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminUserService.listUsers(role, status, search, pageable));
    }

    @Operation(summary = "Get user by ID")
    @GetMapping("/{id}")
    public ResponseEntity<AdminUserResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(adminUserService.getById(id));
    }

    @Operation(summary = "Update user account fields")
    @PatchMapping("/{id}")
    public ResponseEntity<AdminUserResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest req) {
        return ResponseEntity.ok(adminUserService.update(id, req));
    }

    @Operation(summary = "Update user account status (ACTIVE/SUSPENDED/BAN)")
    @PatchMapping("/{id}/status")
    public ResponseEntity<Void> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserStatusRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        adminUserService.updateStatus(id, req, UUID.fromString(principal.getUsername()));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Soft-delete a user account")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        adminUserService.softDelete(id, UUID.fromString(principal.getUsername()));
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.AdminUserControllerTest"
```
Expected: `4 tests passed`

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/admin/controller/AdminUserController.java \
        api-service/src/test/java/com/tinniestudio/api/modules/admin/controller/AdminUserControllerTest.java
git commit -m "feat(partner-admin): implement AdminUserController with list/get/update/status/delete (TDD)"
```

---

## Task 9: AdminDashboardController + AdminContentListController TDD

**Files:**
- Create: `modules/admin/controller/AdminDashboardController.java`
- Modify: `modules/content/controller/AdminContentController.java` — add `GET /admin/contents`
- Create: `modules/admin/controller/AdminUploadController.java`
- Tests for all three

- [ ] **Step 1: Write failing tests**

```java
// AdminDashboardControllerTest.java
package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.modules.admin.dto.AdminDashboardResponse;
import com.tinniestudio.api.modules.admin.service.AdminDashboardService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminDashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminDashboardControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean AdminDashboardService dashboardService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    private static final String CTX = "/api/v1";

    @Test
    @WithMockUser(roles = "ADMIN")
    void getDashboard_returns200WithStats() throws Exception {
        when(dashboardService.getDashboard()).thenReturn(
            new AdminDashboardResponse(500L, 12L, 200L, new BigDecimal("5000.00"), 3L, 2L, 1_000_000L, Map.of())
        );

        mockMvc.perform(get(CTX + "/admin/dashboard").contextPath(CTX))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalUsers").value(500))
            .andExpect(jsonPath("$.data.activeSubscriptions").value(200));
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "*.AdminDashboardControllerTest"
```
Expected: `FAILED — AdminDashboardController not found`

- [ ] **Step 3: Create AdminDashboardController**

```java
package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.modules.admin.dto.AdminDashboardResponse;
import com.tinniestudio.api.modules.admin.service.AdminDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin - Dashboard", description = "Admin platform overview")
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    @Operation(summary = "Get admin platform dashboard stats")
    @GetMapping
    public ResponseEntity<AdminDashboardResponse> getDashboard() {
        return ResponseEntity.ok(dashboardService.getDashboard());
    }
}
```

- [ ] **Step 4: Add GET /admin/contents to AdminContentController**

In the existing `AdminContentController.java`, add:

```java
@Operation(summary = "List all content across all partners and statuses")
@GetMapping
public ResponseEntity<Page<ContentResponse>> listAll(
        @RequestParam(required = false) ContentStatus status,
        @RequestParam(required = false) ContentType type,
        @RequestParam(required = false) String search,
        @PageableDefault(size = 20) Pageable pageable) {
    return ResponseEntity.ok(contentService.listAll(status, type, search, pageable));
}
```

Add `listAll(status, type, search, pageable)` to `ContentService` interface and `ContentServiceImpl`:

```java
// ContentService.java — add:
Page<ContentResponse> listAll(ContentStatus status, ContentType type, String search, Pageable pageable);

// ContentServiceImpl.java — add:
@Override
@Transactional(readOnly = true)
public Page<ContentResponse> listAll(ContentStatus status, ContentType type, String search, Pageable pageable) {
    return contentRepo.findByAdminFilters(status, type, search, pageable).map(ContentResponse::from);
}
```

Add to `ContentRepository.java`:
```java
@Query("""
    SELECT c FROM Content c
    WHERE (:status IS NULL OR c.status = :status)
    AND (:type IS NULL OR c.type = :type)
    AND (:search IS NULL OR LOWER(c.title) LIKE LOWER(CONCAT('%', :search, '%')))
    ORDER BY c.createdAt DESC
    """)
Page<Content> findByAdminFilters(
    @Param("status") ContentStatus status,
    @Param("type") ContentType type,
    @Param("search") String search,
    Pageable pageable
);
```

- [ ] **Step 5: Create AdminUploadController**

```java
package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.modules.upload.dto.UploadSessionResponse;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin - Uploads", description = "Admin upload and processing management")
@RestController
@RequestMapping("/admin/uploads")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUploadController {

    private final UploadSessionRepository uploadSessionRepo;

    @Operation(summary = "List uploads currently processing or failed")
    @GetMapping("/processing")
    public ResponseEntity<Page<UploadSessionResponse>> getProcessing(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(
            uploadSessionRepo.findByVideoAssetProcessingStatusIn(
                java.util.List.of(ProcessingStatus.PROCESSING, ProcessingStatus.FAILED),
                pageable
            ).map(UploadSessionResponse::from)
        );
    }
}
```

Add to `UploadSessionRepository.java`:
```java
@Query("SELECT u FROM UploadSession u WHERE u.videoAsset.processingStatus IN :statuses ORDER BY u.createdAt DESC")
Page<UploadSession> findByVideoAssetProcessingStatusIn(@Param("statuses") List<ProcessingStatus> statuses, Pageable pageable);
```

- [ ] **Step 6: Run all tests**

```bash
./gradlew :api-service:test --tests "*.AdminDashboardControllerTest" --tests "*.AdminUploadController*"
```
Expected: all pass

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/admin/controller/ \
        api-service/src/main/java/com/tinniestudio/api/modules/content/controller/AdminContentController.java \
        api-service/src/test/java/com/tinniestudio/api/modules/admin/controller/
git commit -m "feat(partner-admin): implement AdminDashboardController, admin content list, AdminUploadController (TDD)"
```

---

## Task 10: AdminPartnerApplicationController + AuditLogController TDD

**Files:**
- Create: `modules/admin/controller/AdminPartnerApplicationController.java`
- Create: `modules/admin/controller/AuditLogController.java`
- Tests for both

- [ ] **Step 1: Write failing tests**

```java
// AdminPartnerApplicationControllerTest.java
package com.tinniestudio.api.modules.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.dto.RejectApplicationRequest;
import com.tinniestudio.api.modules.admin.service.PartnerApplicationService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminPartnerApplicationController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminPartnerApplicationControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean PartnerApplicationService applicationService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    private static final String CTX = "/api/v1";
    private static final String ADMIN_ID = "550e8400-e29b-41d4-a716-446655440000";

    private PartnerApplicationResponse sample() {
        return new PartnerApplicationResponse(
            UUID.randomUUID(), UUID.randomUUID(), "Acme", "desc", null,
            "PENDING", null, null, null, Instant.now()
        );
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void listApplications_returns200() throws Exception {
        when(applicationService.list(isNull(), any())).thenReturn(new PageImpl<>(List.of(sample())));

        mockMvc.perform(get(CTX + "/admin/partner-applications").contextPath(CTX))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].companyName").value("Acme"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void approveApplication_returns200() throws Exception {
        PartnerApplicationResponse approved = new PartnerApplicationResponse(
            UUID.randomUUID(), UUID.randomUUID(), "Acme", null, null,
            "APPROVED", null, UUID.fromString(ADMIN_ID), Instant.now(), Instant.now()
        );
        when(applicationService.approve(any(), any())).thenReturn(approved);

        mockMvc.perform(patch(CTX + "/admin/partner-applications/" + UUID.randomUUID() + "/approve").contextPath(CTX))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID, roles = "ADMIN")
    void rejectApplication_returns200() throws Exception {
        RejectApplicationRequest req = new RejectApplicationRequest();
        req.setReason("Incomplete");
        PartnerApplicationResponse rejected = new PartnerApplicationResponse(
            UUID.randomUUID(), UUID.randomUUID(), "Acme", null, null,
            "REJECTED", "Incomplete", null, Instant.now(), Instant.now()
        );
        when(applicationService.reject(any(), any(), any())).thenReturn(rejected);

        mockMvc.perform(patch(CTX + "/admin/partner-applications/" + UUID.randomUUID() + "/reject").contextPath(CTX)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "*.AdminPartnerApplicationControllerTest"
```
Expected: `FAILED — controller not found`

- [ ] **Step 3: Create AdminPartnerApplicationController**

```java
package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.dto.RejectApplicationRequest;
import com.tinniestudio.api.modules.admin.service.PartnerApplicationService;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Admin - Partner Applications", description = "Manage partner applications")
@RestController
@RequestMapping("/admin/partner-applications")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminPartnerApplicationController {

    private final PartnerApplicationService applicationService;

    @Operation(summary = "List partner applications")
    @GetMapping
    public ResponseEntity<Page<PartnerApplicationResponse>> list(
            @RequestParam(required = false) PartnerApplicationStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(applicationService.list(status, pageable));
    }

    @Operation(summary = "Approve a partner application")
    @PatchMapping("/{id}/approve")
    public ResponseEntity<PartnerApplicationResponse> approve(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(applicationService.approve(id, UUID.fromString(principal.getUsername())));
    }

    @Operation(summary = "Reject a partner application")
    @PatchMapping("/{id}/reject")
    public ResponseEntity<PartnerApplicationResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectApplicationRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(applicationService.reject(id, req, UUID.fromString(principal.getUsername())));
    }
}
```

- [ ] **Step 4: Create AuditLogController**

```java
package com.tinniestudio.api.modules.admin.controller;

import com.tinniestudio.api.modules.admin.dto.AuditLogResponse;
import com.tinniestudio.api.modules.admin.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Admin - Audit Logs", description = "Query admin audit trail")
@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @Operation(summary = "List all audit log entries")
    @GetMapping
    public ResponseEntity<Page<AuditLogResponse>> listAll(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(auditLogService.listAll(pageable));
    }

    @Operation(summary = "List audit logs for a specific target")
    @GetMapping("/{targetType}/{targetId}")
    public ResponseEntity<Page<AuditLogResponse>> listByTarget(
            @PathVariable String targetType,
            @PathVariable UUID targetId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(auditLogService.listByTarget(targetType, targetId, pageable));
    }
}
```

- [ ] **Step 5: Run all tests**

```bash
./gradlew :api-service:test --tests "*.AdminPartnerApplicationControllerTest"
```
Expected: `3 tests passed`

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/admin/controller/AdminPartnerApplicationController.java \
        api-service/src/main/java/com/tinniestudio/api/modules/admin/controller/AuditLogController.java \
        api-service/src/test/java/com/tinniestudio/api/modules/admin/controller/AdminPartnerApplicationControllerTest.java
git commit -m "feat(partner-admin): implement AdminPartnerApplicationController and AuditLogController (TDD)"
```

---

## Task 11: PartnerController TDD

**Files:**
- Create: `modules/partner/controller/PartnerController.java`
- Test: `src/test/java/.../partner/controller/PartnerControllerTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.tinniestudio.api.modules.partner.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.admin.service.PartnerApplicationService;
import com.tinniestudio.api.modules.partner.dto.*;
import com.tinniestudio.api.modules.partner.service.PartnerService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PartnerController.class)
@AutoConfigureMockMvc(addFilters = false)
class PartnerControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean PartnerService partnerService;
    @MockBean PartnerApplicationService applicationService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    private static final String CTX = "/api/v1";
    private static final String PARTNER_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
    void getProfile_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(partnerService.getProfile(any())).thenReturn(
            new PartnerProfileResponse(id, UUID.fromString(PARTNER_ID), "Acme", null, null, null, new BigDecimal("70.00"), true)
        );

        mockMvc.perform(get(CTX + "/partner/profile").contextPath(CTX))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.companyName").value("Acme"));
    }

    @Test
    @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
    void getDashboard_returns200() throws Exception {
        when(partnerService.getDashboard(any())).thenReturn(
            new PartnerDashboardResponse(5L, 2L, 1L, 1000L, List.of())
        );

        mockMvc.perform(get(CTX + "/partner/dashboard").contextPath(CTX))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.publishedContentCount").value(5));
    }

    @Test
    @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
    void uploadLogo_returns200WithLogoUrl() throws Exception {
        when(partnerService.uploadLogo(any(), any())).thenReturn("https://cdn.test/logo.jpg");

        MockMultipartFile file = new MockMultipartFile("file", "logo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        mockMvc.perform(multipart(CTX + "/partner/profile/logo").file(file).contextPath(CTX))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.logoUrl").value("https://cdn.test/logo.jpg"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void applyForPartner_asUser_returns201() throws Exception {
        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme");

        var appResponse = new com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse(
            UUID.randomUUID(), UUID.randomUUID(), "Acme", null, null, "PENDING", null, null, null, java.time.Instant.now()
        );
        when(applicationService.apply(any(), any())).thenReturn(appResponse);

        mockMvc.perform(post(CTX + "/partner/apply").contextPath(CTX)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("PENDING"));
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./gradlew :api-service:test --tests "*.PartnerControllerTest"
```
Expected: `FAILED — PartnerController not found`

- [ ] **Step 3: Create PartnerController**

```java
package com.tinniestudio.api.modules.partner.controller;

import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.service.PartnerApplicationService;
import com.tinniestudio.api.modules.content.dto.ContentResponse;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.partner.dto.*;
import com.tinniestudio.api.modules.partner.service.PartnerService;
import com.tinniestudio.api.modules.upload.dto.UploadSessionResponse;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Partner Portal", description = "Partner self-service portal")
@RestController
@RequestMapping("/partner")
@RequiredArgsConstructor
public class PartnerController {

    private final PartnerService partnerService;
    private final PartnerApplicationService applicationService;
    private final ContentRepository contentRepo;
    private final UploadSessionRepository uploadSessionRepo;

    private UUID userId(UserDetails p) { return UUID.fromString(p.getUsername()); }

    // ── Partner Application (open to all authenticated users) ──────────────

    @Operation(summary = "Apply to become a partner")
    @PostMapping("/apply")
    public ResponseEntity<PartnerApplicationResponse> apply(
            @Valid @RequestBody PartnerApplicationRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(applicationService.apply(userId(principal), req));
    }

    // ── Partner Portal (PARTNER role required) ─────────────────────────────

    @Operation(summary = "Get partner profile")
    @GetMapping("/profile")
    @PreAuthorize("hasRole('PARTNER')")
    public ResponseEntity<PartnerProfileResponse> getProfile(@AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(partnerService.getProfile(userId(principal)));
    }

    @Operation(summary = "Update partner profile")
    @PatchMapping("/profile")
    @PreAuthorize("hasRole('PARTNER')")
    public ResponseEntity<PartnerProfileResponse> updateProfile(
            @Valid @RequestBody UpdatePartnerProfileRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(partnerService.updateProfile(userId(principal), req));
    }

    @Operation(summary = "Upload partner logo")
    @PostMapping(value = "/profile/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('PARTNER')")
    public ResponseEntity<Map<String, String>> uploadLogo(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserDetails principal) throws IOException {
        String logoUrl = partnerService.uploadLogo(userId(principal), file);
        return ResponseEntity.ok(Map.of("logoUrl", logoUrl));
    }

    @Operation(summary = "Get partner dashboard stats")
    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('PARTNER')")
    public ResponseEntity<PartnerDashboardResponse> getDashboard(@AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(partnerService.getDashboard(userId(principal)));
    }

    @Operation(summary = "List partner uploads with processing status")
    @GetMapping("/uploads")
    @PreAuthorize("hasRole('PARTNER')")
    public ResponseEntity<Page<UploadSessionResponse>> getUploads(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID uid = userId(principal);
        return ResponseEntity.ok(
            uploadSessionRepo.findByContentCreatedByOrderByCreatedAtDesc(uid, pageable)
                .map(UploadSessionResponse::from)
        );
    }

    @Operation(summary = "List partner's own content")
    @GetMapping("/contents")
    @PreAuthorize("hasRole('PARTNER')")
    public ResponseEntity<Page<ContentResponse>> getContents(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID uid = userId(principal);
        return ResponseEntity.ok(
            contentRepo.findByCreatedByOrderByCreatedAtDesc(uid, pageable)
                .map(ContentResponse::from)
        );
    }
}
```

Add to `UploadSessionRepository.java`:
```java
Page<UploadSession> findByContentCreatedByOrderByCreatedAtDesc(UUID createdBy, Pageable pageable);
```

Add to `ContentRepository.java`:
```java
Page<Content> findByCreatedByOrderByCreatedAtDesc(UUID createdBy, Pageable pageable);
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
./gradlew :api-service:test --tests "*.PartnerControllerTest"
```
Expected: `4 tests passed`

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/partner/controller/PartnerController.java \
        api-service/src/test/java/com/tinniestudio/api/modules/partner/controller/PartnerControllerTest.java
git commit -m "feat(partner-admin): implement PartnerController with profile/dashboard/apply/uploads/contents (TDD)"
```

---

## Task 12: Wire file_size_bytes on upload completion + full test run

**Files:**
- Modify: `modules/upload/controller/UploadController.java` — capture file size on complete
- Modify: `shared/entity/UploadSession.java` — add `fileSizeBytes` field

- [ ] **Step 1: Add fileSizeBytes to UploadSession entity**

In `UploadSession.java`, add:
```java
@Column(nullable = false)
private Long fileSizeBytes = 0L;
```

- [ ] **Step 2: Set fileSizeBytes on upload completion**

In `UploadController.java` (or `UploadServiceImpl.java`), find the `completeUpload` method and add after verifying the object exists:

```java
// After objectExists check, before creating VideoAsset:
try {
    var metadata = storageService.getMetadata(session.getRawObjectKey());
    session.setFileSizeBytes(metadata.contentLength());
    uploadSessionRepo.save(session);
} catch (Exception e) {
    // Non-fatal: size tracking is best-effort
    log.warn("Could not retrieve file size for session {}: {}", session.getId(), e.getMessage());
}
```

- [ ] **Step 3: Run the full test suite**

```bash
./gradlew :api-service:test
```
Expected: all tests pass, no regressions

- [ ] **Step 4: Final commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/UploadSession.java \
        api-service/src/main/java/com/tinniestudio/api/modules/upload/
git commit -m "feat(partner-admin): capture file_size_bytes on upload completion for storage tracking"
```

---

## Self-Review Checklist

**Spec coverage:**
- [x] `POST /partner/apply` — user applies for partner
- [x] `GET /partner/profile` + `PATCH /partner/profile` — partner manages profile
- [x] `POST /partner/profile/logo` — logo upload via StorageService
- [x] `GET /partner/dashboard` — partner dashboard with stats + recent activity
- [x] `GET /partner/uploads` — upload sessions with processing status
- [x] `GET /partner/contents` — partner's own content
- [x] `GET /admin/dashboard` — platform stats with RabbitAdmin queue depth
- [x] `GET /admin/users` + `GET /admin/users/:id` + `PATCH /admin/users/:id` + `PATCH /admin/users/:id/status` + `DELETE /admin/users/:id`
- [x] `GET /admin/contents` — all content, all statuses
- [x] `GET /admin/uploads/processing` — processing failures
- [x] `GET /admin/partner-applications` + approve + reject
- [x] `GET /admin/audit-logs` + `GET /admin/audit-logs/{type}/{id}`
- [x] BAN added to AccountStatus
- [x] Suspension/ban revokes all tokens via existing `SessionService`
- [x] Audit log written on every moderation action
- [x] file_size_bytes tracked on upload completion
- [x] Logo upload via `StorageService.uploadFile()`
- [x] partner_profiles auto-created on application approval
- [x] RabbitAdmin bean added to RabbitConfig

**Not in scope (deferred):**
- Partner analytics time-series → Batch B (15+16+17)
- Revenue reporting → Batch B analytics
- Admin user role promotion endpoint → can be done via `PATCH /admin/users/:id` + `updateStatus`
