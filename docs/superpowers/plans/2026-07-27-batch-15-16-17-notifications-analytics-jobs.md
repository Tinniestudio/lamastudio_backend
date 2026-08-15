# Batch 15+16+17 — Notifications, Analytics, Background Jobs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the notification system (templates, delivery, preferences), content analytics (daily aggregations, partner/admin dashboards, CSV export), and background jobs (session/asset cleanup, stale recovery, token expiry) using RabbitMQ consumers and ShedLock-guarded scheduled tasks.

**Architecture:** All code lives on `api-service`. Notifications consume from the existing `notifications.send` queue (media-worker already publishes `CONTENT_PROCESSED` events there). Analytics consume from the existing `analytics.ingest` queue (PlaybackServiceImpl already publishes `PROGRESS_TRACKED` events there; we add `VIEW_EVENT` when manifests are fetched). Background jobs use ShedLock over JDBC for distributed coordination. Three new migrations: V39 (notifications), V40 (analytics), V41 (ShedLock + job log).

**Tech Stack:** Spring Boot 3.3.5 / Java 21, Spring AMQP `@RabbitListener`, ShedLock 6.x (JDBC provider), `@Scheduled` + `@EnableScheduling`, JPA, Flyway, PostgreSQL native upsert, `@WebMvcTest` for controllers, Mockito for services.

---

## Critical Context — Read Before Starting

- **Branch:** `staging` — never merge to `main`
- **Queues** (already declared in `RabbitConfig.java`):
  - `QUEUE_NOTIFICATIONS = "notifications.send"` — media-worker publishes `CONTENT_PROCESSED` events here
  - `QUEUE_ANALYTICS_INGEST = "analytics.ingest"` — PlaybackServiceImpl publishes `PROGRESS_TRACKED` here
- **`Content.viewCount`** field and `contents.view_count` DB column both exist (V18 migration). Use `@Modifying @Query` for atomic increment.
- **`user_sessions.expires_at`** already exists — no migration needed for token expiry job.
- **`DomainEnums.java`** is at `shared/entity/DomainEnums.java` — add new enums there.
- **`RoleName`** is a standalone class at `shared/entity/RoleName.java`, NOT in DomainEnums.
- **Response envelope:** `SuccessResponseWrapper` wraps all 2xx. Tests assert `$.data.*`.
- **Test `@MockBean` pattern:** `JwtTokenProvider`, `JwtAuthenticationFilter`, `UserDetailsServiceImpl` — always mock all three in `@WebMvcTest`.
- **`@WithMockUser`** pattern: `username = UUID_STRING, roles = "ADMIN"` or `"PARTNER"`.
- **No `AppException`:** use `ResourceNotFoundException(String)` and `BadRequestException(String)`.
- **`userId()` from principal:** `UUID.fromString(principal.getUsername())`
- **ShedLock** is not yet in `build.gradle` — add it in Task 10.
- **`PlaybackServiceImpl`** already publishes to `analytics.ingest` — we add VIEW event in Task 7.

---

## File Map

**New migrations:**
- `V39__add_notifications.sql`
- `V40__add_content_analytics_daily.sql`
- `V41__add_job_execution_log_and_shedlock.sql`

**New enums in `shared/entity/DomainEnums.java`:**
- `NotificationEventType { CONTENT_PROCESSED, CONTENT_APPROVED, CONTENT_REJECTED, APPLICATION_APPROVED, APPLICATION_REJECTED, ACCOUNT_SUSPENDED, ACCOUNT_BANNED }`
- `NotificationChannel { IN_APP, EMAIL }`

**New entities in `shared/entity/`:**
- `NotificationTemplate.java`
- `Notification.java`
- `NotificationPreference.java`
- `ContentAnalyticsDaily.java` + `ContentAnalyticsDailyId.java` (composite PK)
- `JobExecutionLog.java`

**New repositories:**
- `modules/notification/repository/NotificationTemplateRepository.java`
- `modules/notification/repository/NotificationRepository.java`
- `modules/notification/repository/NotificationPreferenceRepository.java`
- `modules/analytics/repository/ContentAnalyticsDailyRepository.java`
- `modules/jobs/repository/JobExecutionLogRepository.java`

**New DTOs:**
- `modules/notification/dto/NotificationTemplateResponse.java`
- `modules/notification/dto/CreateNotificationTemplateRequest.java`
- `modules/notification/dto/UpdateNotificationTemplateRequest.java`
- `modules/notification/dto/NotificationResponse.java`
- `modules/notification/dto/NotificationPreferenceResponse.java`
- `modules/notification/dto/UpdatePreferenceRequest.java`
- `modules/analytics/dto/ContentAnalyticsDailyResponse.java`
- `modules/analytics/dto/PartnerAnalyticsSummaryResponse.java`

**New services:**
- `modules/notification/service/NotificationTemplateService.java` + `NotificationTemplateServiceImpl.java`
- `modules/notification/service/NotificationService.java` + `NotificationServiceImpl.java`
- `modules/analytics/service/AnalyticsService.java` + `AnalyticsServiceImpl.java`

**New consumers:**
- `modules/notification/consumer/NotificationConsumer.java`
- `modules/analytics/consumer/AnalyticsConsumer.java`

**New controllers:**
- `modules/notification/controller/AdminNotificationTemplateController.java`
- `modules/notification/controller/NotificationController.java`
- `modules/analytics/controller/AnalyticsController.java`

**New jobs:**
- `modules/jobs/ExpiredUploadSessionJob.java`
- `modules/jobs/StaleVideoAssetJob.java`
- `modules/jobs/FailedVideoAssetJob.java`
- `modules/jobs/ExpiredSessionCleanupJob.java`
- `modules/jobs/NotificationCleanupJob.java`
- `modules/jobs/JobLogger.java` (helper to write job_execution_log)

**Modified:**
- `shared/entity/DomainEnums.java` — add notification enums
- `modules/playback/service/PlaybackServiceImpl.java` — add VIEW_EVENT publish in manifest methods
- `api-service/build.gradle` — add ShedLock dependencies
- `modules/upload/repository/UploadSessionRepository.java` — add bulk-delete expired sessions query
- `modules/content/repository/ContentRepository.java` — add `incrementViewCount` method

---

## Task 1: DB Migrations V39–V41

**Files:**
- Create: `api-service/src/main/resources/db/migration/V39__add_notifications.sql`
- Create: `api-service/src/main/resources/db/migration/V40__add_content_analytics_daily.sql`
- Create: `api-service/src/main/resources/db/migration/V41__add_job_execution_log_and_shedlock.sql`

- [ ] **Step 1: Write V39 — Notifications**

```sql
-- V39__add_notifications.sql

CREATE TABLE notification_templates (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(100) NOT NULL UNIQUE,
    title_template  VARCHAR(500) NOT NULL,
    body_template   TEXT         NOT NULL,
    channel         VARCHAR(20)  NOT NULL DEFAULT 'IN_APP',
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notif_template_event ON notification_templates(event_type);

CREATE TABLE notifications (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type      VARCHAR(100) NOT NULL,
    title           VARCHAR(500) NOT NULL,
    body            TEXT         NOT NULL,
    channel         VARCHAR(20)  NOT NULL DEFAULT 'IN_APP',
    is_read         BOOLEAN      NOT NULL DEFAULT false,
    read_at         TIMESTAMPTZ,
    reference_type  VARCHAR(50),
    reference_id    UUID,
    sent_at         TIMESTAMPTZ,
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_user    ON notifications(user_id);
CREATE INDEX idx_notifications_unread  ON notifications(user_id) WHERE is_read = false;
CREATE INDEX idx_notifications_created ON notifications(created_at DESC);

CREATE TABLE notification_preferences (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel     VARCHAR(20) NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    is_enabled  BOOLEAN     NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, channel, event_type)
);

CREATE INDEX idx_notif_pref_user ON notification_preferences(user_id);
```

- [ ] **Step 2: Write V40 — Content Analytics Daily**

```sql
-- V40__add_content_analytics_daily.sql

CREATE TABLE content_analytics_daily (
    content_id          UUID    NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    analytics_date      DATE    NOT NULL,
    views               INTEGER NOT NULL DEFAULT 0,
    unique_viewers      INTEGER NOT NULL DEFAULT 0,
    completions         INTEGER NOT NULL DEFAULT 0,
    watch_time_seconds  BIGINT  NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (content_id, analytics_date)
);

CREATE INDEX idx_analytics_content ON content_analytics_daily(content_id);
CREATE INDEX idx_analytics_date    ON content_analytics_daily(analytics_date DESC);
```

- [ ] **Step 3: Write V41 — Job Log + ShedLock**

```sql
-- V41__add_job_execution_log_and_shedlock.sql

CREATE TABLE job_execution_log (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    job_name         VARCHAR(100) NOT NULL,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    status           VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    items_processed  INTEGER      NOT NULL DEFAULT 0,
    error_message    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_job_log_name    ON job_execution_log(job_name);
CREATE INDEX idx_job_log_started ON job_execution_log(started_at DESC);

CREATE TABLE shedlock (
    name        VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
```

- [ ] **Step 4: Verify SQL syntax**

```bash
grep -c "CREATE" api-service/src/main/resources/db/migration/V39__add_notifications.sql
grep -c "CREATE" api-service/src/main/resources/db/migration/V40__add_content_analytics_daily.sql
grep -c "CREATE" api-service/src/main/resources/db/migration/V41__add_job_execution_log_and_shedlock.sql
```
(Migrations will apply at Spring Boot startup — no flywayMigrate task available.)

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/resources/db/migration/V39__add_notifications.sql \
        api-service/src/main/resources/db/migration/V40__add_content_analytics_daily.sql \
        api-service/src/main/resources/db/migration/V41__add_job_execution_log_and_shedlock.sql
git commit -m "feat(b15-17): add DB migrations V39-V41 (notifications, analytics, job log, shedlock)"
```

---

## Task 2: Notification Enums + Entities + Repositories

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/shared/entity/NotificationTemplate.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/shared/entity/Notification.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/shared/entity/NotificationPreference.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/notification/repository/NotificationTemplateRepository.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/notification/repository/NotificationRepository.java`
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/notification/repository/NotificationPreferenceRepository.java`

- [ ] **Step 1: Add enums to DomainEnums.java**

In `DomainEnums.java`, add after the existing enums:

```java
public enum NotificationEventType {
    CONTENT_PROCESSED,
    CONTENT_APPROVED,
    CONTENT_REJECTED,
    APPLICATION_APPROVED,
    APPLICATION_REJECTED,
    ACCOUNT_SUSPENDED,
    ACCOUNT_BANNED
}

public enum NotificationChannel {
    IN_APP, EMAIL
}
```

- [ ] **Step 2: Create NotificationTemplate entity**

```java
package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification_templates")
@Getter @Setter @NoArgsConstructor
public class NotificationTemplate extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private NotificationEventType eventType;

    @Column(nullable = false)
    private String titleTemplate;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String bodyTemplate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel = NotificationChannel.IN_APP;

    @Column(nullable = false)
    private Boolean isActive = true;
}
```

- [ ] **Step 3: Create Notification entity**

```java
package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter @Setter @NoArgsConstructor
public class Notification extends BaseEntity {

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationEventType eventType;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel = NotificationChannel.IN_APP;

    @Column(nullable = false)
    private Boolean isRead = false;

    private Instant readAt;

    private String referenceType;

    private UUID referenceId;

    private Instant sentAt;

    @Column(nullable = false)
    private Integer retryCount = 0;
}
```

- [ ] **Step 4: Create NotificationPreference entity**

```java
package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
@Getter @Setter @NoArgsConstructor
public class NotificationPreference extends BaseEntity {

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationEventType eventType;

    @Column(nullable = false)
    private Boolean isEnabled = true;
}
```

- [ ] **Step 5: Create NotificationTemplateRepository**

```java
package com.tinniestudio.api.modules.notification.repository;

import com.tinniestudio.api.shared.entity.NotificationTemplate;
import com.tinniestudio.api.shared.entity.DomainEnums.NotificationEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {
    Optional<NotificationTemplate> findByEventTypeAndIsActiveTrue(NotificationEventType eventType);
    boolean existsByEventType(NotificationEventType eventType);
}
```

- [ ] **Step 6: Create NotificationRepository**

```java
package com.tinniestudio.api.modules.notification.repository;

import com.tinniestudio.api.shared.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
    long countByUserIdAndIsReadFalse(UUID userId);
    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = :now WHERE n.userId = :userId AND n.isRead = false")
    int markAllReadForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
```

- [ ] **Step 7: Create NotificationPreferenceRepository**

```java
package com.tinniestudio.api.modules.notification.repository;

import com.tinniestudio.api.shared.entity.NotificationPreference;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
    List<NotificationPreference> findByUserId(UUID userId);
    Optional<NotificationPreference> findByUserIdAndChannelAndEventType(
        UUID userId, NotificationChannel channel, NotificationEventType eventType);
}
```

- [ ] **Step 8: Compile**

```bash
./gradlew :api-service:compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/ \
        api-service/src/main/java/com/tinniestudio/api/modules/notification/repository/
git commit -m "feat(b15): add notification enums, entities, and repositories"
```

---

## Task 3: NotificationTemplate Admin Service (TDD)

**Files:**
- Create: `modules/notification/dto/NotificationTemplateResponse.java`
- Create: `modules/notification/dto/CreateNotificationTemplateRequest.java`
- Create: `modules/notification/dto/UpdateNotificationTemplateRequest.java`
- Create: `modules/notification/service/NotificationTemplateService.java`
- Create: `modules/notification/service/NotificationTemplateServiceImpl.java`
- Test: `src/test/java/com/tinniestudio/api/modules/notification/service/NotificationTemplateServiceTest.java`

- [ ] **Step 1: Create DTOs**

```java
// NotificationTemplateResponse.java
package com.tinniestudio.api.modules.notification.dto;

import com.tinniestudio.api.shared.entity.NotificationTemplate;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import java.time.Instant;
import java.util.UUID;

public record NotificationTemplateResponse(
    UUID id,
    String eventType,
    String titleTemplate,
    String bodyTemplate,
    String channel,
    Boolean isActive,
    Instant createdAt,
    Instant updatedAt
) {
    public static NotificationTemplateResponse from(NotificationTemplate t) {
        return new NotificationTemplateResponse(
            t.getId(), t.getEventType().name(), t.getTitleTemplate(),
            t.getBodyTemplate(), t.getChannel().name(), t.getIsActive(),
            t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}
```

```java
// CreateNotificationTemplateRequest.java
package com.tinniestudio.api.modules.notification.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateNotificationTemplateRequest {
    @NotNull NotificationEventType eventType;
    @NotBlank String titleTemplate;
    @NotBlank String bodyTemplate;
    @NotNull NotificationChannel channel;
}
```

```java
// UpdateNotificationTemplateRequest.java
package com.tinniestudio.api.modules.notification.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.*;
import lombok.Data;

@Data
public class UpdateNotificationTemplateRequest {
    String titleTemplate;
    String bodyTemplate;
    NotificationChannel channel;
    Boolean isActive;
}
```

- [ ] **Step 2: Define service interface**

```java
// NotificationTemplateService.java
package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.*;
import java.util.List;
import java.util.UUID;

public interface NotificationTemplateService {
    NotificationTemplateResponse create(CreateNotificationTemplateRequest req);
    List<NotificationTemplateResponse> list();
    NotificationTemplateResponse update(UUID id, UpdateNotificationTemplateRequest req);
    void delete(UUID id);
}
```

- [ ] **Step 3: Write failing tests**

```java
// NotificationTemplateServiceTest.java
package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.repository.NotificationTemplateRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.entity.NotificationTemplate;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationTemplateServiceTest {

    @Mock NotificationTemplateRepository templateRepo;
    @InjectMocks NotificationTemplateServiceImpl service;

    @Test
    void create_savesTemplate() {
        var req = new CreateNotificationTemplateRequest();
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setTitleTemplate("Your video is ready");
        req.setBodyTemplate("Your content {{contentId}} has been processed.");
        req.setChannel(NotificationChannel.IN_APP);

        when(templateRepo.existsByEventType(NotificationEventType.CONTENT_PROCESSED)).thenReturn(false);
        when(templateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(req);

        assertThat(result.eventType()).isEqualTo("CONTENT_PROCESSED");
        verify(templateRepo).save(any(NotificationTemplate.class));
    }

    @Test
    void create_throwsBadRequest_whenEventTypeAlreadyExists() {
        var req = new CreateNotificationTemplateRequest();
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setTitleTemplate("T"); req.setBodyTemplate("B");
        req.setChannel(NotificationChannel.IN_APP);

        when(templateRepo.existsByEventType(NotificationEventType.CONTENT_PROCESSED)).thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void update_throwsNotFound_whenMissing() {
        when(templateRepo.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(UUID.randomUUID(), new UpdateNotificationTemplateRequest()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void list_returnsAll() {
        when(templateRepo.findAll()).thenReturn(List.of());
        assertThat(service.list()).isEmpty();
    }

    @Test
    void delete_throwsNotFound_whenMissing() {
        when(templateRepo.existsById(any())).thenReturn(false);
        assertThatThrownBy(() -> service.delete(UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
```

- [ ] **Step 4: Run tests — expect FAIL**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.service.NotificationTemplateServiceTest" 2>&1 | tail -10
```

- [ ] **Step 5: Implement NotificationTemplateServiceImpl**

```java
package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.repository.NotificationTemplateRepository;
import com.tinniestudio.api.shared.entity.NotificationTemplate;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationTemplateServiceImpl implements NotificationTemplateService {

    private final NotificationTemplateRepository templateRepo;

    @Override
    @Transactional
    public NotificationTemplateResponse create(CreateNotificationTemplateRequest req) {
        if (templateRepo.existsByEventType(req.getEventType())) {
            throw new BadRequestException("Template for event type already exists: " + req.getEventType());
        }
        NotificationTemplate t = new NotificationTemplate();
        t.setEventType(req.getEventType());
        t.setTitleTemplate(req.getTitleTemplate());
        t.setBodyTemplate(req.getBodyTemplate());
        t.setChannel(req.getChannel());
        return NotificationTemplateResponse.from(templateRepo.save(t));
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationTemplateResponse> list() {
        return templateRepo.findAll().stream().map(NotificationTemplateResponse::from).toList();
    }

    @Override
    @Transactional
    public NotificationTemplateResponse update(UUID id, UpdateNotificationTemplateRequest req) {
        NotificationTemplate t = templateRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Notification template not found: " + id));
        if (req.getTitleTemplate() != null) t.setTitleTemplate(req.getTitleTemplate());
        if (req.getBodyTemplate() != null) t.setBodyTemplate(req.getBodyTemplate());
        if (req.getChannel() != null) t.setChannel(req.getChannel());
        if (req.getIsActive() != null) t.setIsActive(req.getIsActive());
        return NotificationTemplateResponse.from(templateRepo.save(t));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        if (!templateRepo.existsById(id)) {
            throw new ResourceNotFoundException("Notification template not found: " + id);
        }
        templateRepo.deleteById(id);
    }
}
```

- [ ] **Step 6: Run tests — expect PASS**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.service.NotificationTemplateServiceTest" 2>&1 | tail -10
```

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/notification/ \
        api-service/src/test/java/com/tinniestudio/api/modules/notification/
git commit -m "feat(b15): implement NotificationTemplateService (TDD)"
```

---

## Task 4: NotificationService + Preferences (TDD)

**Files:**
- Create: `modules/notification/dto/NotificationResponse.java`
- Create: `modules/notification/dto/NotificationPreferenceResponse.java`
- Create: `modules/notification/dto/UpdatePreferenceRequest.java`
- Create: `modules/notification/service/NotificationService.java`
- Create: `modules/notification/service/NotificationServiceImpl.java`
- Test: `src/test/java/com/tinniestudio/api/modules/notification/service/NotificationServiceTest.java`

- [ ] **Step 1: Create DTOs**

```java
// NotificationResponse.java
package com.tinniestudio.api.modules.notification.dto;

import com.tinniestudio.api.shared.entity.Notification;
import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
    UUID id,
    String eventType,
    String title,
    String body,
    String channel,
    Boolean isRead,
    Instant readAt,
    String referenceType,
    UUID referenceId,
    Instant createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
            n.getId(), n.getEventType().name(), n.getTitle(), n.getBody(),
            n.getChannel().name(), n.getIsRead(), n.getReadAt(),
            n.getReferenceType(), n.getReferenceId(), n.getCreatedAt()
        );
    }
}
```

```java
// NotificationPreferenceResponse.java
package com.tinniestudio.api.modules.notification.dto;

import com.tinniestudio.api.shared.entity.NotificationPreference;
import java.util.UUID;

public record NotificationPreferenceResponse(
    UUID id,
    String channel,
    String eventType,
    Boolean isEnabled
) {
    public static NotificationPreferenceResponse from(NotificationPreference p) {
        return new NotificationPreferenceResponse(
            p.getId(), p.getChannel().name(), p.getEventType().name(), p.getIsEnabled()
        );
    }
}
```

```java
// UpdatePreferenceRequest.java
package com.tinniestudio.api.modules.notification.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdatePreferenceRequest {
    @NotNull NotificationChannel channel;
    @NotNull NotificationEventType eventType;
    @NotNull Boolean isEnabled;
}
```

- [ ] **Step 2: Define service interface**

```java
// NotificationService.java
package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface NotificationService {
    void sendNotification(UUID userId, NotificationEventType eventType,
                          String referenceType, UUID referenceId);
    Page<NotificationResponse> listForUser(UUID userId, Pageable pageable);
    void markRead(UUID userId, UUID notificationId);
    void markAllRead(UUID userId);
    long getUnreadCount(UUID userId);
    List<NotificationPreferenceResponse> getPreferences(UUID userId);
    NotificationPreferenceResponse updatePreference(UUID userId, UpdatePreferenceRequest req);
}
```

- [ ] **Step 3: Write failing tests**

```java
// NotificationServiceTest.java
package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.UpdatePreferenceRequest;
import com.tinniestudio.api.modules.notification.repository.NotificationPreferenceRepository;
import com.tinniestudio.api.modules.notification.repository.NotificationRepository;
import com.tinniestudio.api.modules.notification.repository.NotificationTemplateRepository;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository notificationRepo;
    @Mock NotificationTemplateRepository templateRepo;
    @Mock NotificationPreferenceRepository preferenceRepo;
    @InjectMocks NotificationServiceImpl service;

    @Test
    void sendNotification_createsNotification_whenTemplateExists() {
        UUID userId = UUID.randomUUID();
        NotificationTemplate template = new NotificationTemplate();
        template.setEventType(NotificationEventType.CONTENT_PROCESSED);
        template.setTitleTemplate("Ready");
        template.setBodyTemplate("Your content is ready");
        template.setChannel(NotificationChannel.IN_APP);
        template.setIsActive(true);

        when(templateRepo.findByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.of(template));
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getIsRead()).isFalse();
    }

    @Test
    void sendNotification_skips_whenNoTemplate() {
        when(templateRepo.findByEventTypeAndIsActiveTrue(any())).thenReturn(Optional.empty());
        service.sendNotification(UUID.randomUUID(), NotificationEventType.CONTENT_PROCESSED, null, null);
        verify(notificationRepo, never()).save(any());
    }

    @Test
    void getUnreadCount_returnsCount() {
        UUID userId = UUID.randomUUID();
        when(notificationRepo.countByUserIdAndIsReadFalse(userId)).thenReturn(5L);
        assertThat(service.getUnreadCount(userId)).isEqualTo(5L);
    }

    @Test
    void markRead_throwsNotFound_whenNotificationMissing() {
        UUID userId = UUID.randomUUID();
        UUID notifId = UUID.randomUUID();
        when(notificationRepo.findByIdAndUserId(notifId, userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.markRead(userId, notifId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updatePreference_createsIfAbsent() {
        UUID userId = UUID.randomUUID();
        UpdatePreferenceRequest req = new UpdatePreferenceRequest();
        req.setChannel(NotificationChannel.IN_APP);
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setIsEnabled(false);

        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.IN_APP, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.empty());
        when(preferenceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.updatePreference(userId, req);
        assertThat(result.isEnabled()).isFalse();
    }
}
```

- [ ] **Step 4: Run tests — expect FAIL**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.service.NotificationServiceTest" 2>&1 | tail -10
```

- [ ] **Step 5: Implement NotificationServiceImpl**

```java
package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.repository.*;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepo;
    private final NotificationTemplateRepository templateRepo;
    private final NotificationPreferenceRepository preferenceRepo;

    @Override
    @Transactional
    public void sendNotification(UUID userId, NotificationEventType eventType,
                                  String referenceType, UUID referenceId) {
        Optional<NotificationTemplate> templateOpt =
            templateRepo.findByEventTypeAndIsActiveTrue(eventType);
        if (templateOpt.isEmpty()) {
            log.debug("No active template for event type: {}. Skipping.", eventType);
            return;
        }
        NotificationTemplate template = templateOpt.get();
        Notification n = new Notification();
        n.setUserId(userId);
        n.setEventType(eventType);
        n.setTitle(template.getTitleTemplate());
        n.setBody(template.getBodyTemplate());
        n.setChannel(template.getChannel());
        n.setReferenceType(referenceType);
        n.setReferenceId(referenceId);
        n.setSentAt(Instant.now());
        notificationRepo.save(n);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> listForUser(UUID userId, Pageable pageable) {
        return notificationRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)
            .map(NotificationResponse::from);
    }

    @Override
    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification n = notificationRepo.findByIdAndUserId(notificationId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + notificationId));
        if (!n.getIsRead()) {
            n.setIsRead(true);
            n.setReadAt(Instant.now());
            notificationRepo.save(n);
        }
    }

    @Override
    @Transactional
    public void markAllRead(UUID userId) {
        notificationRepo.markAllReadForUser(userId, Instant.now());
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return notificationRepo.countByUserIdAndIsReadFalse(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationPreferenceResponse> getPreferences(UUID userId) {
        return preferenceRepo.findByUserId(userId).stream()
            .map(NotificationPreferenceResponse::from).toList();
    }

    @Override
    @Transactional
    public NotificationPreferenceResponse updatePreference(UUID userId, UpdatePreferenceRequest req) {
        NotificationPreference pref = preferenceRepo
            .findByUserIdAndChannelAndEventType(userId, req.getChannel(), req.getEventType())
            .orElseGet(() -> {
                NotificationPreference p = new NotificationPreference();
                p.setUserId(userId);
                p.setChannel(req.getChannel());
                p.setEventType(req.getEventType());
                return p;
            });
        pref.setIsEnabled(req.getIsEnabled());
        return NotificationPreferenceResponse.from(preferenceRepo.save(pref));
    }
}
```

- [ ] **Step 6: Run tests — expect PASS**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.service.NotificationServiceTest" 2>&1 | tail -10
```

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/notification/ \
        api-service/src/test/java/com/tinniestudio/api/modules/notification/
git commit -m "feat(b15): implement NotificationService with preferences (TDD)"
```

---

## Task 5: Notification Consumer (CONTENT_PROCESSED → update VideoAsset + notify)

**Files:**
- Create: `modules/notification/consumer/NotificationConsumer.java`
- Test: `src/test/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumerTest.java`

The consumer listens to `notifications.send`. When the media-worker publishes `CONTENT_PROCESSED`:
- Finds the VideoAsset and updates its `processingStatus` to READY (or FAILED)
- Looks up the content's creator
- Calls `notificationService.sendNotification(creatorId, CONTENT_PROCESSED, "VIDEO_ASSET", assetId)`

The message payload is a `Map<String, Object>`:
```json
{"type": "CONTENT_PROCESSED", "videoAssetId": "uuid", "contentId": "uuid", "status": "READY"}
```

- [ ] **Step 1: Write failing test**

```java
// NotificationConsumerTest.java
package com.tinniestudio.api.modules.notification.consumer;

import com.tinniestudio.api.modules.notification.service.NotificationService;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

    @Mock NotificationService notificationService;
    @Mock VideoAssetRepository videoAssetRepo;
    @Mock ContentRepository contentRepo;
    @InjectMocks NotificationConsumer consumer;

    @Test
    void handleContentProcessed_updatesStatusAndNotifies() {
        UUID assetId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();

        VideoAsset asset = new VideoAsset();
        asset.setProcessingStatus(ProcessingStatus.PROCESSING);

        Content content = new Content();
        content.setCreatedBy(creatorId);

        when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
        when(contentRepo.findById(contentId)).thenReturn(Optional.of(content));
        when(videoAssetRepo.save(any())).thenReturn(asset);

        consumer.handleNotificationEvent(Map.of(
            "type", "CONTENT_PROCESSED",
            "videoAssetId", assetId.toString(),
            "contentId", contentId.toString(),
            "status", "READY"
        ));

        verify(videoAssetRepo).save(asset);
        assertThat(asset.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        verify(notificationService).sendNotification(
            eq(creatorId), eq(NotificationEventType.CONTENT_PROCESSED),
            eq("VIDEO_ASSET"), eq(assetId));
    }

    @Test
    void handleContentProcessed_skips_whenTypeIsUnknown() {
        consumer.handleNotificationEvent(Map.of("type", "UNKNOWN_EVENT"));
        verify(videoAssetRepo, never()).findById(any());
    }
}
```

Note: The test uses `assertThat` — add `import static org.assertj.core.api.Assertions.*;` to the test.

- [ ] **Step 2: Run test — expect FAIL**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.consumer.NotificationConsumerTest" 2>&1 | tail -10
```

- [ ] **Step 3: Implement NotificationConsumer**

```java
package com.tinniestudio.api.modules.notification.consumer;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.notification.service.NotificationService;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.queue.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationService notificationService;
    private final VideoAssetRepository videoAssetRepo;
    private final ContentRepository contentRepo;

    @RabbitListener(queues = RabbitConfig.QUEUE_NOTIFICATIONS)
    public void handleNotificationEvent(Map<String, Object> message) {
        String type = (String) message.get("type");
        if (!"CONTENT_PROCESSED".equals(type)) {
            log.debug("Ignoring notification event type: {}", type);
            return;
        }
        try {
            UUID assetId = UUID.fromString((String) message.get("videoAssetId"));
            UUID contentId = UUID.fromString((String) message.get("contentId"));
            String status = (String) message.get("status");

            VideoAsset asset = videoAssetRepo.findById(assetId).orElse(null);
            if (asset == null) {
                log.warn("VideoAsset not found: {}", assetId);
                return;
            }
            asset.setProcessingStatus("READY".equals(status) ? ProcessingStatus.READY : ProcessingStatus.FAILED);
            videoAssetRepo.save(asset);

            Content content = contentRepo.findById(contentId).orElse(null);
            if (content == null) {
                log.warn("Content not found for notification: {}", contentId);
                return;
            }
            notificationService.sendNotification(
                content.getCreatedBy(),
                NotificationEventType.CONTENT_PROCESSED,
                "VIDEO_ASSET",
                assetId
            );
        } catch (Exception e) {
            log.error("Error processing CONTENT_PROCESSED notification: {}", e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.consumer.NotificationConsumerTest" 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/notification/consumer/ \
        api-service/src/test/java/com/tinniestudio/api/modules/notification/consumer/
git commit -m "feat(b15): implement NotificationConsumer for CONTENT_PROCESSED events (TDD)"
```

---

## Task 6: Notification Controllers (TDD)

**Files:**
- Create: `modules/notification/controller/AdminNotificationTemplateController.java`
- Create: `modules/notification/controller/NotificationController.java`
- Test: `src/test/java/com/tinniestudio/api/modules/notification/controller/AdminNotificationTemplateControllerTest.java`
- Test: `src/test/java/com/tinniestudio/api/modules/notification/controller/NotificationControllerTest.java`

### AdminNotificationTemplateController

Base mapping: `/admin/notification-templates`
Class-level: `@PreAuthorize("hasRole('ADMIN')")`

Endpoints:
- `GET /admin/notification-templates` → `List<NotificationTemplateResponse>` 200
- `POST /admin/notification-templates` → `NotificationTemplateResponse` 201
- `PATCH /admin/notification-templates/{id}` → `NotificationTemplateResponse` 200
- `DELETE /admin/notification-templates/{id}` → 204

### NotificationController

Base mapping: `/notifications`
Class-level: `@PreAuthorize("isAuthenticated()")`

Endpoints:
- `GET /notifications` → `Page<NotificationResponse>` 200
- `POST /notifications/{id}/read` → 204
- `POST /notifications/read-all` → 204
- `GET /notifications/unread-count` → `{ count: long }` 200
- `GET /notifications/preferences` → `List<NotificationPreferenceResponse>` 200
- `PUT /notifications/preferences` → `NotificationPreferenceResponse` 200

- [ ] **Step 1: Write AdminNotificationTemplateController test**

```java
// AdminNotificationTemplateControllerTest.java
package com.tinniestudio.api.modules.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.service.NotificationTemplateService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminNotificationTemplateController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminNotificationTemplateControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean NotificationTemplateService templateService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    NotificationTemplateResponse sample() {
        return new NotificationTemplateResponse(UUID.randomUUID(), "CONTENT_PROCESSED",
            "Ready", "Your content is ready", "IN_APP", true, Instant.now(), Instant.now());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_returns200() throws Exception {
        when(templateService.list()).thenReturn(List.of(sample()));
        mockMvc.perform(get("/admin/notification-templates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].eventType").value("CONTENT_PROCESSED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_returns201() throws Exception {
        var req = new CreateNotificationTemplateRequest();
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setTitleTemplate("Ready"); req.setBodyTemplate("Done");
        req.setChannel(NotificationChannel.IN_APP);

        when(templateService.create(any())).thenReturn(sample());

        mockMvc.perform(post("/admin/notification-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/admin/notification-templates/{id}", UUID.randomUUID()))
            .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 2: Run AdminNotificationTemplateControllerTest — expect FAIL**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.controller.AdminNotificationTemplateControllerTest" 2>&1 | tail -10
```

- [ ] **Step 3: Implement AdminNotificationTemplateController**

```java
package com.tinniestudio.api.modules.notification.controller;

import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.service.NotificationTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Admin - Notification Templates", description = "Manage notification templates")
@RestController
@RequestMapping("/admin/notification-templates")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminNotificationTemplateController {

    private final NotificationTemplateService templateService;

    @Operation(summary = "List all notification templates")
    @GetMapping
    public ResponseEntity<List<NotificationTemplateResponse>> list() {
        return ResponseEntity.ok(templateService.list());
    }

    @Operation(summary = "Create notification template")
    @PostMapping
    public ResponseEntity<NotificationTemplateResponse> create(
            @Valid @RequestBody CreateNotificationTemplateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.create(req));
    }

    @Operation(summary = "Update notification template")
    @PatchMapping("/{id}")
    public ResponseEntity<NotificationTemplateResponse> update(
            @PathVariable UUID id,
            @RequestBody UpdateNotificationTemplateRequest req) {
        return ResponseEntity.ok(templateService.update(id, req));
    }

    @Operation(summary = "Delete notification template")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        templateService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Write NotificationController test**

```java
// NotificationControllerTest.java
package com.tinniestudio.api.modules.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.service.NotificationService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationControllerTest {

    static final String USER_ID = "00000000-0000-0000-0000-000000000002";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean NotificationService notificationService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    NotificationResponse sampleNotif() {
        return new NotificationResponse(UUID.randomUUID(), "CONTENT_PROCESSED",
            "Ready", "Done", "IN_APP", false, null, null, null, Instant.now());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void list_returns200() throws Exception {
        when(notificationService.listForUser(any(), any())).thenReturn(new PageImpl<>(List.of(sampleNotif())));
        mockMvc.perform(get("/notifications"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void unreadCount_returns200() throws Exception {
        when(notificationService.getUnreadCount(any())).thenReturn(3L);
        mockMvc.perform(get("/notifications/unread-count"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.count").value(3));
    }

    @Test
    @WithMockUser(username = USER_ID)
    void markRead_returns204() throws Exception {
        mockMvc.perform(post("/notifications/{id}/read", UUID.randomUUID()))
            .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void getPreferences_returns200() throws Exception {
        when(notificationService.getPreferences(any())).thenReturn(List.of());
        mockMvc.perform(get("/notifications/preferences"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void updatePreference_returns200() throws Exception {
        var req = new UpdatePreferenceRequest();
        req.setChannel(com.tinniestudio.api.shared.entity.DomainEnums.NotificationChannel.IN_APP);
        req.setEventType(com.tinniestudio.api.shared.entity.DomainEnums.NotificationEventType.CONTENT_PROCESSED);
        req.setIsEnabled(false);

        when(notificationService.updatePreference(any(), any()))
            .thenReturn(new NotificationPreferenceResponse(UUID.randomUUID(), "IN_APP", "CONTENT_PROCESSED", false));

        mockMvc.perform(put("/notifications/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk());
    }
}
```

- [ ] **Step 5: Implement NotificationController**

```java
package com.tinniestudio.api.modules.notification.controller;

import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.service.NotificationService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Notifications", description = "User notifications and preferences")
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "List user notifications")
    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> list(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(notificationService.listForUser(userId, pageable));
    }

    @Operation(summary = "Get unread notification count")
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(
            @AuthenticationPrincipal UserDetails principal) {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(Map.of("count", notificationService.getUnreadCount(userId)));
    }

    @Operation(summary = "Mark notification as read")
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails principal) {
        notificationService.markRead(UUID.fromString(principal.getUsername()), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mark all notifications as read")
    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal UserDetails principal) {
        notificationService.markAllRead(UUID.fromString(principal.getUsername()));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get notification preferences")
    @GetMapping("/preferences")
    public ResponseEntity<List<NotificationPreferenceResponse>> getPreferences(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(notificationService.getPreferences(UUID.fromString(principal.getUsername())));
    }

    @Operation(summary = "Update notification preference")
    @PutMapping("/preferences")
    public ResponseEntity<NotificationPreferenceResponse> updatePreference(
            @Valid @RequestBody UpdatePreferenceRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(notificationService.updatePreference(
            UUID.fromString(principal.getUsername()), req));
    }
}
```

- [ ] **Step 6: Run all notification controller tests — expect PASS**

```bash
./gradlew :api-service:test \
  --tests "com.tinniestudio.api.modules.notification.controller.AdminNotificationTemplateControllerTest" \
  --tests "com.tinniestudio.api.modules.notification.controller.NotificationControllerTest" 2>&1 | tail -20
```

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/notification/controller/ \
        api-service/src/test/java/com/tinniestudio/api/modules/notification/controller/
git commit -m "feat(b15): implement notification controllers (admin templates + user notifications) TDD"
```

---

## Task 7: Analytics Entities + Repository + View Event Publishing

**Files:**
- Create: `shared/entity/ContentAnalyticsDaily.java`
- Create: `shared/entity/ContentAnalyticsDailyId.java`
- Create: `modules/analytics/repository/ContentAnalyticsDailyRepository.java`
- Modify: `modules/content/repository/ContentRepository.java` — add `incrementViewCount`
- Modify: `modules/playback/service/PlaybackServiceImpl.java` — add VIEW_EVENT publish in manifest methods

- [ ] **Step 1: Create ContentAnalyticsDailyId (composite PK)**

```java
package com.tinniestudio.api.shared.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

public class ContentAnalyticsDailyId implements Serializable {
    private UUID contentId;
    private LocalDate analyticsDate;

    public ContentAnalyticsDailyId() {}
    public ContentAnalyticsDailyId(UUID contentId, LocalDate analyticsDate) {
        this.contentId = contentId;
        this.analyticsDate = analyticsDate;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContentAnalyticsDailyId other)) return false;
        return java.util.Objects.equals(contentId, other.contentId)
            && java.util.Objects.equals(analyticsDate, other.analyticsDate);
    }
    @Override public int hashCode() {
        return java.util.Objects.hash(contentId, analyticsDate);
    }
}
```

- [ ] **Step 2: Create ContentAnalyticsDaily entity**

```java
package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "content_analytics_daily")
@IdClass(ContentAnalyticsDailyId.class)
@Getter @Setter @NoArgsConstructor
public class ContentAnalyticsDaily {

    @Id
    @Column(nullable = false)
    private UUID contentId;

    @Id
    @Column(nullable = false)
    private LocalDate analyticsDate;

    @Column(nullable = false)
    private Integer views = 0;

    @Column(nullable = false)
    private Integer uniqueViewers = 0;

    @Column(nullable = false)
    private Integer completions = 0;

    @Column(nullable = false)
    private Long watchTimeSeconds = 0L;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
```

- [ ] **Step 3: Create ContentAnalyticsDailyRepository**

```java
package com.tinniestudio.api.modules.analytics.repository;

import com.tinniestudio.api.shared.entity.ContentAnalyticsDaily;
import com.tinniestudio.api.shared.entity.ContentAnalyticsDailyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface ContentAnalyticsDailyRepository
    extends JpaRepository<ContentAnalyticsDaily, ContentAnalyticsDailyId> {

    List<ContentAnalyticsDaily> findByContentIdAndAnalyticsDateBetweenOrderByAnalyticsDateAsc(
        UUID contentId, LocalDate from, LocalDate to);

    @Query("""
        SELECT c FROM ContentAnalyticsDaily c
        WHERE c.contentId IN :contentIds
        AND c.analyticsDate BETWEEN :from AND :to
        ORDER BY c.analyticsDate ASC
        """)
    List<ContentAnalyticsDaily> findByContentIdsAndDateRange(
        @Param("contentIds") List<UUID> contentIds,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to);

    @Modifying
    @Query(nativeQuery = true, value = """
        INSERT INTO content_analytics_daily
          (content_id, analytics_date, views, watch_time_seconds, created_at, updated_at)
        VALUES (:contentId, :date, 1, :watchTime, now(), now())
        ON CONFLICT (content_id, analytics_date) DO UPDATE SET
          views = content_analytics_daily.views + 1,
          watch_time_seconds = content_analytics_daily.watch_time_seconds + :watchTime,
          updated_at = now()
        """)
    void upsertView(@Param("contentId") UUID contentId,
                    @Param("date") LocalDate date,
                    @Param("watchTime") long watchTime);

    @Modifying
    @Query(nativeQuery = true, value = """
        INSERT INTO content_analytics_daily
          (content_id, analytics_date, completions, created_at, updated_at)
        VALUES (:contentId, :date, 1, now(), now())
        ON CONFLICT (content_id, analytics_date) DO UPDATE SET
          completions = content_analytics_daily.completions + 1,
          updated_at = now()
        """)
    void upsertCompletion(@Param("contentId") UUID contentId,
                          @Param("date") LocalDate date);
}
```

- [ ] **Step 4: Add incrementViewCount to ContentRepository**

In `modules/content/repository/ContentRepository.java`, add:

```java
@Modifying
@Query("UPDATE Content c SET c.viewCount = c.viewCount + 1 WHERE c.id = :id")
void incrementViewCount(@Param("id") UUID id);
```

Make sure `@Param` is imported from `org.springframework.data.repository.query.Param`.

- [ ] **Step 5: Add VIEW_EVENT publishing to PlaybackServiceImpl**

In `PlaybackServiceImpl.java`, in `getContentManifest()`, add after the `buildManifestResponse` line:

```java
// Async view tracking
try {
    rabbitTemplate.convertAndSend(
        com.tinniestudio.api.shared.queue.RabbitConfig.QUEUE_ANALYTICS_INGEST,
        Map.of(
            "type", "VIEW_EVENT",
            "userId", userId.toString(),
            "contentId", contentId.toString(),
            "episodeId", ""
        )
    );
} catch (Exception e) {
    log.warn("View event publish failed (non-critical): {}", e.getMessage());
}
```

Similarly in `getEpisodeManifest()`, add after `buildManifestResponse`:

```java
try {
    rabbitTemplate.convertAndSend(
        com.tinniestudio.api.shared.queue.RabbitConfig.QUEUE_ANALYTICS_INGEST,
        Map.of(
            "type", "VIEW_EVENT",
            "userId", userId.toString(),
            "contentId", contentId.toString(),
            "episodeId", episodeId.toString()
        )
    );
} catch (Exception e) {
    log.warn("View event publish failed (non-critical): {}", e.getMessage());
}
```

Note: `contentId` in `getEpisodeManifest` is the variable set from `episode.getSeason().getContent().getId()`.

- [ ] **Step 6: Compile**

```bash
./gradlew :api-service:compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/ \
        api-service/src/main/java/com/tinniestudio/api/modules/analytics/repository/ \
        api-service/src/main/java/com/tinniestudio/api/modules/content/repository/ContentRepository.java \
        api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java
git commit -m "feat(b16): add analytics entities, repository, and view event publishing"
```

---

## Task 8: Analytics Consumer (TDD)

**Files:**
- Create: `modules/analytics/consumer/AnalyticsConsumer.java`
- Test: `src/test/java/com/tinniestudio/api/modules/analytics/consumer/AnalyticsConsumerTest.java`

Consumes from `analytics.ingest`. Handles:
- `VIEW_EVENT` → increment `content.views_count` + upsert into `content_analytics_daily`
- `PROGRESS_TRACKED` → if completionPercentage >= 90%, upsert completion into `content_analytics_daily`

Anonymous views (userId empty string) are accepted for analytics but not linked to a user.

- [ ] **Step 1: Write failing test**

```java
// AnalyticsConsumerTest.java
package com.tinniestudio.api.modules.analytics.consumer;

import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsDailyRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsConsumerTest {

    @Mock ContentRepository contentRepo;
    @Mock ContentAnalyticsDailyRepository analyticsRepo;
    @InjectMocks AnalyticsConsumer consumer;

    @Test
    void viewEvent_incrementsViewCountAndUpserts() {
        UUID contentId = UUID.randomUUID();
        consumer.handleAnalyticsEvent(Map.of(
            "type", "VIEW_EVENT",
            "userId", UUID.randomUUID().toString(),
            "contentId", contentId.toString(),
            "episodeId", ""
        ));

        verify(contentRepo).incrementViewCount(contentId);
        verify(analyticsRepo).upsertView(eq(contentId), any(), eq(0L));
    }

    @Test
    void progressTracked_upsertCompletion_whenOver90Percent() {
        UUID contentId = UUID.randomUUID();
        consumer.handleAnalyticsEvent(Map.of(
            "type", "PROGRESS_TRACKED",
            "userId", UUID.randomUUID().toString(),
            "contentId", contentId.toString(),
            "episodeId", "",
            "progressSeconds", 91,
            "durationSeconds", 100
        ));

        verify(analyticsRepo).upsertCompletion(eq(contentId), any());
    }

    @Test
    void progressTracked_noCompletion_whenUnder90Percent() {
        UUID contentId = UUID.randomUUID();
        consumer.handleAnalyticsEvent(Map.of(
            "type", "PROGRESS_TRACKED",
            "userId", UUID.randomUUID().toString(),
            "contentId", contentId.toString(),
            "episodeId", "",
            "progressSeconds", 50,
            "durationSeconds", 100
        ));

        verify(analyticsRepo, never()).upsertCompletion(any(), any());
    }

    @Test
    void unknownType_isIgnored() {
        consumer.handleAnalyticsEvent(Map.of("type", "UNKNOWN"));
        verifyNoInteractions(contentRepo, analyticsRepo);
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.analytics.consumer.AnalyticsConsumerTest" 2>&1 | tail -10
```

- [ ] **Step 3: Implement AnalyticsConsumer**

```java
package com.tinniestudio.api.modules.analytics.consumer;

import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsDailyRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.shared.queue.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsConsumer {

    private final ContentRepository contentRepo;
    private final ContentAnalyticsDailyRepository analyticsRepo;

    @RabbitListener(queues = RabbitConfig.QUEUE_ANALYTICS_INGEST)
    @Transactional
    public void handleAnalyticsEvent(Map<String, Object> message) {
        String type = (String) message.get("type");
        try {
            switch (type) {
                case "VIEW_EVENT" -> handleViewEvent(message);
                case "PROGRESS_TRACKED" -> handleProgressTracked(message);
                default -> log.debug("Ignoring analytics event type: {}", type);
            }
        } catch (Exception e) {
            log.error("Error processing analytics event type={}: {}", type, e.getMessage(), e);
        }
    }

    private void handleViewEvent(Map<String, Object> msg) {
        String contentIdStr = (String) msg.get("contentId");
        if (contentIdStr == null || contentIdStr.isBlank()) return;
        UUID contentId = UUID.fromString(contentIdStr);
        contentRepo.incrementViewCount(contentId);
        analyticsRepo.upsertView(contentId, LocalDate.now(), 0L);
    }

    private void handleProgressTracked(Map<String, Object> msg) {
        String contentIdStr = (String) msg.get("contentId");
        if (contentIdStr == null || contentIdStr.isBlank()) return;
        UUID contentId = UUID.fromString(contentIdStr);

        int progress = toInt(msg.get("progressSeconds"));
        int duration = toInt(msg.get("durationSeconds"));
        if (duration > 0 && progress * 100 / duration >= 90) {
            analyticsRepo.upsertCompletion(contentId, LocalDate.now());
        }
    }

    private int toInt(Object val) {
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) return Integer.parseInt(s);
        return 0;
    }
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.analytics.consumer.AnalyticsConsumerTest" 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/analytics/consumer/ \
        api-service/src/test/java/com/tinniestudio/api/modules/analytics/consumer/
git commit -m "feat(b16): implement AnalyticsConsumer for VIEW_EVENT and PROGRESS_TRACKED (TDD)"
```

---

## Task 9: Analytics Service + Controller (TDD)

**Files:**
- Create: `modules/analytics/dto/ContentAnalyticsDailyResponse.java`
- Create: `modules/analytics/dto/PartnerAnalyticsSummaryResponse.java`
- Create: `modules/analytics/service/AnalyticsService.java`
- Create: `modules/analytics/service/AnalyticsServiceImpl.java`
- Create: `modules/analytics/controller/AnalyticsController.java`
- Test: `src/test/java/com/tinniestudio/api/modules/analytics/service/AnalyticsServiceTest.java`
- Test: `src/test/java/com/tinniestudio/api/modules/analytics/controller/AnalyticsControllerTest.java`

Analytics endpoints:
- `GET /analytics/contents/{contentId}?from=&to=&format=json|csv` — content analytics (creator or admin)
- `GET /analytics/partners/me?from=&to=&format=json|csv` — partner's own analytics (aggregated)
- `GET /admin/analytics/contents/{contentId}?from=&to=` — admin view

For CSV: return `ResponseEntity<byte[]>` with `Content-Type: text/csv`.

- [ ] **Step 1: Create DTOs**

```java
// ContentAnalyticsDailyResponse.java
package com.tinniestudio.api.modules.analytics.dto;

import com.tinniestudio.api.shared.entity.ContentAnalyticsDaily;
import java.time.LocalDate;
import java.util.UUID;

public record ContentAnalyticsDailyResponse(
    UUID contentId,
    LocalDate analyticsDate,
    Integer views,
    Integer uniqueViewers,
    Integer completions,
    Long watchTimeSeconds
) {
    public static ContentAnalyticsDailyResponse from(ContentAnalyticsDaily d) {
        return new ContentAnalyticsDailyResponse(
            d.getContentId(), d.getAnalyticsDate(), d.getViews(),
            d.getUniqueViewers(), d.getCompletions(), d.getWatchTimeSeconds()
        );
    }
}
```

```java
// PartnerAnalyticsSummaryResponse.java
package com.tinniestudio.api.modules.analytics.dto;

import java.util.List;
import java.util.UUID;

public record PartnerAnalyticsSummaryResponse(
    UUID partnerId,
    long totalViews,
    long totalCompletions,
    long totalWatchTimeSeconds,
    List<ContentAnalyticsDailyResponse> dailyBreakdown
) {}
```

- [ ] **Step 2: Define AnalyticsService interface**

```java
// AnalyticsService.java
package com.tinniestudio.api.modules.analytics.service;

import com.tinniestudio.api.modules.analytics.dto.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AnalyticsService {
    List<ContentAnalyticsDailyResponse> getContentAnalytics(UUID contentId, LocalDate from, LocalDate to);
    PartnerAnalyticsSummaryResponse getPartnerAnalytics(UUID partnerId, LocalDate from, LocalDate to);
    byte[] exportContentCsv(UUID contentId, LocalDate from, LocalDate to);
    byte[] exportPartnerCsv(UUID partnerId, LocalDate from, LocalDate to);
}
```

- [ ] **Step 3: Write failing service tests**

```java
// AnalyticsServiceTest.java
package com.tinniestudio.api.modules.analytics.service;

import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsDailyRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.shared.entity.ContentAnalyticsDaily;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock ContentAnalyticsDailyRepository analyticsRepo;
    @Mock ContentRepository contentRepo;
    @InjectMocks AnalyticsServiceImpl service;

    @Test
    void getContentAnalytics_returnsRows() {
        UUID contentId = UUID.randomUUID();
        ContentAnalyticsDaily row = new ContentAnalyticsDaily();
        row.setContentId(contentId);
        row.setAnalyticsDate(LocalDate.now());
        row.setViews(10);
        row.setUniqueViewers(8);
        row.setCompletions(5);
        row.setWatchTimeSeconds(1000L);

        when(analyticsRepo.findByContentIdAndAnalyticsDateBetweenOrderByAnalyticsDateAsc(
            any(), any(), any())).thenReturn(List.of(row));

        var result = service.getContentAnalytics(contentId, LocalDate.now().minusDays(7), LocalDate.now());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).views()).isEqualTo(10);
    }

    @Test
    void exportContentCsv_returnsCsvBytes() {
        UUID contentId = UUID.randomUUID();
        ContentAnalyticsDaily row = new ContentAnalyticsDaily();
        row.setContentId(contentId);
        row.setAnalyticsDate(LocalDate.of(2026, 7, 27));
        row.setViews(5);
        row.setUniqueViewers(3);
        row.setCompletions(2);
        row.setWatchTimeSeconds(500L);

        when(analyticsRepo.findByContentIdAndAnalyticsDateBetweenOrderByAnalyticsDateAsc(
            any(), any(), any())).thenReturn(List.of(row));

        byte[] csv = service.exportContentCsv(contentId, LocalDate.now().minusDays(7), LocalDate.now());
        String csvStr = new String(csv);
        assertThat(csvStr).contains("date,views,unique_viewers,completions,watch_time_seconds");
        assertThat(csvStr).contains("2026-07-27");
    }

    @Test
    void getPartnerAnalytics_aggregatesAcrossContent() {
        UUID partnerId = UUID.randomUUID();
        UUID contentId1 = UUID.randomUUID();
        UUID contentId2 = UUID.randomUUID();

        when(contentRepo.findByCreatedByOrderByCreatedAtDesc(any(), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        var result = service.getPartnerAnalytics(partnerId, LocalDate.now().minusDays(7), LocalDate.now());
        assertThat(result.partnerId()).isEqualTo(partnerId);
        assertThat(result.totalViews()).isEqualTo(0L);
    }
}
```

- [ ] **Step 4: Run test — expect FAIL**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.analytics.service.AnalyticsServiceTest" 2>&1 | tail -10
```

- [ ] **Step 5: Implement AnalyticsServiceImpl**

```java
package com.tinniestudio.api.modules.analytics.service;

import com.tinniestudio.api.modules.analytics.dto.*;
import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsDailyRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.shared.entity.ContentAnalyticsDaily;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {

    private final ContentAnalyticsDailyRepository analyticsRepo;
    private final ContentRepository contentRepo;

    @Override
    @Transactional(readOnly = true)
    public List<ContentAnalyticsDailyResponse> getContentAnalytics(UUID contentId, LocalDate from, LocalDate to) {
        return analyticsRepo.findByContentIdAndAnalyticsDateBetweenOrderByAnalyticsDateAsc(contentId, from, to)
            .stream().map(ContentAnalyticsDailyResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PartnerAnalyticsSummaryResponse getPartnerAnalytics(UUID partnerId, LocalDate from, LocalDate to) {
        var contentPage = contentRepo.findByCreatedByOrderByCreatedAtDesc(partnerId, Pageable.unpaged());
        var contentIds = contentPage.getContent().stream()
            .map(c -> c.getId()).toList();

        List<ContentAnalyticsDaily> rows = contentIds.isEmpty()
            ? List.of()
            : analyticsRepo.findByContentIdsAndDateRange(contentIds, from, to);

        long totalViews = rows.stream().mapToLong(r -> r.getViews()).sum();
        long totalCompletions = rows.stream().mapToLong(r -> r.getCompletions()).sum();
        long totalWatchTime = rows.stream().mapToLong(r -> r.getWatchTimeSeconds()).sum();

        List<ContentAnalyticsDailyResponse> daily = rows.stream()
            .map(ContentAnalyticsDailyResponse::from).toList();

        return new PartnerAnalyticsSummaryResponse(partnerId, totalViews, totalCompletions, totalWatchTime, daily);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportContentCsv(UUID contentId, LocalDate from, LocalDate to) {
        List<ContentAnalyticsDailyResponse> rows = getContentAnalytics(contentId, from, to);
        return toCsv(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportPartnerCsv(UUID partnerId, LocalDate from, LocalDate to) {
        var summary = getPartnerAnalytics(partnerId, from, to);
        return toCsv(summary.dailyBreakdown());
    }

    private byte[] toCsv(List<ContentAnalyticsDailyResponse> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("date,views,unique_viewers,completions,watch_time_seconds\n");
        for (var row : rows) {
            sb.append(row.analyticsDate()).append(",")
              .append(row.views()).append(",")
              .append(row.uniqueViewers()).append(",")
              .append(row.completions()).append(",")
              .append(row.watchTimeSeconds()).append("\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 6: Run service tests — expect PASS**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.analytics.service.AnalyticsServiceTest" 2>&1 | tail -10
```

- [ ] **Step 7: Write AnalyticsController test**

```java
// AnalyticsControllerTest.java
package com.tinniestudio.api.modules.analytics.controller;

import com.tinniestudio.api.modules.analytics.dto.*;
import com.tinniestudio.api.modules.analytics.service.AnalyticsService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AnalyticsController.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsControllerTest {

    static final String PARTNER_ID = "00000000-0000-0000-0000-000000000010";

    @Autowired MockMvc mockMvc;
    @MockBean AnalyticsService analyticsService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    @Test
    @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
    void getContentAnalytics_returns200() throws Exception {
        when(analyticsService.getContentAnalytics(any(), any(), any())).thenReturn(List.of());
        mockMvc.perform(get("/analytics/contents/{id}", UUID.randomUUID())
                .param("from", "2026-07-01").param("to", "2026-07-27"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
    void getPartnerAnalytics_returns200() throws Exception {
        when(analyticsService.getPartnerAnalytics(any(), any(), any()))
            .thenReturn(new PartnerAnalyticsSummaryResponse(UUID.fromString(PARTNER_ID), 0L, 0L, 0L, List.of()));
        mockMvc.perform(get("/analytics/partners/me")
                .param("from", "2026-07-01").param("to", "2026-07-27"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalViews").value(0));
    }

    @Test
    @WithMockUser(username = PARTNER_ID, roles = "PARTNER")
    void exportContentCsv_returns200WithCsvContentType() throws Exception {
        when(analyticsService.exportContentCsv(any(), any(), any())).thenReturn("date,views\n".getBytes());
        mockMvc.perform(get("/analytics/contents/{id}", UUID.randomUUID())
                .param("from", "2026-07-01").param("to", "2026-07-27")
                .param("format", "csv"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")));
    }
}
```

- [ ] **Step 8: Implement AnalyticsController**

```java
package com.tinniestudio.api.modules.analytics.controller;

import com.tinniestudio.api.modules.analytics.dto.*;
import com.tinniestudio.api.modules.analytics.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Analytics", description = "Content and partner analytics")
@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Operation(summary = "Get daily analytics for a specific content")
    @GetMapping("/contents/{contentId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PARTNER')")
    public ResponseEntity<?> getContentAnalytics(
            @PathVariable UUID contentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") String format) {
        if ("csv".equalsIgnoreCase(format)) {
            byte[] csv = analyticsService.exportContentCsv(contentId, from, to);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"analytics.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
        }
        return ResponseEntity.ok(analyticsService.getContentAnalytics(contentId, from, to));
    }

    @Operation(summary = "Get partner's own aggregated analytics")
    @GetMapping("/partners/me")
    @PreAuthorize("hasRole('PARTNER')")
    public ResponseEntity<?> getPartnerAnalytics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "json") String format,
            @AuthenticationPrincipal UserDetails principal) {
        UUID partnerId = UUID.fromString(principal.getUsername());
        if ("csv".equalsIgnoreCase(format)) {
            byte[] csv = analyticsService.exportPartnerCsv(partnerId, from, to);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"partner-analytics.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
        }
        return ResponseEntity.ok(analyticsService.getPartnerAnalytics(partnerId, from, to));
    }
}
```

- [ ] **Step 9: Run all analytics tests — expect PASS**

```bash
./gradlew :api-service:test \
  --tests "com.tinniestudio.api.modules.analytics.service.AnalyticsServiceTest" \
  --tests "com.tinniestudio.api.modules.analytics.controller.AnalyticsControllerTest" 2>&1 | tail -20
```

- [ ] **Step 10: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/analytics/ \
        api-service/src/test/java/com/tinniestudio/api/modules/analytics/
git commit -m "feat(b16): implement AnalyticsService and AnalyticsController with CSV export (TDD)"
```

---

## Task 10: Background Jobs with ShedLock (TDD)

**Files:**
- Modify: `api-service/build.gradle` — add ShedLock dependencies
- Create: `shared/entity/JobExecutionLog.java`
- Create: `modules/jobs/repository/JobExecutionLogRepository.java`
- Create: `modules/jobs/JobLogger.java` (helper)
- Create: `modules/jobs/ExpiredUploadSessionJob.java`
- Create: `modules/jobs/StaleVideoAssetJob.java`
- Create: `modules/jobs/FailedVideoAssetJob.java`
- Create: `modules/jobs/ExpiredSessionCleanupJob.java`
- Create: `modules/jobs/NotificationCleanupJob.java`
- Create: `shared/config/SchedulingConfig.java`
- Test: `src/test/java/com/tinniestudio/api/modules/jobs/ExpiredUploadSessionJobTest.java`
- Test: `src/test/java/com/tinniestudio/api/modules/jobs/StaleVideoAssetJobTest.java`

- [ ] **Step 1: Add ShedLock dependencies to build.gradle**

In `api-service/build.gradle`, inside the `dependencies` block, add:

```groovy
implementation 'net.javacrumbs.shedlock:shedlock-spring:6.0.0'
implementation 'net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.0.0'
```

- [ ] **Step 2: Create SchedulingConfig**

```java
package com.tinniestudio.api.shared.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        );
    }
}
```

- [ ] **Step 3: Create JobExecutionLog entity**

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
@Table(name = "job_execution_log")
@Getter @Setter @NoArgsConstructor
public class JobExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String jobName;

    @CreationTimestamp
    private Instant startedAt;

    private Instant finishedAt;

    @Column(nullable = false)
    private String status = "RUNNING";

    @Column(nullable = false)
    private Integer itemsProcessed = 0;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;
}
```

- [ ] **Step 4: Create JobExecutionLogRepository**

```java
package com.tinniestudio.api.modules.jobs.repository;

import com.tinniestudio.api.shared.entity.JobExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface JobExecutionLogRepository extends JpaRepository<JobExecutionLog, UUID> {}
```

- [ ] **Step 5: Create JobLogger helper**

```java
package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.jobs.repository.JobExecutionLogRepository;
import com.tinniestudio.api.shared.entity.JobExecutionLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class JobLogger {

    private final JobExecutionLogRepository logRepo;

    public JobExecutionLog start(String jobName) {
        JobExecutionLog log = new JobExecutionLog();
        log.setJobName(jobName);
        log.setStatus("RUNNING");
        return logRepo.save(log);
    }

    public void success(JobExecutionLog log, int itemsProcessed) {
        log.setStatus("SUCCESS");
        log.setItemsProcessed(itemsProcessed);
        log.setFinishedAt(Instant.now());
        logRepo.save(log);
    }

    public void failed(JobExecutionLog log, String error) {
        log.setStatus("FAILED");
        log.setErrorMessage(error);
        log.setFinishedAt(Instant.now());
        logRepo.save(log);
    }
}
```

- [ ] **Step 6: Add bulk-delete query to UploadSessionRepository**

In `modules/upload/repository/UploadSessionRepository.java`, add:

```java
import com.tinniestudio.api.shared.entity.DomainEnums.UploadStatus;
import java.time.Instant;

@Modifying
@Query("DELETE FROM UploadSession u WHERE u.expiresAt < :now AND u.uploadStatus = :status")
int deleteExpired(@Param("now") Instant now, @Param("status") UploadStatus status);
```

Make sure imports include `@Modifying`, `@Query`, `@Param`.

- [ ] **Step 7: Add queries to VideoAssetRepository for stale/failed recovery**

In `modules/upload/repository/VideoAssetRepository.java`, add:

```java
import java.time.Instant;
import java.util.List;

@Query("SELECT v FROM VideoAsset v WHERE v.processingStatus = :status AND v.updatedAt < :cutoff")
List<VideoAsset> findByProcessingStatusAndUpdatedAtBefore(
    @Param("status") ProcessingStatus status,
    @Param("cutoff") Instant cutoff);

@Modifying
@Query("DELETE FROM VideoAsset v WHERE v.processingStatus = :status AND v.updatedAt < :cutoff")
int deleteByProcessingStatusAndUpdatedAtBefore(
    @Param("status") ProcessingStatus status,
    @Param("cutoff") Instant cutoff);
```

Also add to `modules/user/repository/UserSessionRepository.java` (check if it exists, otherwise check `SessionRepository`):

```java
@Modifying
@Query("DELETE FROM UserSession s WHERE s.expiresAt < :now AND s.revoked = false")
int deleteExpiredSessions(@Param("now") Instant now);
```

First check which repository class handles user sessions:
```bash
find api-service/src/main/java -name "*Session*Repository*" -o -name "*UserSession*" 2>/dev/null
```

- [ ] **Step 8: Add bulk-delete query to NotificationRepository**

Already added `deleteOlderThan` in Task 2 Step 6. Verify it's there:
```java
@Modifying
@Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff")
int deleteOlderThan(@Param("cutoff") Instant cutoff);
```

- [ ] **Step 9: Write failing tests**

```java
// ExpiredUploadSessionJobTest.java
package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.UploadStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExpiredUploadSessionJobTest {

    @Mock UploadSessionRepository uploadSessionRepo;
    @Mock JobLogger jobLogger;
    @InjectMocks ExpiredUploadSessionJob job;

    @Test
    void run_deletesExpiredPendingSessions() {
        var log = new com.tinniestudio.api.shared.entity.JobExecutionLog();
        when(jobLogger.start(any())).thenReturn(log);
        when(uploadSessionRepo.deleteExpired(any(), eq(UploadStatus.PENDING))).thenReturn(3);

        job.run();

        verify(uploadSessionRepo).deleteExpired(any(Instant.class), eq(UploadStatus.PENDING));
        verify(jobLogger).success(log, 3);
    }
}
```

```java
// StaleVideoAssetJobTest.java
package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.admin.service.AuditLogService;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.VideoAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaleVideoAssetJobTest {

    @Mock VideoAssetRepository videoAssetRepo;
    @Mock AuditLogService auditLogService;
    @Mock JobLogger jobLogger;
    @InjectMocks StaleVideoAssetJob job;

    @Test
    void run_marksStaleAssetsAsFailed() {
        VideoAsset stale = new VideoAsset();
        stale.setProcessingStatus(ProcessingStatus.PROCESSING);

        var log = new com.tinniestudio.api.shared.entity.JobExecutionLog();
        when(jobLogger.start(any())).thenReturn(log);
        when(videoAssetRepo.findByProcessingStatusAndUpdatedAtBefore(
            eq(ProcessingStatus.PROCESSING), any())).thenReturn(List.of(stale));
        when(videoAssetRepo.save(any())).thenReturn(stale);

        job.run();

        assertThat(stale.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        verify(auditLogService).log(eq("STALE_VIDEO_ASSET_FAILED"), isNull(), eq("VIDEO_ASSET"), any(), any(), any());
        verify(jobLogger).success(log, 1);
    }
}
```

- [ ] **Step 10: Run tests — expect FAIL**

```bash
./gradlew :api-service:test \
  --tests "com.tinniestudio.api.modules.jobs.ExpiredUploadSessionJobTest" \
  --tests "com.tinniestudio.api.modules.jobs.StaleVideoAssetJobTest" 2>&1 | tail -10
```

- [ ] **Step 11: Implement all jobs**

```java
// ExpiredUploadSessionJob.java
package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.UploadStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredUploadSessionJob {

    private final UploadSessionRepository uploadSessionRepo;
    private final JobLogger jobLogger;

    @Scheduled(cron = "0 0 */6 * * *")
    @SchedulerLock(name = "expiredUploadSessionJob", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    @Transactional
    public void run() {
        var log = jobLogger.start("expiredUploadSessionJob");
        try {
            int deleted = uploadSessionRepo.deleteExpired(Instant.now(), UploadStatus.PENDING);
            jobLogger.success(log, deleted);
            if (deleted > 0) {
                log.info("Deleted {} expired upload sessions", deleted);
            }
        } catch (Exception e) {
            jobLogger.failed(log, e.getMessage());
            log.error("ExpiredUploadSessionJob failed: {}", e.getMessage(), e);
        }
    }
}
```

```java
// StaleVideoAssetJob.java
package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.admin.service.AuditLogService;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.VideoAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StaleVideoAssetJob {

    private final VideoAssetRepository videoAssetRepo;
    private final AuditLogService auditLogService;
    private final JobLogger jobLogger;

    @Scheduled(fixedDelay = 3_600_000) // every hour
    @SchedulerLock(name = "staleVideoAssetJob", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    @Transactional
    public void run() {
        var log = jobLogger.start("staleVideoAssetJob");
        try {
            Instant cutoff = Instant.now().minus(60, ChronoUnit.MINUTES);
            List<VideoAsset> stale = videoAssetRepo
                .findByProcessingStatusAndUpdatedAtBefore(ProcessingStatus.PROCESSING, cutoff);

            for (VideoAsset asset : stale) {
                asset.setProcessingStatus(ProcessingStatus.FAILED);
                videoAssetRepo.save(asset);
                auditLogService.log("STALE_VIDEO_ASSET_FAILED", null, "VIDEO_ASSET",
                    asset.getId(), "Stale after 60 min in PROCESSING", null);
            }
            jobLogger.success(log, stale.size());
        } catch (Exception e) {
            jobLogger.failed(log, e.getMessage());
            log.error("StaleVideoAssetJob failed: {}", e.getMessage(), e);
        }
    }
}
```

```java
// FailedVideoAssetJob.java
package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class FailedVideoAssetJob {

    private final VideoAssetRepository videoAssetRepo;
    private final JobLogger jobLogger;

    @Scheduled(cron = "0 0 2 * * *") // 2am daily
    @SchedulerLock(name = "failedVideoAssetJob", lockAtMostFor = "15m", lockAtLeastFor = "1m")
    @Transactional
    public void run() {
        var log = jobLogger.start("failedVideoAssetJob");
        try {
            Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
            int deleted = videoAssetRepo.deleteByProcessingStatusAndUpdatedAtBefore(
                ProcessingStatus.FAILED, cutoff);
            jobLogger.success(log, deleted);
        } catch (Exception e) {
            jobLogger.failed(log, e.getMessage());
            log.error("FailedVideoAssetJob failed: {}", e.getMessage(), e);
        }
    }
}
```

```java
// NotificationCleanupJob.java
package com.tinniestudio.api.modules.jobs;

import com.tinniestudio.api.modules.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCleanupJob {

    private final NotificationRepository notificationRepo;
    private final JobLogger jobLogger;

    @Scheduled(cron = "0 0 3 * * *") // 3am daily
    @SchedulerLock(name = "notificationCleanupJob", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    @Transactional
    public void run() {
        var log = jobLogger.start("notificationCleanupJob");
        try {
            Instant cutoff = Instant.now().minus(90, ChronoUnit.DAYS);
            int deleted = notificationRepo.deleteOlderThan(cutoff);
            jobLogger.success(log, deleted);
        } catch (Exception e) {
            jobLogger.failed(log, e.getMessage());
            log.error("NotificationCleanupJob failed: {}", e.getMessage(), e);
        }
    }
}
```

For `ExpiredSessionCleanupJob`, first find the session repository (check what class handles `user_sessions`):

```bash
find api-service/src/main/java -name "*Session*Repository*" 2>/dev/null
```

Then implement similarly to the others — delete sessions where `expires_at < now()` (hard delete, since expired sessions are truly useless; the `revoked` flag is set by the session service when revoking).

```java
// ExpiredSessionCleanupJob.java
package com.tinniestudio.api.modules.jobs;

// Import the correct session repository
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredSessionCleanupJob {

    private final JobLogger jobLogger;
    // Inject the session repository — find its type by reading the actual class

    @Scheduled(cron = "0 0 1 * * *") // 1am daily
    @SchedulerLock(name = "expiredSessionCleanupJob", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    @Transactional
    public void run() {
        var log = jobLogger.start("expiredSessionCleanupJob");
        try {
            // Call the delete-expired method on the session repo
            // int deleted = sessionRepo.deleteExpiredSessions(Instant.now());
            // jobLogger.success(log, deleted);
        } catch (Exception e) {
            jobLogger.failed(log, e.getMessage());
            log.error("ExpiredSessionCleanupJob failed: {}", e.getMessage(), e);
        }
    }
}
```

Fill in the session repository injection after reading the actual session repository class.

- [ ] **Step 12: Run tests — expect PASS**

```bash
./gradlew :api-service:test \
  --tests "com.tinniestudio.api.modules.jobs.ExpiredUploadSessionJobTest" \
  --tests "com.tinniestudio.api.modules.jobs.StaleVideoAssetJobTest" 2>&1 | tail -20
```

- [ ] **Step 13: Compile everything**

```bash
./gradlew :api-service:compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 14: Commit**

```bash
git add api-service/build.gradle \
        api-service/src/main/java/com/tinniestudio/api/shared/config/SchedulingConfig.java \
        api-service/src/main/java/com/tinniestudio/api/shared/entity/JobExecutionLog.java \
        api-service/src/main/java/com/tinniestudio/api/modules/jobs/ \
        api-service/src/main/java/com/tinniestudio/api/modules/upload/repository/ \
        api-service/src/test/java/com/tinniestudio/api/modules/jobs/
git commit -m "feat(b17): implement background jobs with ShedLock (session/asset/notification cleanup) TDD"
```

---

## Task 11: Full Test Suite Run

- [ ] **Step 1: Run the full api-service test suite**

```bash
./gradlew :api-service:test 2>&1 | tail -50
```
Expected: all tests pass, no regressions.

- [ ] **Step 2: If any tests fail, investigate and fix**

Common failure patterns:
- `@MockBean` missing for newly-added beans in `@WebMvcTest` tests (e.g., if `SchedulingConfig` imports something that needs mocking)
- `@RabbitListener` annotation on consumers can conflict in `@WebMvcTest` if the consumer is loaded into the test context — use `@MockBean` on the consumer or exclude it from test slices if needed
- If `@EnableScheduling` causes issues in tests, ensure Spring test slices don't pick up `SchedulingConfig` unless needed
- ShedLock configuration issues: `JdbcTemplateLockProvider` needs a `DataSource` — if tests fail because of this, add `@ConditionalOnProperty` guard to `SchedulingConfig` or exclude from test context

To exclude scheduling in tests that don't need it, add to `application-test.properties` (if one exists):
```properties
spring.task.scheduling.enabled=false
```
Or better: use `@MockBean(LockProvider.class)` in integration tests.

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix(b15-17): resolve test suite regressions after batch B implementation"
```

---

## Self-Review Checklist

**Spec coverage:**
- [x] Notification templates with admin CRUD — B15 item 3
- [x] Per-channel notification preferences — B15 item 4
- [x] Separate unread count endpoint `/notifications/unread-count` — B15 item 5
- [x] Preference in notification module — B15 item 6
- [x] Lives on api-service — B15 item 7
- [x] Abstract notification sending (no real email) — B15 item 8
- [x] 90-day cleanup job — B15 item 9
- [x] Consumer updates VideoAsset + sends notification — B15 item 10
- [x] Full migrations V39-V41 — B15 item 1
- [x] Semantic event types (enum) — B15 item 2
- [x] `views_count` incremented async via RabbitMQ consumer — B16 item 1
- [x] `PROGRESS_TRACKED` published from playback (already done) — B16 item 2
- [x] Partner analytics at `/analytics/partners/me` (flexible endpoint) — B16 item 3+8
- [x] Payment table not needed for views analytics — B16 item 4
- [x] Daily granularity (one point per calendar day) — B16 item 6
- [x] Anonymous tracking via user_id (null-safe) — B16 item 7
- [x] Read from `content_analytics_daily` (stale up to 1 hour via consumer lag) — B16 item 9
- [x] CSV export via `?format=csv` — B16 item 10
- [x] ShedLock distributed locking — B17 item 1
- [x] Expired upload sessions: DB-only delete — B17 item 2
- [x] `sent_at` and `retry_count` on notifications table — B17 item 3
- [x] Stale PROCESSING VideoAssets after 60 min → FAILED — B17 item 4
- [x] FAILED VideoAssets deleted after 7 days — B17 item 5
- [x] Stale recovery writes to audit_log — B17 item 6
- [x] Expired token cleanup job for user_sessions — B17 item 7
- [x] `job_execution_log` table + JobLogger — B17 item 8

**Not in scope:**
- Real email delivery (abstract/stub) — will be wired in infrastructure phase
- Push notification channel (future)
- Unique viewer counting (requires identity resolution, deferred)
