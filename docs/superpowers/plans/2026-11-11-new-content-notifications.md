# New-Content Notifications Broadcast Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Broadcast a notification to every opted-in user when a title is published or a new season/episode is added to an already-published show, delivered via both in-app and real email per each user's own channel preference — plus an unrelated but small addendum fixing episode-select trailer-swap/scroll behavior on two content-detail pages.

**Architecture:** Part 1 (backend-heavy) generalizes the existing single-recipient notification pipeline to support multiple templates per event (one per channel) and real email sending, adds two new event types fired from the content/season/episode publish paths, and fans them out to every user via an async RabbitMQ consumer with an idempotency guard. Part 2 (frontend-only, independent) fixes two small, unrelated UI bugs on the same content-detail surface.

**Tech Stack:** Spring Boot / Java / JPA / Flyway / RabbitMQ (server), Next.js / React / TypeScript (tinniestudio-client-web), JUnit 5 + Mockito (backend tests), manual + `tsc --noEmit` + `eslint` (frontend — no test framework in this repo).

**Spec:** `docs/superpowers/specs/2026-11-11-new-content-notifications-design.md`

---

## Part 1 — Notification Broadcast Infrastructure

### Task 1: Migration + entity — one template per (event_type, channel)

**Files:**
- Create: `server/api-service/src/main/resources/db/migration/V61__notification_templates_channel_unique.sql`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/shared/entity/NotificationTemplate.java`

- [ ] **Step 1: Write the migration**

```sql
-- V61__notification_templates_channel_unique.sql

-- Was UNIQUE on event_type alone, so an event type could only ever have one template row
-- with one channel. Moving to (event_type, channel) lets an event type have up to two active
-- rows — one IN_APP, one EMAIL — each with independent copy, needed so a single event can
-- deliver via both channels with different wording (email can be longer/more formal).
ALTER TABLE notification_templates DROP CONSTRAINT IF EXISTS notification_templates_event_type_key;
ALTER TABLE notification_templates ADD CONSTRAINT notification_templates_event_type_channel_key
    UNIQUE (event_type, channel);
```

- [ ] **Step 2: Update the entity to match**

In `NotificationTemplate.java`, replace:

```java
@Entity
@Table(name = "notification_templates")
@Getter @Setter @NoArgsConstructor
public class NotificationTemplate extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private NotificationEventType eventType;
```

with:

```java
@Entity
@Table(
    name = "notification_templates",
    uniqueConstraints = @UniqueConstraint(columnNames = {"event_type", "channel"})
)
@Getter @Setter @NoArgsConstructor
public class NotificationTemplate extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationEventType eventType;
```

(No other field in this file changes. `ddl-auto: none` in `application.yml` means Hibernate never actually enforces this annotation as DDL — Flyway's `V61` migration above is what makes the constraint real — but the entity annotation should still accurately describe what's enforced, not lie about a single-column uniqueness that no longer exists.)

- [ ] **Step 3: Run the full test module to confirm nothing broke**

Run from `/home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server`:

```bash
./gradlew :api-service:test
```

Expected: same pass/fail counts as before this change (this step only changes a constraint definition, no behavior yet — later tasks will exercise it).

- [ ] **Step 4: Commit**

```bash
git add api-service/src/main/resources/db/migration/V61__notification_templates_channel_unique.sql api-service/src/main/java/com/tinniestudio/api/shared/entity/NotificationTemplate.java
git commit -m "feat: allow one notification template per (event_type, channel)"
```

---

### Task 2: Repository + admin CRUD validation for the new constraint

**Files:**
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/notification/repository/NotificationTemplateRepository.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/notification/service/NotificationTemplateServiceImpl.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/modules/notification/service/NotificationTemplateServiceTest.java`

- [ ] **Step 1: Write the failing tests**

In `NotificationTemplateServiceTest.java`, replace the existing `create_throwsBadRequest_whenEventTypeAlreadyExists` test:

```java
    @Test
    void create_throwsBadRequest_whenEventTypeAlreadyExists() {
        var req = new CreateNotificationTemplateRequest();
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setTitleTemplate("T"); req.setBodyTemplate("B");
        req.setChannel(NotificationChannel.IN_APP);

        when(templateRepo.existsByEventType(NotificationEventType.CONTENT_PROCESSED)).thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(com.tinniestudio.api.shared.exception.BadRequestException.class);
    }
```

with:

```java
    @Test
    void create_throwsBadRequest_whenSameEventTypeAndChannelAlreadyExists() {
        var req = new CreateNotificationTemplateRequest();
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setTitleTemplate("T"); req.setBodyTemplate("B");
        req.setChannel(NotificationChannel.IN_APP);

        when(templateRepo.existsByEventTypeAndChannel(NotificationEventType.CONTENT_PROCESSED, NotificationChannel.IN_APP))
            .thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(com.tinniestudio.api.shared.exception.BadRequestException.class);
    }

    @Test
    void create_allows_whenSameEventTypeButDifferentChannel() {
        var req = new CreateNotificationTemplateRequest();
        req.setEventType(NotificationEventType.CONTENT_PROCESSED);
        req.setTitleTemplate("T"); req.setBodyTemplate("B");
        req.setChannel(NotificationChannel.EMAIL);

        when(templateRepo.existsByEventTypeAndChannel(NotificationEventType.CONTENT_PROCESSED, NotificationChannel.EMAIL))
            .thenReturn(false);
        when(templateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(req);
        assertThat(result.eventType()).isEqualTo("CONTENT_PROCESSED");
        verify(templateRepo).save(any(NotificationTemplate.class));
    }
```

(The other existing tests in this file — `create_savesTemplate`, `update_throwsNotFound_whenMissing`, `list_returnsAll`, `delete_throwsNotFound_whenMissing` — are unaffected and stay exactly as they are. `create_savesTemplate`'s existing `when(templateRepo.existsByEventType(...))` stub will no longer be called by the implementation after Step 3 below, but since it's a `when(...).thenReturn(false)` stub on an unrelated mock method that's simply never invoked, Mockito's default `lenient` behavior for unused stubs in this test class — confirmed by the class already relying on default strictness elsewhere without issue — will not fail the test; update it anyway for clarity:)

Replace in `create_savesTemplate`:

```java
        when(templateRepo.existsByEventType(NotificationEventType.CONTENT_PROCESSED)).thenReturn(false);
```

with:

```java
        when(templateRepo.existsByEventTypeAndChannel(NotificationEventType.CONTENT_PROCESSED, NotificationChannel.IN_APP))
            .thenReturn(false);
```

- [ ] **Step 2: Run tests to verify they fail**

Run from `/home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server`:

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.service.NotificationTemplateServiceTest"
```

Expected: FAIL — `existsByEventTypeAndChannel` doesn't exist yet on the repository interface (compile error) or the two new tests fail since the service still calls the old method.

- [ ] **Step 3: Add the repository method**

In `NotificationTemplateRepository.java`, replace:

```java
@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {
    Optional<NotificationTemplate> findByEventTypeAndIsActiveTrue(NotificationEventType eventType);
    boolean existsByEventType(NotificationEventType eventType);
}
```

with:

```java
@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {
    Optional<NotificationTemplate> findByEventTypeAndIsActiveTrue(NotificationEventType eventType);
    List<NotificationTemplate> findAllByEventTypeAndIsActiveTrue(NotificationEventType eventType);
    boolean existsByEventTypeAndChannel(NotificationEventType eventType, NotificationChannel channel);
}
```

(`findByEventTypeAndIsActiveTrue` stays — it's about to be removed from `NotificationServiceImpl` in Task 3, but keeping it on the repository for this task avoids an intermediate broken state; Task 3 will delete it once nothing calls it. Add the needed imports: `import com.tinniestudio.api.shared.entity.DomainEnums.NotificationChannel;` and `import java.util.List;` alongside the existing `NotificationEventType` import.)

- [ ] **Step 4: Update the service to use the new validation**

In `NotificationTemplateServiceImpl.java`, replace:

```java
    @Override
    @Transactional
    public NotificationTemplateResponse create(CreateNotificationTemplateRequest req) {
        if (templateRepo.existsByEventType(req.getEventType())) {
            throw new BadRequestException("Template for event type already exists: " + req.getEventType());
        }
```

with:

```java
    @Override
    @Transactional
    public NotificationTemplateResponse create(CreateNotificationTemplateRequest req) {
        if (templateRepo.existsByEventTypeAndChannel(req.getEventType(), req.getChannel())) {
            throw new BadRequestException(
                "Template for event type " + req.getEventType() + " on channel " + req.getChannel() + " already exists");
        }
```

(No other method in this file changes.)

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.service.NotificationTemplateServiceTest"
```

Expected: PASS — all tests, including the two new ones.

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/notification/repository/NotificationTemplateRepository.java api-service/src/main/java/com/tinniestudio/api/modules/notification/service/NotificationTemplateServiceImpl.java api-service/src/test/java/com/tinniestudio/api/modules/notification/service/NotificationTemplateServiceTest.java
git commit -m "feat: validate template uniqueness per (event_type, channel), not event_type alone"
```

---

### Task 3: Generalize `sendNotification()` — multi-channel, placeholders, real email

**Files:**
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/notification/service/NotificationService.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/notification/service/NotificationServiceImpl.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/modules/notification/service/NotificationServiceTest.java`

This is the central change: `sendNotification` currently looks up exactly one template and always creates an in-app `Notification` row (even for a template whose `channel` is `EMAIL` — it just never actually sent anything, since no code path dispatched to `ResendEmailService`). After this task: it loops over every active template for the event type (zero, one, or two rows), checks that user's preference independently per channel, and for `IN_APP` creates a `Notification` row exactly as before; for `EMAIL` sends a real email via `ResendEmailService` and does **not** create a `Notification` row (an email-channel template's copy is a different, parallel message, not something that should also silently appear in the in-app bell list under the in-app template's old behavior of "create a row regardless of channel"). It also adds `{placeholder}` substitution in title/body, needed so a broadcast notification can say e.g. "*The Last Horizon* is now available" instead of static boilerplate.

- [ ] **Step 1: Write the failing tests — full replacement of the test file**

Replace the entire contents of `NotificationServiceTest.java` with:

```java
package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.UpdatePreferenceRequest;
import com.tinniestudio.api.modules.notification.repository.*;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.email.EmailRequest;
import com.tinniestudio.api.shared.email.ResendEmailService;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
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
    @Mock UserRepository userRepository;
    @Mock ResendEmailService resendEmailService;
    @InjectMocks NotificationServiceImpl service;

    private NotificationTemplate inAppTemplate() {
        NotificationTemplate t = new NotificationTemplate();
        t.setEventType(NotificationEventType.CONTENT_PROCESSED);
        t.setTitleTemplate("Ready");
        t.setBodyTemplate("Your content is ready");
        t.setChannel(NotificationChannel.IN_APP);
        t.setIsActive(true);
        return t;
    }

    private NotificationTemplate emailTemplate() {
        NotificationTemplate t = new NotificationTemplate();
        t.setEventType(NotificationEventType.CONTENT_PROCESSED);
        t.setTitleTemplate("Your content is ready");
        t.setBodyTemplate("Hi — your content has finished processing.");
        t.setChannel(NotificationChannel.EMAIL);
        t.setIsActive(true);
        return t;
    }

    @Test
    void sendNotification_createsNotification_whenInAppTemplateExists() {
        UUID userId = UUID.randomUUID();
        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate()));
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getIsRead()).isFalse();
        verifyNoInteractions(resendEmailService);
    }

    @Test
    void sendNotification_skips_whenNoTemplate() {
        when(templateRepo.findAllByEventTypeAndIsActiveTrue(any())).thenReturn(List.of());
        service.sendNotification(UUID.randomUUID(), NotificationEventType.CONTENT_PROCESSED, null, null);
        verify(notificationRepo, never()).save(any());
        verifyNoInteractions(resendEmailService);
    }

    @Test
    void sendNotification_suppressed_whenPreferenceDisabled() {
        UUID userId = UUID.randomUUID();
        NotificationPreference pref = new NotificationPreference();
        pref.setUserId(userId);
        pref.setChannel(NotificationChannel.IN_APP);
        pref.setEventType(NotificationEventType.CONTENT_PROCESSED);
        pref.setIsEnabled(false);

        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate()));
        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.IN_APP, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.of(pref));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        verify(notificationRepo, never()).save(any());
    }

    @Test
    void sendNotification_creates_whenNoPreferenceRow() {
        UUID userId = UUID.randomUUID();
        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate()));
        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.IN_APP, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.empty());
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        verify(notificationRepo).save(any());
    }

    @Test
    void sendNotification_creates_whenPreferenceExplicitlyEnabled() {
        UUID userId = UUID.randomUUID();
        NotificationPreference pref = new NotificationPreference();
        pref.setUserId(userId);
        pref.setChannel(NotificationChannel.IN_APP);
        pref.setEventType(NotificationEventType.CONTENT_PROCESSED);
        pref.setIsEnabled(true);

        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate()));
        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.IN_APP, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.of(pref));
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        verify(notificationRepo).save(any());
    }

    @Test
    void sendNotification_sendsRealEmail_forEmailChannelTemplate_andDoesNotCreateInAppRow() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setEmail("viewer@example.com");

        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(emailTemplate()));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        ArgumentCaptor<EmailRequest> captor = ArgumentCaptor.forClass(EmailRequest.class);
        verify(resendEmailService).sendEmail(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("viewer@example.com");
        assertThat(captor.getValue().subject()).isEqualTo("Your content is ready");
        verify(notificationRepo, never()).save(any());
    }

    @Test
    void sendNotification_deliversBothChannels_whenBothTemplatesActiveAndBothEnabled() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setEmail("viewer@example.com");

        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate(), emailTemplate()));
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        verify(notificationRepo).save(any());
        verify(resendEmailService).sendEmail(any());
    }

    @Test
    void sendNotification_onlyEmail_whenInAppDisabledButEmailEnabled() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setEmail("viewer@example.com");

        NotificationPreference inAppDisabled = new NotificationPreference();
        inAppDisabled.setUserId(userId);
        inAppDisabled.setChannel(NotificationChannel.IN_APP);
        inAppDisabled.setEventType(NotificationEventType.CONTENT_PROCESSED);
        inAppDisabled.setIsEnabled(false);

        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate(), emailTemplate()));
        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.IN_APP, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.of(inAppDisabled));
        when(preferenceRepo.findByUserIdAndChannelAndEventType(userId,
            NotificationChannel.EMAIL, NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        verify(notificationRepo, never()).save(any());
        verify(resendEmailService).sendEmail(any());
    }

    @Test
    void sendNotification_substitutesTitlePlaceholder_inBothTitleAndBody() {
        UUID userId = UUID.randomUUID();
        NotificationTemplate template = new NotificationTemplate();
        template.setEventType(NotificationEventType.CONTENT_PROCESSED);
        template.setTitleTemplate("{title} is now available");
        template.setBodyTemplate("Check out {title} today!");
        template.setChannel(NotificationChannel.IN_APP);
        template.setIsActive(true);

        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(template));
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID(),
            Map.of("title", "The Last Horizon"));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("The Last Horizon is now available");
        assertThat(captor.getValue().getBody()).isEqualTo("Check out The Last Horizon today!");
    }

    @Test
    void sendNotification_fourArgOverload_behavesAsNoPlaceholders() {
        UUID userId = UUID.randomUUID();
        when(templateRepo.findAllByEventTypeAndIsActiveTrue(NotificationEventType.CONTENT_PROCESSED))
            .thenReturn(List.of(inAppTemplate()));
        when(notificationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sendNotification(userId, NotificationEventType.CONTENT_PROCESSED, "CONTENT", UUID.randomUUID());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Ready");
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

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.service.NotificationServiceTest"
```

Expected: FAIL to compile (the 5-arg `sendNotification` overload and `UserRepository`/`ResendEmailService` fields don't exist on `NotificationServiceImpl` yet).

- [ ] **Step 3: Add the new interface method**

In `NotificationService.java`, replace:

```java
public interface NotificationService {
    void sendNotification(UUID userId, NotificationEventType eventType,
                          String referenceType, UUID referenceId);
```

with:

```java
public interface NotificationService {
    void sendNotification(UUID userId, NotificationEventType eventType,
                          String referenceType, UUID referenceId);
    void sendNotification(UUID userId, NotificationEventType eventType,
                          String referenceType, UUID referenceId, java.util.Map<String, String> placeholders);
```

- [ ] **Step 4: Rewrite `NotificationServiceImpl`**

Replace the entire contents of `NotificationServiceImpl.java` with:

```java
package com.tinniestudio.api.modules.notification.service;

import com.tinniestudio.api.modules.notification.dto.*;
import com.tinniestudio.api.modules.notification.repository.*;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.email.EmailRequest;
import com.tinniestudio.api.shared.email.ResendEmailService;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepo;
    private final NotificationTemplateRepository templateRepo;
    private final NotificationPreferenceRepository preferenceRepo;
    private final UserRepository userRepository;
    private final ResendEmailService resendEmailService;

    @Override
    @Transactional
    public void sendNotification(UUID userId, NotificationEventType eventType,
                                  String referenceType, UUID referenceId) {
        sendNotification(userId, eventType, referenceType, referenceId, Map.of());
    }

    @Override
    @Transactional
    public void sendNotification(UUID userId, NotificationEventType eventType,
                                  String referenceType, UUID referenceId,
                                  Map<String, String> placeholders) {
        List<NotificationTemplate> templates = templateRepo.findAllByEventTypeAndIsActiveTrue(eventType);
        if (templates.isEmpty()) {
            log.debug("No active template for event type: {}. Skipping.", eventType);
            return;
        }

        for (NotificationTemplate template : templates) {
            boolean suppressed = preferenceRepo
                .findByUserIdAndChannelAndEventType(userId, template.getChannel(), eventType)
                .map(pref -> !pref.getIsEnabled())
                .orElse(false);
            if (suppressed) {
                log.debug("User {} has disabled event type {} on channel {}. Skipping.",
                    userId, eventType, template.getChannel());
                continue;
            }

            String title = applyPlaceholders(template.getTitleTemplate(), placeholders);
            String body = applyPlaceholders(template.getBodyTemplate(), placeholders);

            if (template.getChannel() == NotificationChannel.EMAIL) {
                // A real email send, not an in-app row — an EMAIL-channel template is a distinct
                // message with its own copy, not something that should also silently appear in
                // the in-app bell list the way the old (never-actually-sending) code did.
                sendEmailNotification(userId, title, body);
            } else {
                Notification n = new Notification();
                n.setUserId(userId);
                n.setEventType(eventType);
                n.setTitle(title);
                n.setBody(body);
                n.setChannel(template.getChannel());
                n.setReferenceType(referenceType);
                n.setReferenceId(referenceId);
                n.setSentAt(Instant.now());
                notificationRepo.save(n);
            }
        }
    }

    private String applyPlaceholders(String text, Map<String, String> placeholders) {
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private void sendEmailNotification(UUID userId, String subject, String body) {
        userRepository.findById(userId).ifPresent(user ->
            resendEmailService.sendEmail(new EmailRequest(user.getEmail(), subject, body))
        );
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

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.service.NotificationServiceTest"
```

Expected: PASS — all tests.

- [ ] **Step 6: Run the full test module to check for unrelated regressions**

```bash
./gradlew :api-service:test
```

Expected: no new failures outside `NotificationServiceTest` (callers of the original 4-arg `sendNotification` — `NotificationConsumer`, `PartnerApplicationServiceImpl` — are unaffected since that overload still exists and now just delegates).

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/notification/service/NotificationService.java api-service/src/main/java/com/tinniestudio/api/modules/notification/service/NotificationServiceImpl.java api-service/src/test/java/com/tinniestudio/api/modules/notification/service/NotificationServiceTest.java
git commit -m "feat: generalize sendNotification to multi-channel templates with placeholders and real email"
```

---

### Task 4: New event types + lightweight user-id projection query

**Files:**
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/user/repository/UserRepository.java`

- [ ] **Step 1: Add the two new event types**

In `DomainEnums.java`, replace:

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
```

with:

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

- [ ] **Step 2: Add the lightweight projection query**

In `UserRepository.java`, add `import java.util.List;` alongside the existing `import java.util.Optional;`, then add this method to the interface (anywhere inside the interface body, e.g. directly after `existsByEmailAndDeletedAtIsNull`):

```java
    /**
     * Every non-deleted user's id, for fan-out broadcasts (e.g. new-content notifications) —
     * a lightweight projection, not full User entities, since a broadcast audience can be the
     * entire user base. Deliberately does not additionally filter on accountStatus: a
     * suspended/banned account still has notification preferences that should be honored the
     * same as any other account, and this spec's own audience rule is "every user who hasn't
     * opted out," not "every user in good standing."
     */
    @Query("SELECT u.id FROM User u WHERE u.deletedAt IS NULL")
    List<UUID> findAllActiveUserIds();
```

- [ ] **Step 3: Run the full test module**

```bash
./gradlew :api-service:test
```

Expected: same pass/fail counts as before this task (nothing calls either new addition yet).

- [ ] **Step 4: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java api-service/src/main/java/com/tinniestudio/api/modules/user/repository/UserRepository.java
git commit -m "feat: add CONTENT_PUBLISHED/EPISODE_PUBLISHED event types and active-user-id projection"
```

---

### Task 5: `ContentService.transitionStatus` publishes `CONTENT_PUBLISHED`

**Files:**
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/content/service/ContentService.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/modules/content/service/ContentServiceTest.java`

- [ ] **Step 1: Write the failing tests**

In `ContentServiceTest.java`, inside the `TransitionStatusTests` nested class, replace the existing `processingToPublishedSetsPublishedAt` test:

```java
        @Test
        @DisplayName("PROCESSING to PUBLISHED sets publishedAt")
        void processingToPublishedSetsPublishedAt() {
            content.setStatus(ContentStatus.PROCESSING);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.transitionStatus(contentId, ContentStatus.PUBLISHED, null);

            assertThat(result.status()).isEqualTo("PUBLISHED");
            assertThat(result.publishedAt()).isNotNull();
        }
```

with:

```java
        @Test
        @DisplayName("PROCESSING to PUBLISHED sets publishedAt and publishes a CONTENT_PUBLISHED broadcast")
        void processingToPublishedSetsPublishedAtAndPublishesBroadcast() {
            content.setStatus(ContentStatus.PROCESSING);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            ContentResponse result = contentService.transitionStatus(contentId, ContentStatus.PUBLISHED, null);

            assertThat(result.status()).isEqualTo("PUBLISHED");
            assertThat(result.publishedAt()).isNotNull();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.QUEUE_NOTIFICATIONS), captor.capture());
            assertThat(captor.getValue()).containsEntry("type", "CONTENT_PUBLISHED");
            assertThat(captor.getValue()).containsEntry("referenceType", "CONTENT");
            assertThat(captor.getValue()).containsEntry("referenceId", contentId.toString());
        }

        @Test
        @DisplayName("DRAFT to REVIEW does not publish a CONTENT_PUBLISHED broadcast")
        void draftToReviewDoesNotPublishBroadcast() {
            content.setStatus(ContentStatus.DRAFT);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
            when(contentRepository.save(any(Content.class))).thenAnswer(inv -> inv.getArgument(0));

            contentService.transitionStatus(contentId, ContentStatus.REVIEW, null);

            verify(rabbitTemplate, never()).convertAndSend(eq(RabbitConfig.QUEUE_NOTIFICATIONS), any(Map.class));
        }
```

Add these imports to the top of the test file if not already present: `import org.mockito.ArgumentCaptor;` and `import static org.mockito.ArgumentMatchers.eq;` (check the existing import block first — this file already imports `any` via `import static org.mockito.ArgumentMatchers.any;`, confirm `ArgumentCaptor`/`eq` aren't already imported before adding, to avoid a duplicate-import compile error).

- [ ] **Step 2: Run tests to verify they fail**

Run from `/home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server`:

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.content.service.ContentServiceTest"
```

Expected: FAIL — `processingToPublishedSetsPublishedAtAndPublishesBroadcast` fails because no publish call happens yet.

- [ ] **Step 3: Add the publish call**

In `ContentService.java`, replace:

```java
    @Transactional
    @CacheEvict(value = {"content-list", "content-detail", "discover"}, allEntries = true)
    public ContentResponse transitionStatus(UUID id, ContentStatus targetStatus, String rejectionReason) {
        Content content = contentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + id));
        validateTransition(content.getStatus(), targetStatus);
        content.setStatus(targetStatus);
        if (targetStatus == ContentStatus.PUBLISHED && content.getPublishedAt() == null) {
            content.setPublishedAt(Instant.now());
        }
        content.setRejectionReason(targetStatus == ContentStatus.REJECTED ? rejectionReason : null);
        return ContentResponse.from(contentRepository.save(content));
    }
```

with:

```java
    @Transactional
    @CacheEvict(value = {"content-list", "content-detail", "discover"}, allEntries = true)
    public ContentResponse transitionStatus(UUID id, ContentStatus targetStatus, String rejectionReason) {
        Content content = contentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + id));
        validateTransition(content.getStatus(), targetStatus);
        content.setStatus(targetStatus);
        if (targetStatus == ContentStatus.PUBLISHED && content.getPublishedAt() == null) {
            content.setPublishedAt(Instant.now());
        }
        content.setRejectionReason(targetStatus == ContentStatus.REJECTED ? rejectionReason : null);
        ContentResponse response = ContentResponse.from(contentRepository.save(content));
        if (targetStatus == ContentStatus.PUBLISHED) {
            publishContentPublishedBroadcast(content);
        }
        return response;
    }

    /**
     * Best-effort, same pattern as recordView's analytics publish in this same class: a queue
     * publish failure must never fail the admin's publish action itself.
     */
    private void publishContentPublishedBroadcast(Content content) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.QUEUE_NOTIFICATIONS, Map.of(
                "type", "CONTENT_PUBLISHED",
                "contentTitle", content.getTitle(),
                "referenceType", "CONTENT",
                "referenceId", content.getId().toString()
            ));
        } catch (Exception e) {
            log.warn("CONTENT_PUBLISHED broadcast publish failed (non-critical): {}", e.getMessage());
        }
    }
```

(`rabbitTemplate`, `RabbitConfig`, `Map`, and `log` are all already fields/imports in this class — used by the existing `recordView` method. No new imports needed.)

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.content.service.ContentServiceTest"
```

Expected: PASS — all tests, including the two new/changed ones.

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/content/service/ContentService.java api-service/src/test/java/com/tinniestudio/api/modules/content/service/ContentServiceTest.java
git commit -m "feat: publish CONTENT_PUBLISHED broadcast when content transitions to PUBLISHED"
```

---

### Task 6: `SeasonService`/`EpisodeService` publish `EPISODE_PUBLISHED`

**Files:**
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/season/service/SeasonService.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/episode/service/EpisodeService.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/modules/season/service/SeasonServiceTest.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/modules/episode/service/EpisodeServiceTest.java`

- [ ] **Step 1: Write the failing test for `SeasonService`**

In `SeasonServiceTest.java`, add `@Mock private RabbitTemplate rabbitTemplate;` to the mock field list (alongside `seasonRepository`/`contentRepository`), and add this import: `import org.springframework.amqp.rabbit.core.RabbitTemplate;`. Then, inside the nested test class containing `adminCanCreateOnAnyContent` (the `create()` tests), add:

```java
        @Test
        @DisplayName("publishes EPISODE_PUBLISHED when the parent content is already PUBLISHED")
        void publishesEpisodePublishedWhenParentAlreadyPublished() {
            seriesContent.setStatus(ContentStatus.PUBLISHED);
            CreateSeasonRequest req = new CreateSeasonRequest(1, "Season One", null, null, null, null);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));
            when(seasonRepository.existsByContentIdAndSeasonNumber(contentId, 1)).thenReturn(false);
            when(seasonRepository.saveAndFlush(any(Season.class))).thenAnswer(inv -> {
                Season s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                s.setEpisodes(new ArrayList<>());
                return s;
            });

            seasonService.create(contentId, req, ownerPrincipal());

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(rabbitTemplate).convertAndSend(
                eq(com.tinniestudio.api.shared.queue.RabbitConfig.QUEUE_NOTIFICATIONS), captor.capture());
            assertThat(captor.getValue()).containsEntry("type", "EPISODE_PUBLISHED");
            assertThat(captor.getValue()).containsEntry("referenceId", contentId.toString());
        }

        @Test
        @DisplayName("does not publish EPISODE_PUBLISHED when the parent content is not yet PUBLISHED")
        void doesNotPublishWhenParentNotYetPublished() {
            // seriesContent defaults to DRAFT in setUp() — unchanged here.
            CreateSeasonRequest req = new CreateSeasonRequest(1, "Season One", null, null, null, null);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));
            when(seasonRepository.existsByContentIdAndSeasonNumber(contentId, 1)).thenReturn(false);
            when(seasonRepository.saveAndFlush(any(Season.class))).thenAnswer(inv -> {
                Season s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                s.setEpisodes(new ArrayList<>());
                return s;
            });

            seasonService.create(contentId, req, ownerPrincipal());

            verifyNoInteractions(rabbitTemplate);
        }
```

Add `import static org.mockito.ArgumentMatchers.eq;` to the test file's imports if not already present (check first — this file currently imports only `any` via `import static org.mockito.ArgumentMatchers.any;`).

- [ ] **Step 2: Write the failing test for `EpisodeService`**

In `EpisodeServiceTest.java`, add `@Mock private RabbitTemplate rabbitTemplate;` to the mock field list and the same `RabbitTemplate` import. Then, inside the `CreateTests` nested class, add:

```java
        @Test
        @DisplayName("publishes EPISODE_PUBLISHED when the parent content is already PUBLISHED")
        void publishesEpisodePublishedWhenParentAlreadyPublished() {
            content.setStatus(ContentStatus.PUBLISHED);
            CreateEpisodeRequest req = new CreateEpisodeRequest(null, "New Episode", null, null, 2400, null);
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.findMaxEpisodeNumberBySeasonId(seasonId)).thenReturn(Optional.of(2));
            when(episodeRepository.saveAndFlush(any(Episode.class))).thenAnswer(inv -> {
                Episode ep = inv.getArgument(0);
                ep.setId(UUID.randomUUID());
                return ep;
            });

            episodeService.create(seasonId, req, ownerPrincipal());

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(rabbitTemplate).convertAndSend(
                eq(com.tinniestudio.api.shared.queue.RabbitConfig.QUEUE_NOTIFICATIONS), captor.capture());
            assertThat(captor.getValue()).containsEntry("type", "EPISODE_PUBLISHED");
            assertThat(captor.getValue()).containsEntry("referenceId", content.getId().toString());
        }

        @Test
        @DisplayName("does not publish EPISODE_PUBLISHED when the parent content is not yet PUBLISHED")
        void doesNotPublishWhenParentNotYetPublished() {
            // content defaults to DRAFT in setUp() — unchanged here.
            CreateEpisodeRequest req = new CreateEpisodeRequest(null, "New Episode", null, null, 2400, null);
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.findMaxEpisodeNumberBySeasonId(seasonId)).thenReturn(Optional.of(2));
            when(episodeRepository.saveAndFlush(any(Episode.class))).thenAnswer(inv -> {
                Episode ep = inv.getArgument(0);
                ep.setId(UUID.randomUUID());
                return ep;
            });

            episodeService.create(seasonId, req, ownerPrincipal());

            verifyNoInteractions(rabbitTemplate);
        }
```

Add the same `eq` static import if not already present.

- [ ] **Step 3: Run both test classes to verify they fail**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.season.service.SeasonServiceTest" --tests "com.tinniestudio.api.modules.episode.service.EpisodeServiceTest"
```

Expected: FAIL to compile (`RabbitTemplate` isn't injected into either service yet).

- [ ] **Step 4: Update `SeasonService`**

In `SeasonService.java`, add these imports alongside the existing ones:

```java
import com.tinniestudio.api.shared.queue.RabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;
```

Add `@Slf4j` above the class declaration (alongside the existing `@Service`):

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class SeasonService {

    private final SeasonRepository seasonRepository;
    private final ContentRepository contentRepository;
    private final RabbitTemplate rabbitTemplate;
```

Replace the `create()` method's save block:

```java
        try {
            // saveAndFlush: see ContentService.create for why plain save() doesn't reliably
            // surface the constraint violation inside this try/catch.
            return SeasonResponse.from(seasonRepository.saveAndFlush(season));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Season number " + seasonNumber + " already exists for this content");
        }
    }
```

with:

```java
        try {
            // saveAndFlush: see ContentService.create for why plain save() doesn't reliably
            // surface the constraint violation inside this try/catch.
            Season saved = seasonRepository.saveAndFlush(season);
            if (content.getStatus() == ContentStatus.PUBLISHED) {
                publishEpisodePublishedBroadcast(content);
            }
            return SeasonResponse.from(saved);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Season number " + seasonNumber + " already exists for this content");
        }
    }

    /**
     * Best-effort, same non-critical-publish-failure pattern used in ContentService.
     */
    private void publishEpisodePublishedBroadcast(Content content) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.QUEUE_NOTIFICATIONS, Map.of(
                "type", "EPISODE_PUBLISHED",
                "contentTitle", content.getTitle(),
                "referenceType", "CONTENT",
                "referenceId", content.getId().toString()
            ));
        } catch (Exception e) {
            log.warn("EPISODE_PUBLISHED broadcast publish failed (non-critical): {}", e.getMessage());
        }
    }
```

- [ ] **Step 5: Update `EpisodeService`**

In `EpisodeService.java`, add the same imports:

```java
import com.tinniestudio.api.shared.queue.RabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;
```

Add `@Slf4j` and the new field:

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class EpisodeService {

    private final EpisodeRepository episodeRepository;
    private final SeasonRepository seasonRepository;
    private final RabbitTemplate rabbitTemplate;
```

Replace the `create()` method's save block:

```java
        try {
            // saveAndFlush: see ContentService.create for why plain save() doesn't reliably
            // surface the constraint violation inside this try/catch.
            return EpisodeResponse.from(episodeRepository.saveAndFlush(episode));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Episode number " + episodeNumber + " already exists in this season");
        }
    }
```

with:

```java
        try {
            // saveAndFlush: see ContentService.create for why plain save() doesn't reliably
            // surface the constraint violation inside this try/catch.
            Episode saved = episodeRepository.saveAndFlush(episode);
            if (season.getContent().getStatus() == ContentStatus.PUBLISHED) {
                publishEpisodePublishedBroadcast(season.getContent());
            }
            return EpisodeResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Episode number " + episodeNumber + " already exists in this season");
        }
    }

    /**
     * Best-effort, same non-critical-publish-failure pattern used in ContentService.
     */
    private void publishEpisodePublishedBroadcast(Content content) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.QUEUE_NOTIFICATIONS, Map.of(
                "type", "EPISODE_PUBLISHED",
                "contentTitle", content.getTitle(),
                "referenceType", "CONTENT",
                "referenceId", content.getId().toString()
            ));
        } catch (Exception e) {
            log.warn("EPISODE_PUBLISHED broadcast publish failed (non-critical): {}", e.getMessage());
        }
    }
```

- [ ] **Step 6: Run both test classes to verify they pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.season.service.SeasonServiceTest" --tests "com.tinniestudio.api.modules.episode.service.EpisodeServiceTest"
```

Expected: PASS — all tests in both classes.

- [ ] **Step 7: Run the full test module to check for unrelated regressions**

```bash
./gradlew :api-service:test
```

Expected: no new failures.

- [ ] **Step 8: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/season/service/SeasonService.java api-service/src/main/java/com/tinniestudio/api/modules/episode/service/EpisodeService.java api-service/src/test/java/com/tinniestudio/api/modules/season/service/SeasonServiceTest.java api-service/src/test/java/com/tinniestudio/api/modules/episode/service/EpisodeServiceTest.java
git commit -m "feat: publish EPISODE_PUBLISHED when a season/episode is added to an already-published show"
```

---

### Task 7: Consumer — fan-out broadcast with idempotency

**Files:**
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumer.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumerTest.java`

This is the only place a new `@RabbitListener` method could seem tempting but must NOT be added separately: Spring AMQP treats each `@RabbitListener(queues = ...)` as an independent competing consumer on that queue, so two listener methods both bound to `QUEUE_NOTIFICATIONS` would each receive only some of the messages (whichever happens to win the race), not all of them. The existing single listener method must stay the single entry point, dispatching internally.

- [ ] **Step 1: Write the failing tests**

Add these tests to `NotificationConsumerTest.java` (after the existing tests, before the closing `}`):

```java
    @Test
    @DisplayName("fans a CONTENT_PUBLISHED broadcast out to every active user")
    void handleContentPublished_fansOutToAllUsers() {
        UUID contentId = UUID.randomUUID();
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        when(cacheService.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(userRepository.findAllActiveUserIds()).thenReturn(List.of(user1, user2));

        consumer.handleNotificationEvent(Map.of(
            "type", "CONTENT_PUBLISHED",
            "contentTitle", "The Last Horizon",
            "referenceType", "CONTENT",
            "referenceId", contentId.toString()
        ));

        verify(notificationService).sendNotification(user1, NotificationEventType.CONTENT_PUBLISHED,
            "CONTENT", contentId, Map.of("title", "The Last Horizon"));
        verify(notificationService).sendNotification(user2, NotificationEventType.CONTENT_PUBLISHED,
            "CONTENT", contentId, Map.of("title", "The Last Horizon"));
    }

    @Test
    @DisplayName("fans an EPISODE_PUBLISHED broadcast out the same way as CONTENT_PUBLISHED")
    void handleEpisodePublished_fansOutToAllUsers() {
        UUID contentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(cacheService.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(userRepository.findAllActiveUserIds()).thenReturn(List.of(userId));

        consumer.handleNotificationEvent(Map.of(
            "type", "EPISODE_PUBLISHED",
            "contentTitle", "Breaking Bad",
            "referenceType", "CONTENT",
            "referenceId", contentId.toString()
        ));

        verify(notificationService).sendNotification(userId, NotificationEventType.EPISODE_PUBLISHED,
            "CONTENT", contentId, Map.of("title", "Breaking Bad"));
    }

    @Test
    @DisplayName("redelivery of the same broadcast is a no-op (idempotency lock)")
    void handleContentPublished_redelivery_isNoOp() {
        UUID contentId = UUID.randomUUID();

        when(cacheService.setIfAbsent(any(), any(), any())).thenReturn(false);

        consumer.handleNotificationEvent(Map.of(
            "type", "CONTENT_PUBLISHED",
            "contentTitle", "The Last Horizon",
            "referenceType", "CONTENT",
            "referenceId", contentId.toString()
        ));

        verifyNoInteractions(userRepository, notificationService);
    }

    @Test
    @DisplayName("one failing user does not stop the rest of the broadcast fan-out")
    void handleContentPublished_oneUserFailureDoesNotAbortBroadcast() {
        UUID contentId = UUID.randomUUID();
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        when(cacheService.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(userRepository.findAllActiveUserIds()).thenReturn(List.of(user1, user2));
        doThrow(new RuntimeException("boom")).when(notificationService)
            .sendNotification(eq(user1), any(), any(), any(), any());

        consumer.handleNotificationEvent(Map.of(
            "type", "CONTENT_PUBLISHED",
            "contentTitle", "The Last Horizon",
            "referenceType", "CONTENT",
            "referenceId", contentId.toString()
        ));

        verify(notificationService).sendNotification(user2, NotificationEventType.CONTENT_PUBLISHED,
            "CONTENT", contentId, Map.of("title", "The Last Horizon"));
    }
```

Update the test file's mock field list to add two new mocks, and the imports to match:

```java
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.cache.CacheService;
```

```java
    @Mock NotificationService notificationService;
    @Mock VideoAssetRepository videoAssetRepo;
    @Mock ContentRepository contentRepo;
    @Mock VideoActivationService videoActivationService;
    @Mock UserRepository userRepository;
    @Mock CacheService cacheService;
    @InjectMocks NotificationConsumer consumer;
```

Also add `import java.util.List;` and `import static org.mockito.Mockito.doThrow;` if not already covered by the existing wildcard `import static org.mockito.Mockito.*;` (check first — this file already has that wildcard import, so `doThrow` is already available; `List` needs adding alongside the existing `Map`/`Optional`/`UUID` imports).

- [ ] **Step 2: Run tests to verify they fail**

Run from `/home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server`:

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.consumer.NotificationConsumerTest"
```

Expected: FAIL to compile (`UserRepository`/`CacheService` aren't injected into `NotificationConsumer` yet, and it doesn't handle these two event types).

- [ ] **Step 3: Rewrite `NotificationConsumer`**

Replace the entire contents of `NotificationConsumer.java` with:

```java
package com.tinniestudio.api.modules.notification.consumer;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.notification.service.NotificationService;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.modules.upload.service.VideoActivationService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.cache.CacheService;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.queue.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    // Not released after processing (unlike a mutual-exclusion job lock) — this is a dedup/seen
    // marker, not a concurrency lock. A given (eventType, referenceId) is only ever meant to be
    // broadcast once, ever, so the lock should simply outlive any realistic redelivery window
    // rather than being deleted in a finally block.
    private static final Duration BROADCAST_DEDUP_TTL = Duration.ofHours(24);

    private final NotificationService notificationService;
    private final VideoAssetRepository videoAssetRepo;
    private final ContentRepository contentRepo;
    private final VideoActivationService videoActivationService;
    private final UserRepository userRepository;
    private final CacheService cacheService;

    @RabbitListener(queues = RabbitConfig.QUEUE_NOTIFICATIONS)
    public void handleNotificationEvent(Map<String, Object> message) {
        String type = (String) message.get("type");
        switch (type) {
            case "CONTENT_PROCESSED" -> handleContentProcessed(message);
            case "CONTENT_PUBLISHED" -> handleBroadcast(message, NotificationEventType.CONTENT_PUBLISHED);
            case "EPISODE_PUBLISHED" -> handleBroadcast(message, NotificationEventType.EPISODE_PUBLISHED);
            default -> log.debug("Ignoring notification event type: {}", type);
        }
    }

    private void handleContentProcessed(Map<String, Object> message) {
        try {
            UUID assetId = UUID.fromString((String) message.get("videoAssetId"));
            UUID contentId = UUID.fromString((String) message.get("contentId"));
            String status = (String) message.get("status");

            VideoAsset asset = videoAssetRepo.findById(assetId).orElse(null);
            if (asset == null) {
                log.warn("VideoAsset not found: {}", assetId);
                return;
            }
            boolean succeeded = "READY".equals(status);
            asset.setProcessingStatus(succeeded ? ProcessingStatus.READY : ProcessingStatus.FAILED);
            if (succeeded && asset.getContent() != null) {
                // Auto-retire: this asset becomes "the" active one for its (content, assetType)
                // pair, every sibling that was previously active gets flipped off. Guarded on
                // asset.getContent() != null because a video processed before B6 (video-linking
                // fix) landed may have no content link at all — nothing to retire against, and
                // no isActive semantics apply to an unlinked asset.
                //
                // Delegated to VideoActivationService so the sibling-retire UPDATE and this
                // asset's own save() commit in one transaction — done inline in the same method
                // (no enclosing @Transactional here), a crash between the two writes could
                // otherwise leave zero active assets for this (content, assetType) pair.
                // processingStatus was already set above on this same in-memory entity, so the
                // service's own save() persists both fields together — no separate save needed.
                videoActivationService.activateAndRetireSiblings(asset);
            } else {
                if (succeeded) {
                    log.warn("VideoAsset {} processed successfully but has no content link " +
                            "(pre-B6 legacy data?) — cannot activate", assetId);
                }
                videoAssetRepo.save(asset);
            }

            Content content = contentRepo.findById(contentId).orElse(null);
            if (content == null) {
                log.warn("Content not found for notification: {}", contentId);
                return;
            }
            // CONTENT, not VIDEO_ASSET: the notification is about a piece of content finishing
            // processing, and the frontend has a content page to deep-link to — it has no
            // video-asset detail view. contentId was already resolved above (it's how we found
            // the recipient), it just wasn't what got sent as the reference before this fix.
            //
            // Known limitation: media-worker's publisher (VideoProcessingService) only includes a
            // real contentId in this message when VideoAsset.contentId is set, which nothing in
            // the upload-completion path populates yet (see UploadService.completeSession) — so
            // in practice this branch is rarely reached today; contentRepo.findById(contentId)
            // above returns empty and we return early instead. That's the video-upload-to-content
            // linking gap (tracked separately, not fixed here) — this fix is still correct for the
            // day that gap closes, and is a strict improvement over the old VIDEO_ASSET reference,
            // which could never deep-link correctly even with a valid id.
            notificationService.sendNotification(
                content.getCreatedBy(),
                NotificationEventType.CONTENT_PROCESSED,
                "CONTENT",
                contentId
            );
        } catch (Exception e) {
            log.error("Error processing CONTENT_PROCESSED notification: {}", e.getMessage(), e);
        }
    }

    private void handleBroadcast(Map<String, Object> message, NotificationEventType eventType) {
        try {
            String contentTitle = (String) message.get("contentTitle");
            String referenceType = (String) message.get("referenceType");
            UUID referenceId = UUID.fromString((String) message.get("referenceId"));

            String lockKey = "tinnie:lock:notif-broadcast:" + eventType + ":" + referenceId;
            if (!cacheService.setIfAbsent(lockKey, "sent", BROADCAST_DEDUP_TTL)) {
                log.debug("Broadcast for {} {} already processed, skipping redelivery", eventType, referenceId);
                return;
            }

            List<UUID> userIds = userRepository.findAllActiveUserIds();
            Map<String, String> placeholders = Map.of("title", contentTitle);
            for (UUID userId : userIds) {
                try {
                    notificationService.sendNotification(userId, eventType, referenceType, referenceId, placeholders);
                } catch (Exception e) {
                    log.warn("Broadcast notification failed for user {} event {}: {}", userId, eventType, e.getMessage());
                }
            }
            log.info("Broadcast {} for {} {} sent to {} users", eventType, referenceType, referenceId, userIds.size());
        } catch (Exception e) {
            log.error("Error processing {} broadcast: {}", eventType, e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.notification.consumer.NotificationConsumerTest"
```

Expected: PASS — all tests, including the existing `CONTENT_PROCESSED` ones (unchanged behavior, now routed through `handleContentProcessed`) and the four new ones.

- [ ] **Step 5: Run the full test module to check for unrelated regressions**

```bash
./gradlew :api-service:test
```

Expected: no new failures anywhere in the module.

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumer.java api-service/src/test/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumerTest.java
git commit -m "feat: fan CONTENT_PUBLISHED/EPISODE_PUBLISHED broadcasts out to all users, idempotently"
```

---

## Part 2 — Episode-select trailer swap + scroll addendum

Independent of Part 1 — a small, unrelated UX fix on the content-detail pages, surfaced during the same brainstorming session. No backend changes.

### Task 8: `ContentDetail.tsx` — trailer actually swaps per episode, plus scroll-into-view

**Files:**
- Modify: `tinniestudio-client-web/src/components/content/ContentDetail.tsx`

- [ ] **Step 1: Add a ref for the hero wrapper and parameterize the trailer hook**

Replace:

```tsx
export default function ContentDetail({
  data,
  relatedContents,
  returnTo,
  subscription = null,
}: ContentDetailProps) {
  const { user, isAuthenticated, isLoading: isAuthLoading } = useAuth();
  const [activeSeason, setActiveSeason] = useState<Season | null>(
    data.seasons?.[0] ?? null
  );
  const [activeEpisode, setActiveEpisode] = useState<Episode | null>(
    data.seasons?.[0]?.episodes?.[0] ?? null
  );
  const { isInList, toggle: handleAddToList } = useToggleFavorite(data.id);
  const userSubscription = subscription ?? user?.subscription ?? null;

  const { asset: heroAsset } = useTrailerManifest({ contentId: data.id });

  const handleEpisodeSelect = (ep: Episode) => {
    setActiveEpisode(ep);
  };
```

with:

```tsx
export default function ContentDetail({
  data,
  relatedContents,
  returnTo,
  subscription = null,
}: ContentDetailProps) {
  const { user, isAuthenticated, isLoading: isAuthLoading } = useAuth();
  const [activeSeason, setActiveSeason] = useState<Season | null>(
    data.seasons?.[0] ?? null
  );
  const [activeEpisode, setActiveEpisode] = useState<Episode | null>(
    data.seasons?.[0]?.episodes?.[0] ?? null
  );
  const { isInList, toggle: handleAddToList } = useToggleFavorite(data.id);
  const userSubscription = subscription ?? user?.subscription ?? null;
  const heroRef = useRef<HTMLDivElement>(null);

  const { asset: heroAsset } = useTrailerManifest({
    episodeId: activeEpisode?.id,
    seasonId: activeSeason?.id,
    contentId: data.id,
  });

  const handleEpisodeSelect = (ep: Episode) => {
    setActiveEpisode(ep);
    heroRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
```

Add `useRef` to the existing React import:

```tsx
import React, { useState } from 'react';
```

becomes:

```tsx
import React, { useRef, useState } from 'react';
```

- [ ] **Step 2: Attach the ref to the hero wrapper**

Replace:

```tsx
      <div className='relative w-full bg-black border-b border-white/5 shadow-2xl overflow-hidden'>
```

with:

```tsx
      <div ref={heroRef} className='relative w-full bg-black border-b border-white/5 shadow-2xl overflow-hidden'>
```

(This is the hero wrapper div, the topmost element returned by the component — not the inner `aspect-video` div one level down.)

- [ ] **Step 3: Type-check and lint**

Run from `/home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web`:

```bash
npm run type-check && npm run lint
```

Expected: both PASS with no new errors on this file. Note: this file has unrelated, pre-existing uncommitted changes on this branch — `git diff` before staging to confirm your edit sits alongside (not conflicting with) that other work, and stage only your own hunks (`git add -p`) rather than the whole file if the unrelated change is still present and uncommitted at the time you do this task.

- [ ] **Step 4: Manual verification**

Visit a movie/kids/lives-categorized title that has seasons/episodes (structurally allowed regardless of category), scroll down to the episode list, click a different episode. Confirm the hero trailer actually changes (previously it never did on this page) and the page scrolls back up to reveal it.

- [ ] **Step 5: Commit**

```bash
git add src/components/content/ContentDetail.tsx
git commit -m "fix: make episode selection swap the hero trailer and scroll it into view"
```

---

### Task 9: `SeasonContentDetail.tsx` — scroll-into-view (trailer swap already works here)

**Files:**
- Modify: `tinniestudio-client-web/src/components/content/SeasonContentDetail.tsx`

- [ ] **Step 1: Add a ref and scroll call**

Replace:

```tsx
  const { isInList, toggle: handleAddToList } = useToggleFavorite(data.id);
  const { user, isAuthenticated, isLoading: isAuthLoading } = useAuth();
  const userSubscription = subscription ?? user?.subscription ?? null;

  const { asset: heroAsset } = useTrailerManifest({
    episodeId: activeEpisode?.id,
    seasonId: activeSeason?.id,
    contentId: data.id,
  });

  const handleEpisodeSelect = (ep: Episode) => {
    setActiveEpisode(ep);
    if (activeSeason) {
      router.push(`${basePath}/${data.slug}/${activeSeason.slug}/${ep.slug}`, {
        scroll: false,
      });
    }
  };
```

with:

```tsx
  const { isInList, toggle: handleAddToList } = useToggleFavorite(data.id);
  const { user, isAuthenticated, isLoading: isAuthLoading } = useAuth();
  const userSubscription = subscription ?? user?.subscription ?? null;
  const heroRef = useRef<HTMLDivElement>(null);

  const { asset: heroAsset } = useTrailerManifest({
    episodeId: activeEpisode?.id,
    seasonId: activeSeason?.id,
    contentId: data.id,
  });

  const handleEpisodeSelect = (ep: Episode) => {
    setActiveEpisode(ep);
    if (activeSeason) {
      router.push(`${basePath}/${data.slug}/${activeSeason.slug}/${ep.slug}`, {
        scroll: false,
      });
    }
    heroRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
```

(`scroll: false` on the `router.push` stays exactly as-is — it suppresses Next.js's own scroll-to-top behavior for this soft navigation, deliberately, so it doesn't fight with the explicit `scrollIntoView` call, which is now the sole scroll mechanism here.)

Add `useRef` to the existing React import, same as Task 8:

```tsx
import React, { useState } from 'react';
```

becomes:

```tsx
import React, { useRef, useState } from 'react';
```

- [ ] **Step 2: Attach the ref to the hero wrapper**

Replace:

```tsx
      <div className='relative w-full aspect-video max-h-[80vh] min-h-100 bg-black border-b border-white/5 shadow-2xl'>
```

with:

```tsx
      <div ref={heroRef} className='relative w-full aspect-video max-h-[80vh] min-h-100 bg-black border-b border-white/5 shadow-2xl'>
```

- [ ] **Step 3: Type-check and lint**

```bash
npm run type-check && npm run lint
```

Expected: both PASS with no new errors.

- [ ] **Step 4: Manual verification**

Visit a show/sermon title, scroll down to the episode list, click a different episode. Confirm the trailer already changed before this fix (unchanged) and now the page also scrolls back up to reveal it.

- [ ] **Step 5: Commit**

```bash
git add src/components/content/SeasonContentDetail.tsx
git commit -m "fix: scroll the hero trailer into view on episode selection"
```

---

## Self-Review Notes

**Spec coverage:** Trigger points (Task 5, 6), async fan-out with idempotency (Task 7), template schema change (Task 1), multi-channel + placeholder + real email generalization (Task 3), admin CRUD validation update (Task 2), new event types + audience query (Task 4), episode deep-link (no client change needed, per spec — confirmed `NotificationBell.tsx`'s existing `CONTENT` case already covers both new event types since both use `referenceType=CONTENT`), and the addendum (Tasks 8, 9) are all covered.

**Pre-flight check carried from the spec, not a task in this plan**: before shipping, audit any existing `EMAIL`-channel `notification_templates` rows in production — generalizing real sending means any of them would start actually emailing for the first time. This is an operational step, not code, so it isn't a task here, but don't skip it at rollout.

**Scope note**: the `AdminNotificationTemplateController`/DTO layer (`CreateNotificationTemplateRequest`, etc.) needed no changes beyond Task 2's validation fix — the existing create/update/list/delete endpoints already pass a single `channel` through per request, which is exactly what creating a second (event_type, channel) row requires; no new "multi-row" endpoint shape was needed.
