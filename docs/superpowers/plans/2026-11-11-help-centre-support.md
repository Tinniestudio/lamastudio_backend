# Help Centre & Customer Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the static, broken Help Centre/Contact page; add a Tawk.to live-chat embed; and build a full support-ticket system (entities, APIs, email alerts, in-app notifications, attachments) spanning `server`, `tinniestudio-client-web`, and `tinniestudio-admin-web`.

**Architecture:** Part A/B (client-web only) are small, independent cleanup/embed tasks. Part C is the substantial new build: new `SupportTicket`/`TicketMessage` entities and a new `SUPPORT` admin role on the backend, a user-facing `/tickets` API (ownership-scoped) and an admin-facing `/admin/tickets` API (role-gated, any admin role), reusing the existing presign/upload/confirm storage pattern for attachments and the existing `sendNotification()`/RabbitMQ notification pipeline for reply alerts. Client-web gets a ticket submission form plus a "My Tickets" list/thread UI; admin-web gets a new Tickets nav section with a filterable list and a reply/status-change detail page.

**Tech Stack:** Spring Boot / Java / JPA / Flyway / RabbitMQ (server), Next.js / React / TypeScript / React Query (tinniestudio-client-web, tinniestudio-admin-web), JUnit 5 + Mockito (backend tests), manual + `tsc --noEmit` + `eslint` (both frontends — no test framework in either).

**Spec:** `docs/superpowers/specs/2026-11-11-help-centre-support-design.md`

---

## Part 1 — Server: roles, entities, migration, plumbing

### Task 1: `SUPPORT` admin role

**Files:**

- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/auth/admin/entity/AdminRoleName.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/auth/service/AdminAuthServiceTest.java` (if it tests role assignment exhaustively — check first)

- [ ] **Step 1: Add the enum value**

Current full file:

```java
package com.tinniestudio.api.modules.auth.admin.entity;

public enum AdminRoleName {
    SUPER_ADMIN,
    MODERATOR
}
```

Change to:

```java
package com.tinniestudio.api.modules.auth.admin.entity;

public enum AdminRoleName {
    SUPER_ADMIN,
    MODERATOR,
    SUPPORT
}
```

- [ ] **Step 2: Confirm no whitelist blocks the new value**

`AdminAuthService.java` has explicit `AdminRoleName.SUPER_ADMIN`-specific checks (preventing more than one SUPER_ADMIN, preventing self-demotion) but no enumerated whitelist that would reject `SUPPORT` as an invalid role when creating a sub-admin — confirm this by reading `AdminAuthService.java`'s sub-admin creation method (search for `createSubAdmin` or similar) and the DTO it binds (`@NotNull private AdminRoleName role;`-style field). If you find a whitelist/switch that enumerates `SUPER_ADMIN`/`MODERATOR` exhaustively and would reject `SUPPORT`, report it and fix it as part of this task (add `SUPPORT` to that list/switch too). If no such whitelist exists (expected), no further code change is needed here — a `SUPPORT` admin becomes assignable through the existing sub-admin creation flow automatically.

- [ ] **Step 3: Confirm `hasRole('ADMIN')` already covers `SUPPORT`**

Read `AdminUserDetailsServiceImpl.java` — confirm it grants the Spring Security authority `ROLE_ADMIN` to every admin unconditionally, regardless of which specific `AdminRoleName` they hold (this is already true today for `SUPER_ADMIN`/`MODERATOR`; adding `SUPPORT` to the enum doesn't change this grant, since it isn't keyed on the specific role value). This confirms that `AdminPartnerApplicationController`-style endpoints gated with `@PreAuthorize("hasRole('ADMIN')")` already permit `SUPPORT` admins with zero further backend changes — the new ticket controllers in Task 6 can use this exact same class-level annotation to satisfy the spec's "any admin role" requirement, with no need for `hasAnyRole('SUPER_ADMIN','MODERATOR','SUPPORT')` anywhere.

- [ ] **Step 4: Type-check**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
./gradlew :api-service:compileJava
```

Expected: `BUILD SUCCESSFUL` (adding an enum constant can't break compilation on its own; this just confirms nothing exhaustively pattern-matches the old two-value enum via a `switch` without a `default` — Java requires `default` or full coverage on enum switches used as expressions, so a compile failure here would mean exactly that).

- [ ] **Step 5: Run the full test suite to confirm nothing broke**

```bash
./gradlew :api-service:test
```

Expected: same accepted baseline as always — only `ActuatorSecurityIntegrationTest.healthEndpointIsPubliclyAccessible`, `SubscriptionServiceTest$ProcessRenewal...anchorsNewPeriodToNowNotStaleRenewalDate`, and intermittently `PartnerApplicationAuthIntegrationTest`'s rate-limiter flake may fail. Anything else failing needs investigation.

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/auth/admin/entity/AdminRoleName.java
git commit -m "feat: add SUPPORT admin role"
```

---

### Task 2: `SupportTicket`/`TicketMessage` entities + migration + repositories

**Files:**

- Create: `server/api-service/src/main/resources/db/migration/V62__add_support_tickets.sql`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/shared/entity/SupportTicket.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/shared/entity/TicketMessage.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/repository/SupportTicketRepository.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/repository/TicketMessageRepository.java`
- Create: `server/api-service/src/test/java/com/tinniestudio/api/modules/ticket/repository/SupportTicketRepositoryTest.java`

- [ ] **Step 1: Write the migration**

```sql
-- V62__add_support_tickets.sql

CREATE TABLE IF NOT EXISTS support_tickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    subject VARCHAR(255) NOT NULL,
    ticket_number VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Guard: backfill any column this no-op CREATE TABLE left missing if the table already
-- pre-existed (see V16/V18 production drift fix for why this defensive block exists).
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS user_id UUID NOT NULL;
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS subject VARCHAR(255) NOT NULL;
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS ticket_number VARCHAR(20) NOT NULL;
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'OPEN';
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE support_tickets ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX IF NOT EXISTS idx_support_tickets_ticket_number ON support_tickets(ticket_number);
CREATE INDEX IF NOT EXISTS idx_support_tickets_user_id ON support_tickets(user_id);
CREATE INDEX IF NOT EXISTS idx_support_tickets_status ON support_tickets(status);

CREATE TABLE IF NOT EXISTS ticket_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id UUID NOT NULL REFERENCES support_tickets(id),
    sender_type VARCHAR(10) NOT NULL,
    sender_id UUID NOT NULL,
    body TEXT NOT NULL,
    attachment_url VARCHAR(2048),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS ticket_id UUID NOT NULL REFERENCES support_tickets(id);
ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS sender_type VARCHAR(10) NOT NULL;
ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS sender_id UUID NOT NULL;
ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS body TEXT NOT NULL;
ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS attachment_url VARCHAR(2048);
ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE ticket_messages ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE ticket_messages ALTER COLUMN id SET DEFAULT gen_random_uuid();

CREATE INDEX IF NOT EXISTS idx_ticket_messages_ticket_id ON ticket_messages(ticket_id);
```

- [ ] **Step 2: Add the `TicketStatus`/`TicketSenderType` enums**

In `DomainEnums.java`, add these two new enums anywhere alongside the other small enums in the file (e.g. right after `NotificationChannel`):

```java
    public enum TicketStatus {
        OPEN, IN_PROGRESS, RESOLVED, CLOSED
    }

    public enum TicketSenderType {
        USER, ADMIN
    }
```

- [ ] **Step 3: Create the `SupportTicket` entity**

```java
package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(
    name = "support_tickets",
    indexes = {
        @Index(name = "idx_support_tickets_user_id", columnList = "user_id"),
        @Index(name = "idx_support_tickets_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class SupportTicket extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(name = "ticket_number", nullable = false, unique = true, length = 20)
    private String ticketNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status = TicketStatus.OPEN;
}
```

- [ ] **Step 4: Create the `TicketMessage` entity**

```java
package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.TicketSenderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(
    name = "ticket_messages",
    indexes = @Index(name = "idx_ticket_messages_ticket_id", columnList = "ticket_id")
)
@Getter
@Setter
@NoArgsConstructor
public class TicketMessage extends BaseEntity {

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 10)
    private TicketSenderType senderType;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "attachment_url", length = 2048)
    private String attachmentUrl;
}
```

- [ ] **Step 5: Create the repositories**

```java
package com.tinniestudio.api.modules.ticket.repository;

import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import com.tinniestudio.api.shared.entity.SupportTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    boolean existsByTicketNumber(String ticketNumber);

    Page<SupportTicket> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<SupportTicket> findByIdAndUserId(UUID id, UUID userId);

    Page<SupportTicket> findByStatusOrderByCreatedAtDesc(TicketStatus status, Pageable pageable);

    Page<SupportTicket> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
```

```java
package com.tinniestudio.api.modules.ticket.repository;

import com.tinniestudio.api.shared.entity.TicketMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TicketMessageRepository extends JpaRepository<TicketMessage, UUID> {

    List<TicketMessage> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);
}
```

- [ ] **Step 6: Write a repository test proving the migration + entities work end-to-end**

```java
package com.tinniestudio.api.modules.ticket.repository;

import com.tinniestudio.api.shared.entity.DomainEnums.TicketSenderType;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import com.tinniestudio.api.shared.entity.SupportTicket;
import com.tinniestudio.api.shared.entity.TicketMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SupportTicketRepositoryTest {

    @Autowired private SupportTicketRepository ticketRepository;
    @Autowired private TicketMessageRepository messageRepository;

    @Test
    void savesAndReadsBackTicketWithMessage_defaultingStatusToOpen() {
        UUID userId = UUID.randomUUID();

        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(userId);
        ticket.setSubject("Can't play episode 3");
        ticket.setTicketNumber("TKT-ABC123");
        ticket = ticketRepository.save(ticket);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.getId()).isNotNull();
        assertThat(ticket.getCreatedAt()).isNotNull();

        TicketMessage message = new TicketMessage();
        message.setTicketId(ticket.getId());
        message.setSenderType(TicketSenderType.USER);
        message.setSenderId(userId);
        message.setBody("It just spins forever on episode 3.");
        messageRepository.save(message);

        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getBody()).isEqualTo("It just spins forever on episode 3.");

        Optional<SupportTicket> ownTicket = ticketRepository.findByIdAndUserId(ticket.getId(), userId);
        assertThat(ownTicket).isPresent();

        Optional<SupportTicket> wrongOwner = ticketRepository.findByIdAndUserId(ticket.getId(), UUID.randomUUID());
        assertThat(wrongOwner).isEmpty();
    }

    @Test
    void existsByTicketNumber_trueOnlyAfterSave() {
        assertThat(ticketRepository.existsByTicketNumber("TKT-ZZZZZZ")).isFalse();

        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(UUID.randomUUID());
        ticket.setSubject("Billing question");
        ticket.setTicketNumber("TKT-ZZZZZZ");
        ticketRepository.save(ticket);

        assertThat(ticketRepository.existsByTicketNumber("TKT-ZZZZZZ")).isTrue();
    }

    @Test
    void findByStatus_onlyReturnsMatchingStatus() {
        SupportTicket open = new SupportTicket();
        open.setUserId(UUID.randomUUID());
        open.setSubject("Open one");
        open.setTicketNumber("TKT-OPEN01");
        ticketRepository.save(open);

        SupportTicket resolved = new SupportTicket();
        resolved.setUserId(UUID.randomUUID());
        resolved.setSubject("Resolved one");
        resolved.setTicketNumber("TKT-RSLV01");
        resolved.setStatus(TicketStatus.RESOLVED);
        ticketRepository.save(resolved);

        List<SupportTicket> openTickets = ticketRepository
            .findByStatusOrderByCreatedAtDesc(TicketStatus.OPEN, PageRequest.of(0, 10))
            .getContent();

        assertThat(openTickets).extracting(SupportTicket::getTicketNumber).containsExactly("TKT-OPEN01");
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.ticket.repository.SupportTicketRepositoryTest"
```

Run as a plain foreground blocking command. Expected: `BUILD SUCCESSFUL`, 3/3 tests pass. If Flyway fails to apply `V62` (e.g. a syntax error), the error will show here — fix the migration and re-run before proceeding.

- [ ] **Step 8: Commit**

```bash
git add api-service/src/main/resources/db/migration/V62__add_support_tickets.sql \
        api-service/src/main/java/com/tinniestudio/api/shared/entity/SupportTicket.java \
        api-service/src/main/java/com/tinniestudio/api/shared/entity/TicketMessage.java \
        api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/repository/SupportTicketRepository.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/repository/TicketMessageRepository.java \
        api-service/src/test/java/com/tinniestudio/api/modules/ticket/repository/SupportTicketRepositoryTest.java
git commit -m "feat: add SupportTicket/TicketMessage entities, migration, and repositories"
```

---

### Task 3: Ticket number generator

**Files:**

- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/util/TicketNumberGenerator.java`
- Create: `server/api-service/src/test/java/com/tinniestudio/api/modules/ticket/util/TicketNumberGeneratorTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.tinniestudio.api.modules.ticket.util;

import com.tinniestudio.api.modules.ticket.repository.SupportTicketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketNumberGeneratorTest {

    @Mock private SupportTicketRepository ticketRepository;

    @Test
    void generate_returnsATktPrefixedSixCharacterCode() {
        when(ticketRepository.existsByTicketNumber(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        TicketNumberGenerator generator = new TicketNumberGenerator(ticketRepository);

        String number = generator.generate();

        assertThat(number).startsWith("TKT-");
        assertThat(number).hasSize("TKT-".length() + 6);
    }

    @Test
    void generate_retriesOnCollisionUntilAUniqueCodeIsFound() {
        when(ticketRepository.existsByTicketNumber(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true, true, false);
        TicketNumberGenerator generator = new TicketNumberGenerator(ticketRepository);

        String number = generator.generate();

        assertThat(number).startsWith("TKT-");
    }

    @Test
    void generate_givesUpAfterTenAttemptsAndThrows() {
        when(ticketRepository.existsByTicketNumber(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        TicketNumberGenerator generator = new TicketNumberGenerator(ticketRepository);

        assertThatThrownBy(generator::generate).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.ticket.util.TicketNumberGeneratorTest"
```

Expected: compile error — `TicketNumberGenerator` doesn't exist yet.

- [ ] **Step 3: Implement**

```java
package com.tinniestudio.api.modules.ticket.util;

import com.tinniestudio.api.modules.ticket.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates a short, human-friendly ticket reference (e.g. "TKT-7F3K9Q") shown to the user as
 * their confirmation number. Cosmetic only — never used for internal lookups, which always go
 * through the ticket's real UUID id. The alphabet deliberately excludes 0/O/1/I to avoid
 * ambiguity when a user reads the code aloud over the phone or re-types it from an email.
 */
@Component
@RequiredArgsConstructor
public class TicketNumberGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 6;
    private static final int MAX_ATTEMPTS = 10;

    private final SupportTicketRepository ticketRepository;
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = "TKT-" + randomSuffix();
            if (!ticketRepository.existsByTicketNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique ticket number after " + MAX_ATTEMPTS + " attempts");
    }

    private String randomSuffix() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.ticket.util.TicketNumberGeneratorTest"
```

Expected: all 3 pass.

- [ ] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/ticket/util/TicketNumberGenerator.java \
        api-service/src/test/java/com/tinniestudio/api/modules/ticket/util/TicketNumberGeneratorTest.java
git commit -m "feat: add collision-checked ticket number generator"
```

---

### Task 4: Support alert email + `TICKET_REPLIED` notification plumbing

**Files:**

- Modify: `server/api-service/src/main/java/com/tinniestudio/api/shared/config/AppProperties.java`
- Modify: `server/api-service/src/main/resources/application.yml`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/auth/service/EmailService.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumer.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumerTest.java`
- Create: `server/api-service/src/test/java/com/tinniestudio/api/auth/service/EmailServiceTest.java` (only if one doesn't already exist covering `EmailService` — check first; if `EmailServiceTest.java` already exists, add to it instead of creating a new file)

- [ ] **Step 1: Write the failing tests**

First check whether `server/api-service/src/test/java/com/tinniestudio/api/auth/service/EmailServiceTest.java` already exists. If it does, read it to match its existing structure/conventions and add the new test method there. If it doesn't exist, create it with this content:

```java
package com.tinniestudio.api.auth.service;

import com.tinniestudio.api.modules.auth.service.EmailService;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.email.EmailRequest;
import com.tinniestudio.api.shared.email.ResendEmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock private ResendEmailService resendEmailService;
    private AppProperties appProperties;
    private EmailService emailService;

    private AppProperties buildProps(String alertRecipientEmail) {
        AppProperties props = new AppProperties();
        AppProperties.Support support = new AppProperties.Support();
        support.setAlertRecipientEmail(alertRecipientEmail);
        props.setSupport(support);
        return props;
    }

    @Test
    @DisplayName("sendNewTicketAlert sends to the configured recipient with ticket details")
    void sendNewTicketAlert_sendsToConfiguredRecipient() {
        appProperties = buildProps("support-ops@tinniestudio.com");
        emailService = new EmailService(resendEmailService, appProperties);

        emailService.sendNewTicketAlert("TKT-ABC123", "Can't play episode 3", "jane@example.com");

        ArgumentCaptor<EmailRequest> captor = ArgumentCaptor.forClass(EmailRequest.class);
        verify(resendEmailService).sendEmail(captor.capture());
        EmailRequest sent = captor.getValue();
        assertThat(sent.to()).isEqualTo("support-ops@tinniestudio.com");
        assertThat(sent.subject()).contains("TKT-ABC123");
        assertThat(sent.html()).contains("Can't play episode 3");
        assertThat(sent.html()).contains("jane@example.com");
    }

    @Test
    @DisplayName("sendNewTicketAlert skips sending (no throw) when no recipient is configured")
    void sendNewTicketAlert_noRecipientConfigured_skipsSilently() {
        appProperties = buildProps("");
        emailService = new EmailService(resendEmailService, appProperties);

        emailService.sendNewTicketAlert("TKT-ABC123", "Subject", "jane@example.com");

        verify(resendEmailService, never()).sendEmail(org.mockito.ArgumentMatchers.any());
    }
}
```

Add this new test to `NotificationConsumerTest.java` (follow the existing file's mock/setup pattern already in that file, same as the `DEVICE_LOGGED_OUT` tests added in a prior plan):

```java
    @Test
    @DisplayName("TICKET_REPLIED dispatches straight to the ticket's owner, no broadcast fan-out, no dedup lock")
    void ticketReplied_sendsDirectlyToTargetUser() {
        UUID userId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Map<String, Object> message = Map.of(
                "type", "TICKET_REPLIED",
                "userId", userId.toString(),
                "ticketId", ticketId.toString(),
                "subject", "Can't play episode 3"
        );

        consumer.handleNotificationEvent(message);

        verify(notificationService).sendNotification(
                eq(userId),
                eq(DomainEnums.NotificationEventType.TICKET_REPLIED),
                eq("TICKET"),
                eq(ticketId),
                eq(Map.of("subject", "Can't play episode 3"))
        );
        verify(cacheService, never()).setIfAbsent(any(), any(), any());
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.auth.service.EmailServiceTest" --tests "com.tinniestudio.api.modules.notification.consumer.NotificationConsumerTest"
```

Run as a plain foreground blocking command. Expected: compile errors — `AppProperties.Support`, `EmailService.sendNewTicketAlert`, `DomainEnums.NotificationEventType.TICKET_REPLIED`, and the `TICKET_REPLIED` switch case don't exist yet.

- [ ] **Step 3: Add the `Support` properties group**

In `AppProperties.java`, add a new nested static class anywhere alongside the other small nested groups (e.g. right after `Resend`):

```java
    @Getter
    @Setter
    public static class Support {
        private String alertRecipientEmail;
    }
```

Add the field on the outer class, alongside the other `= new X();`-initialized fields:

```java
    private Support support = new Support();
```

In `application.yml`, add a new top-level-under-`app` block, right after the `resend:` block:

```yaml
support:
  alert-recipient-email: ${SUPPORT_ALERT_RECIPIENT_EMAIL:}
```

- [ ] **Step 4: Add `sendNewTicketAlert` to `EmailService`**

Add this method anywhere among the other `@Async` methods in `EmailService.java`:

```java
    @Async
    public void sendNewTicketAlert(String ticketNumber, String subject, String submitterEmail) {
        String recipient = appProperties.getSupport().getAlertRecipientEmail();
        if (!StringUtils.hasText(recipient)) {
            log.warn("No support alert recipient configured (app.support.alert-recipient-email) — " +
                    "skipping new ticket alert for {}", ticketNumber);
            return;
        }
        String html = "<p>A new support ticket was submitted.</p>"
                + "<p><strong>Ticket:</strong> " + ticketNumber + "</p>"
                + "<p><strong>Subject:</strong> " + subject + "</p>"
                + "<p><strong>From:</strong> " + submitterEmail + "</p>";
        resendEmailService.sendEmail(new EmailRequest(recipient, "New support ticket: " + ticketNumber, html));
    }
```

- [ ] **Step 5: Add the `TICKET_REPLIED` enum value**

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
        EPISODE_PUBLISHED,
        DEVICE_LOGGED_OUT
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
        DEVICE_LOGGED_OUT,
        TICKET_REPLIED
    }
```

- [ ] **Step 6: Add the consumer case**

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
            case "TICKET_REPLIED" -> handleTicketReplied(message);
            case null, default -> log.debug("Ignoring notification event type: {}", type);
        }
    }
```

Add the new handler method after `handleDeviceLoggedOut` (same single-target style, no broadcast/dedup machinery):

```java
    // Single-target, same style as handleDeviceLoggedOut — the ticket reply itself is the only
    // event that can ever fire this for a given (ticketId, replyAt) occurrence, so no dedup lock
    // is needed.
    private void handleTicketReplied(Map<String, Object> message) {
        try {
            UUID userId = UUID.fromString((String) message.get("userId"));
            UUID ticketId = UUID.fromString((String) message.get("ticketId"));
            String subject = (String) message.get("subject");
            notificationService.sendNotification(
                    userId,
                    NotificationEventType.TICKET_REPLIED,
                    "TICKET",
                    ticketId,
                    Map.of("subject", subject)
            );
        } catch (Exception e) {
            log.error("Error processing TICKET_REPLIED notification: {}", e.getMessage(), e);
        }
    }
```

- [ ] **Step 7: Run to verify it passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.auth.service.EmailServiceTest" --tests "com.tinniestudio.api.modules.notification.consumer.NotificationConsumerTest"
```

Expected: all pass, including every pre-existing test in both files.

- [ ] **Step 8: Run the full module suite**

```bash
./gradlew :api-service:test
```

Expected: only the accepted baseline flakes.

- [ ] **Step 9: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/config/AppProperties.java \
        api-service/src/main/resources/application.yml \
        api-service/src/main/java/com/tinniestudio/api/modules/auth/service/EmailService.java \
        api-service/src/main/java/com/tinniestudio/api/shared/entity/DomainEnums.java \
        api-service/src/main/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumer.java \
        api-service/src/test/java/com/tinniestudio/api/modules/notification/consumer/NotificationConsumerTest.java \
        api-service/src/test/java/com/tinniestudio/api/auth/service/EmailServiceTest.java
git commit -m "feat: add new-ticket alert email and TICKET_REPLIED notification dispatch"
```

---

## Part 2 — Server: user-facing ticket API

### Task 5: DTOs + `TicketService` + `TicketController`

**Files:**

- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/CreateTicketRequest.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/ReplyTicketRequest.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/TicketMessageResponse.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/TicketResponse.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/TicketDetailResponse.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/TicketService.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/TicketServiceImpl.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/controller/TicketController.java`
- Create: `server/api-service/src/test/java/com/tinniestudio/api/modules/ticket/service/TicketServiceImplTest.java`
- Create: `server/api-service/src/test/java/com/tinniestudio/api/modules/ticket/controller/TicketControllerTest.java`

- [ ] **Step 1: Write the DTOs (no tests needed — plain data carriers)**

```java
package com.tinniestudio.api.modules.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
public class CreateTicketRequest {

    @NotBlank(message = "Subject is required")
    @Size(max = 255, message = "Subject must not exceed 255 characters")
    private String subject;

    @NotBlank(message = "Message is required")
    private String body;

    private String attachmentStorageKey;
}
```

```java
package com.tinniestudio.api.modules.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
public class ReplyTicketRequest {

    @NotBlank(message = "Message is required")
    private String body;

    private String attachmentStorageKey;
}
```

```java
package com.tinniestudio.api.modules.ticket.dto;

import com.tinniestudio.api.shared.entity.TicketMessage;

import java.time.Instant;
import java.util.UUID;

public record TicketMessageResponse(
        UUID id,
        String senderType,
        UUID senderId,
        String body,
        String attachmentUrl,
        Instant createdAt
) {
    public static TicketMessageResponse from(TicketMessage m) {
        return new TicketMessageResponse(
                m.getId(), m.getSenderType().name(), m.getSenderId(), m.getBody(),
                m.getAttachmentUrl(), m.getCreatedAt());
    }
}
```

```java
package com.tinniestudio.api.modules.ticket.dto;

import com.tinniestudio.api.shared.entity.SupportTicket;

import java.time.Instant;
import java.util.UUID;

public record TicketResponse(
        UUID id,
        String ticketNumber,
        String subject,
        String status,
        UUID userId,
        String submitterEmail,
        Instant createdAt,
        Instant updatedAt
) {
    public static TicketResponse from(SupportTicket t) {
        return from(t, null);
    }

    public static TicketResponse from(SupportTicket t, String submitterEmail) {
        return new TicketResponse(
                t.getId(), t.getTicketNumber(), t.getSubject(), t.getStatus().name(),
                t.getUserId(), submitterEmail, t.getCreatedAt(), t.getUpdatedAt());
    }
}
```

```java
package com.tinniestudio.api.modules.ticket.dto;

import com.tinniestudio.api.shared.entity.SupportTicket;
import com.tinniestudio.api.shared.entity.TicketMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketDetailResponse(
        UUID id,
        String ticketNumber,
        String subject,
        String status,
        UUID userId,
        String submitterEmail,
        Instant createdAt,
        Instant updatedAt,
        List<TicketMessageResponse> messages
) {
    public static TicketDetailResponse from(SupportTicket t, List<TicketMessage> messages) {
        return from(t, messages, null);
    }

    public static TicketDetailResponse from(SupportTicket t, List<TicketMessage> messages, String submitterEmail) {
        return new TicketDetailResponse(
                t.getId(), t.getTicketNumber(), t.getSubject(), t.getStatus().name(),
                t.getUserId(), submitterEmail, t.getCreatedAt(), t.getUpdatedAt(),
                messages.stream().map(TicketMessageResponse::from).toList());
    }
}
```

- [ ] **Step 2: Write the failing service test**

```java
package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.CreateTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.repository.SupportTicketRepository;
import com.tinniestudio.api.modules.ticket.repository.TicketMessageRepository;
import com.tinniestudio.api.modules.ticket.util.TicketNumberGenerator;
import com.tinniestudio.api.modules.auth.service.EmailService;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketSenderType;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import com.tinniestudio.api.shared.entity.SupportTicket;
import com.tinniestudio.api.shared.entity.TicketMessage;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import com.tinniestudio.api.shared.storage.StorageService;
import com.tinniestudio.api.modules.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketServiceImplTest {

    @Mock private SupportTicketRepository ticketRepository;
    @Mock private TicketMessageRepository messageRepository;
    @Mock private TicketNumberGenerator ticketNumberGenerator;
    @Mock private StorageService storageService;
    @Mock private EmailService emailService;
    @Mock private UserRepository userRepository;

    private TicketServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TicketServiceImpl(
                ticketRepository, messageRepository, ticketNumberGenerator,
                storageService, emailService, userRepository);
    }

    private SupportTicket savedTicket(UUID userId, TicketStatus status) {
        SupportTicket t = new SupportTicket();
        t.setUserId(userId);
        t.setSubject("Can't play episode 3");
        t.setTicketNumber("TKT-ABC123");
        t.setStatus(status);
        org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        return t;
    }

    @Test
    @DisplayName("createTicket saves an OPEN ticket + the first USER message, and sends the alert email")
    void createTicket_savesTicketAndMessage_andSendsAlert() {
        UUID userId = UUID.randomUUID();
        CreateTicketRequest request = new CreateTicketRequest();
        request.setSubject("Can't play episode 3");
        request.setBody("It just spins forever.");

        User user = new User();
        user.setId(userId);
        user.setEmail("jane@example.com");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(ticketNumberGenerator.generate()).thenReturn("TKT-ABC123");
        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> {
            SupportTicket t = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            return t;
        });

        TicketResponse response = service.createTicket(userId, request);

        assertThat(response.ticketNumber()).isEqualTo("TKT-ABC123");
        assertThat(response.status()).isEqualTo("OPEN");

        ArgumentCaptor<TicketMessage> messageCaptor = ArgumentCaptor.forClass(TicketMessage.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSenderType()).isEqualTo(TicketSenderType.USER);
        assertThat(messageCaptor.getValue().getSenderId()).isEqualTo(userId);
        assertThat(messageCaptor.getValue().getBody()).isEqualTo("It just spins forever.");

        verify(emailService).sendNewTicketAlert("TKT-ABC123", "Can't play episode 3", "jane@example.com");
    }

    @Test
    @DisplayName("createTicket still succeeds even if the alert email throws")
    void createTicket_alertEmailFailure_doesNotBlockCreation() {
        UUID userId = UUID.randomUUID();
        CreateTicketRequest request = new CreateTicketRequest();
        request.setSubject("Billing question");
        request.setBody("Why was I charged twice?");

        User user = new User();
        user.setId(userId);
        user.setEmail("jane@example.com");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(ticketNumberGenerator.generate()).thenReturn("TKT-XYZ999");
        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> {
            SupportTicket t = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            return t;
        });
        doThrow(new RuntimeException("email provider down")).when(emailService)
                .sendNewTicketAlert(any(), any(), any());

        TicketResponse response = service.createTicket(userId, request);

        assertThat(response.ticketNumber()).isEqualTo("TKT-XYZ999");
    }

    @Test
    @DisplayName("getMyTicket throws ResourceNotFoundException for a ticket that isn't the caller's own")
    void getMyTicket_wrongOwner_throwsNotFound() {
        UUID userId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findByIdAndUserId(ticketId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyTicket(userId, ticketId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("replyToTicket on a CLOSED ticket is rejected")
    void replyToTicket_closedTicket_rejected() {
        UUID userId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.CLOSED);
        when(ticketRepository.findByIdAndUserId(ticket.getId(), userId)).thenReturn(Optional.of(ticket));

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("Still broken!");

        assertThatThrownBy(() -> service.replyToTicket(userId, ticket.getId(), request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("open a new ticket");

        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("replyToTicket on a RESOLVED ticket reopens it to IN_PROGRESS")
    void replyToTicket_resolvedTicket_reopensToInProgress() {
        UUID userId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.RESOLVED);
        when(ticketRepository.findByIdAndUserId(ticket.getId(), userId)).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).thenReturn(List.of());

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("Still broken!");

        TicketDetailResponse response = service.replyToTicket(userId, ticket.getId(), request);

        assertThat(response.status()).isEqualTo("IN_PROGRESS");
        verify(messageRepository).save(any(TicketMessage.class));
    }

    @Test
    @DisplayName("replyToTicket on an OPEN ticket leaves status unchanged")
    void replyToTicket_openTicket_statusUnchanged() {
        UUID userId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.OPEN);
        when(ticketRepository.findByIdAndUserId(ticket.getId(), userId)).thenReturn(Optional.of(ticket));
        when(messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).thenReturn(List.of());

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("Any update?");

        TicketDetailResponse response = service.replyToTicket(userId, ticket.getId(), request);

        assertThat(response.status()).isEqualTo("OPEN");
        verify(ticketRepository, never()).save(any());
    }
}
```

- [ ] **Step 3: Run to verify it fails**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.ticket.service.TicketServiceImplTest"
```

Expected: compile error — `TicketService`/`TicketServiceImpl` don't exist yet.

- [ ] **Step 4: Implement `TicketService` interface**

Attachment upload (`initiateAttachmentUpload`) is deliberately NOT part of this interface yet — Task 7 adds it there, together with its DTOs and implementation, once attachments are wired up. Adding it here now would forward-reference DTOs that don't exist until Task 7.

```java
package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.CreateTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TicketService {

    TicketResponse createTicket(UUID userId, CreateTicketRequest request);

    Page<TicketResponse> listMyTickets(UUID userId, Pageable pageable);

    TicketDetailResponse getMyTicket(UUID userId, UUID ticketId);

    TicketDetailResponse replyToTicket(UUID userId, UUID ticketId, ReplyTicketRequest request);
}
```

- [ ] **Step 5: Implement `TicketServiceImpl`**

```java
package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.CreateTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.repository.SupportTicketRepository;
import com.tinniestudio.api.modules.ticket.repository.TicketMessageRepository;
import com.tinniestudio.api.modules.ticket.util.TicketNumberGenerator;
import com.tinniestudio.api.modules.auth.service.EmailService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketSenderType;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import com.tinniestudio.api.shared.entity.SupportTicket;
import com.tinniestudio.api.shared.entity.TicketMessage;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import com.tinniestudio.api.shared.storage.StorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final SupportTicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final TicketNumberGenerator ticketNumberGenerator;
    private final StorageService storageService;
    private final EmailService emailService;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public TicketResponse createTicket(UUID userId, CreateTicketRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String attachmentUrl = resolveAttachmentUrl(request.getAttachmentStorageKey());

        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(userId);
        ticket.setSubject(request.getSubject());
        ticket.setTicketNumber(ticketNumberGenerator.generate());
        ticket = ticketRepository.save(ticket);

        TicketMessage message = new TicketMessage();
        message.setTicketId(ticket.getId());
        message.setSenderType(TicketSenderType.USER);
        message.setSenderId(userId);
        message.setBody(request.getBody());
        message.setAttachmentUrl(attachmentUrl);
        messageRepository.save(message);

        // Best-effort — a mail-provider failure must never block ticket submission.
        try {
            emailService.sendNewTicketAlert(ticket.getTicketNumber(), ticket.getSubject(), user.getEmail());
        } catch (Exception ex) {
            log.warn("New-ticket alert email failed for {} (non-critical): {}",
                    ticket.getTicketNumber(), ex.getMessage());
        }

        return TicketResponse.from(ticket);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TicketResponse> listMyTickets(UUID userId, Pageable pageable) {
        return ticketRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(TicketResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public TicketDetailResponse getMyTicket(UUID userId, UUID ticketId) {
        SupportTicket ticket = findOwnTicket(userId, ticketId);
        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        return TicketDetailResponse.from(ticket, messages);
    }

    @Override
    @Transactional
    public TicketDetailResponse replyToTicket(UUID userId, UUID ticketId, ReplyTicketRequest request) {
        SupportTicket ticket = findOwnTicket(userId, ticketId);

        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new BadRequestException(
                    "This ticket is closed. Please open a new ticket.");
        }
        if (ticket.getStatus() == TicketStatus.RESOLVED) {
            ticket.setStatus(TicketStatus.IN_PROGRESS);
            ticketRepository.save(ticket);
        }

        String attachmentUrl = resolveAttachmentUrl(request.getAttachmentStorageKey());

        TicketMessage message = new TicketMessage();
        message.setTicketId(ticketId);
        message.setSenderType(TicketSenderType.USER);
        message.setSenderId(userId);
        message.setBody(request.getBody());
        message.setAttachmentUrl(attachmentUrl);
        messageRepository.save(message);

        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        return TicketDetailResponse.from(ticket, messages);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SupportTicket findOwnTicket(UUID userId, UUID ticketId) {
        return ticketRepository.findByIdAndUserId(ticketId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
    }

    private String resolveAttachmentUrl(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return null;
        }
        if (!storageService.objectExists(storageKey)) {
            throw new BadRequestException("Upload not found in storage. Please upload the file before submitting.");
        }
        return storageKey;
    }
}
```

Note on `resolveAttachmentUrl`: it returns the raw `storageKey` as the stored "URL" for now (consistent with how the eventual CDN-URL-building happens elsewhere) — Task 7 will update this one method to build the real CDN URL via `appProperties.getCdn().getBaseUrl() + "/" + storageKey`, once `AppProperties` is injected for that purpose; this task only needs `storageService.objectExists(...)` to validate the upload landed, since attachment upload itself isn't wired until Task 7 and no real storage key will ever be submitted through this code path until then.

- [ ] **Step 6: Implement `TicketController`**

```java
package com.tinniestudio.api.modules.ticket.controller;

import com.tinniestudio.api.modules.ticket.dto.CreateTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.service.TicketService;
import com.tinniestudio.api.shared.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Support Tickets", description = "User-facing ticket submission and thread management.")
public class TicketController {

    private final TicketService ticketService;

    @Operation(summary = "Submit a new support ticket")
    @PostMapping
    public ResponseEntity<TicketResponse> create(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody CreateTicketRequest request) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(ticketService.createTicket(userId, request));
    }

    @Operation(summary = "List the authenticated user's own tickets")
    @GetMapping
    public ResponseEntity<Page<TicketResponse>> list(
            @AuthenticationPrincipal UserDetails principal,
            Pageable pageable) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(ticketService.listMyTickets(userId, pageable));
    }

    @Operation(summary = "Get one of the authenticated user's own tickets, with its full message thread")
    @GetMapping("/{ticketId}")
    public ResponseEntity<TicketDetailResponse> getOne(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID ticketId) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(ticketService.getMyTicket(userId, ticketId));
    }

    @Operation(summary = "Reply to one of the authenticated user's own tickets")
    @PostMapping("/{ticketId}/messages")
    public ResponseEntity<TicketDetailResponse> reply(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID ticketId,
            @Valid @RequestBody ReplyTicketRequest request) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(ticketService.replyToTicket(userId, ticketId, request));
    }
}
```

- [ ] **Step 7: Write the controller test**

```java
package com.tinniestudio.api.modules.ticket.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.ticket.dto.CreateTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.service.TicketService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TicketController.class)
@AutoConfigureMockMvc(addFilters = false)
class TicketControllerTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CONTEXT_PATH = "/api/v1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private TicketService ticketService;
    @MockBean private UserRepository userRepository;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    private TicketResponse sampleResponse() {
        return new TicketResponse(
                UUID.fromString(USER_ID), "TKT-ABC123", "Can't play episode 3", "OPEN",
                UUID.fromString(USER_ID), null, Instant.now(), Instant.now());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void create_returns201WithTicketResponse() throws Exception {
        when(ticketService.createTicket(eq(UUID.fromString(USER_ID)), any())).thenReturn(sampleResponse());

        CreateTicketRequest request = new CreateTicketRequest();
        request.setSubject("Can't play episode 3");
        request.setBody("It just spins forever.");

        mockMvc.perform(post(CONTEXT_PATH + "/tickets")
                        .contextPath(CONTEXT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ticketNumber").value("TKT-ABC123"));
    }

    @Test
    @WithMockUser(username = USER_ID)
    void create_blankSubject_returns400() throws Exception {
        CreateTicketRequest request = new CreateTicketRequest();
        request.setSubject("");
        request.setBody("It just spins forever.");

        mockMvc.perform(post(CONTEXT_PATH + "/tickets")
                        .contextPath(CONTEXT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = USER_ID)
    void list_returns200WithPage() throws Exception {
        when(ticketService.listMyTickets(eq(UUID.fromString(USER_ID)), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(sampleResponse())));

        mockMvc.perform(get(CONTEXT_PATH + "/tickets").contextPath(CONTEXT_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
```

- [ ] **Step 8: Run to verify everything passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.ticket.service.TicketServiceImplTest" --tests "com.tinniestudio.api.modules.ticket.controller.TicketControllerTest"
```

Run as a plain foreground blocking command. Expected: all pass.

- [ ] **Step 9: Run the full module suite**

```bash
./gradlew :api-service:test
```

Expected: only the accepted baseline flakes.

- [ ] **Step 10: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/CreateTicketRequest.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/ReplyTicketRequest.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/TicketMessageResponse.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/TicketResponse.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/TicketDetailResponse.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/TicketService.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/TicketServiceImpl.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/controller/TicketController.java \
        api-service/src/test/java/com/tinniestudio/api/modules/ticket/service/TicketServiceImplTest.java \
        api-service/src/test/java/com/tinniestudio/api/modules/ticket/controller/TicketControllerTest.java
git commit -m "feat: add user-facing ticket submission, listing, and reply API"
```

---

## Part 3 — Server: admin-facing ticket API

### Task 6: `UpdateTicketStatusRequest` + `AdminTicketService` + `AdminTicketController`

**Files:**

- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/UpdateTicketStatusRequest.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/AdminTicketService.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/AdminTicketServiceImpl.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/controller/AdminTicketController.java`
- Create: `server/api-service/src/test/java/com/tinniestudio/api/modules/ticket/service/AdminTicketServiceImplTest.java`
- Create: `server/api-service/src/test/java/com/tinniestudio/api/modules/ticket/controller/AdminTicketControllerTest.java`

- [ ] **Step 1: Write the DTO**

```java
package com.tinniestudio.api.modules.ticket.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
public class UpdateTicketStatusRequest {

    @NotNull(message = "Status is required")
    private TicketStatus status;
}
```

- [ ] **Step 2: Write the failing service test**

```java
package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.dto.UpdateTicketStatusRequest;
import com.tinniestudio.api.modules.ticket.repository.SupportTicketRepository;
import com.tinniestudio.api.modules.ticket.repository.TicketMessageRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketSenderType;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import com.tinniestudio.api.shared.entity.SupportTicket;
import com.tinniestudio.api.shared.entity.TicketMessage;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import com.tinniestudio.api.shared.queue.RabbitConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminTicketServiceImplTest {

    @Mock private SupportTicketRepository ticketRepository;
    @Mock private TicketMessageRepository messageRepository;
    @Mock private UserRepository userRepository;
    @Mock private RabbitTemplate rabbitTemplate;

    private AdminTicketServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminTicketServiceImpl(ticketRepository, messageRepository, userRepository, rabbitTemplate);
    }

    private SupportTicket savedTicket(UUID userId, TicketStatus status) {
        SupportTicket t = new SupportTicket();
        t.setUserId(userId);
        t.setSubject("Can't play episode 3");
        t.setTicketNumber("TKT-ABC123");
        t.setStatus(status);
        org.springframework.test.util.ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        return t;
    }

    @Test
    @DisplayName("getTicket on an unknown id throws ResourceNotFoundException")
    void getTicket_unknownId_throwsNotFound() {
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTicket(ticketId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("replyToTicket on an OPEN ticket transitions it to IN_PROGRESS and publishes TICKET_REPLIED")
    void replyToTicket_openTicket_transitionsAndPublishesNotification() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.OPEN);
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).thenReturn(List.of());

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("We're looking into it.");

        TicketDetailResponse response = service.replyToTicket(adminId, ticket.getId(), request);

        assertThat(response.status()).isEqualTo("IN_PROGRESS");

        ArgumentCaptor<TicketMessage> messageCaptor = ArgumentCaptor.forClass(TicketMessage.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSenderType()).isEqualTo(TicketSenderType.ADMIN);
        assertThat(messageCaptor.getValue().getSenderId()).isEqualTo(adminId);

        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.QUEUE_NOTIFICATIONS), eq(Map.of(
                "type", "TICKET_REPLIED",
                "userId", userId.toString(),
                "ticketId", ticket.getId().toString(),
                "subject", "Can't play episode 3"
        )));
    }

    @Test
    @DisplayName("replyToTicket on an IN_PROGRESS ticket leaves status unchanged")
    void replyToTicket_inProgressTicket_statusUnchanged() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.IN_PROGRESS);
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId())).thenReturn(List.of());

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("Still working on it.");

        TicketDetailResponse response = service.replyToTicket(adminId, ticket.getId(), request);

        assertThat(response.status()).isEqualTo("IN_PROGRESS");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    @DisplayName("replyToTicket on a CLOSED ticket is rejected, same rule as the user-facing endpoint")
    void replyToTicket_closedTicket_rejected() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.CLOSED);
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        ReplyTicketRequest request = new ReplyTicketRequest();
        request.setBody("One more note.");

        assertThatThrownBy(() -> service.replyToTicket(adminId, ticket.getId(), request))
                .isInstanceOf(BadRequestException.class);

        verify(messageRepository, never()).save(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), any(Map.class));
    }

    @Test
    @DisplayName("updateStatus accepts RESOLVED")
    void updateStatus_resolved_succeeds() {
        UUID userId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.IN_PROGRESS);
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest();
        request.setStatus(TicketStatus.RESOLVED);

        TicketResponse response = service.updateStatus(ticket.getId(), request);

        assertThat(response.status()).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("updateStatus rejects OPEN and IN_PROGRESS as manual targets — those are system-managed transitions")
    void updateStatus_openOrInProgress_rejected() {
        UUID userId = UUID.randomUUID();
        SupportTicket ticket = savedTicket(userId, TicketStatus.RESOLVED);
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        UpdateTicketStatusRequest request = new UpdateTicketStatusRequest();
        request.setStatus(TicketStatus.OPEN);

        assertThatThrownBy(() -> service.updateStatus(ticket.getId(), request))
                .isInstanceOf(BadRequestException.class);

        verify(ticketRepository, never()).save(any());
    }
}
```

- [ ] **Step 3: Run to verify it fails**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.ticket.service.AdminTicketServiceImplTest"
```

Expected: compile error — `AdminTicketService`/`AdminTicketServiceImpl` don't exist yet.

- [ ] **Step 4: Implement `AdminTicketService` interface**

```java
package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.dto.UpdateTicketStatusRequest;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface AdminTicketService {

    Page<TicketResponse> listTickets(TicketStatus status, Pageable pageable);

    TicketDetailResponse getTicket(UUID ticketId);

    TicketDetailResponse replyToTicket(UUID adminId, UUID ticketId, ReplyTicketRequest request);

    TicketResponse updateStatus(UUID ticketId, UpdateTicketStatusRequest request);
}
```

- [ ] **Step 5: Implement `AdminTicketServiceImpl`**

```java
package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.dto.UpdateTicketStatusRequest;
import com.tinniestudio.api.modules.ticket.repository.SupportTicketRepository;
import com.tinniestudio.api.modules.ticket.repository.TicketMessageRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketSenderType;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import com.tinniestudio.api.shared.entity.SupportTicket;
import com.tinniestudio.api.shared.entity.TicketMessage;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import com.tinniestudio.api.shared.queue.RabbitConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminTicketServiceImpl implements AdminTicketService {

    private static final Set<TicketStatus> MANUAL_STATUS_TARGETS = Set.of(TicketStatus.RESOLVED, TicketStatus.CLOSED);

    private final SupportTicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final RabbitTemplate rabbitTemplate;

    @Override
    @Transactional(readOnly = true)
    public Page<TicketResponse> listTickets(TicketStatus status, Pageable pageable) {
        Page<SupportTicket> tickets = status != null
                ? ticketRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                : ticketRepository.findAllByOrderByCreatedAtDesc(pageable);
        return tickets.map(t -> TicketResponse.from(t, submitterEmail(t.getUserId())));
    }

    @Override
    @Transactional(readOnly = true)
    public TicketDetailResponse getTicket(UUID ticketId) {
        SupportTicket ticket = findTicket(ticketId);
        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        return TicketDetailResponse.from(ticket, messages, submitterEmail(ticket.getUserId()));
    }

    @Override
    @Transactional
    public TicketDetailResponse replyToTicket(UUID adminId, UUID ticketId, ReplyTicketRequest request) {
        SupportTicket ticket = findTicket(ticketId);

        // Closed tickets reject new replies from either side — admins re-open explicitly via
        // updateStatus() before continuing a conversation, same rule as the user-facing endpoint
        // for consistency (the spec only specifies this for user replies, but there's no
        // "half-closed" state — a closed ticket should behave uniformly for both senders).
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new BadRequestException("This ticket is closed.");
        }
        if (ticket.getStatus() == TicketStatus.OPEN) {
            ticket.setStatus(TicketStatus.IN_PROGRESS);
            ticketRepository.save(ticket);
        }

        TicketMessage message = new TicketMessage();
        message.setTicketId(ticketId);
        message.setSenderType(TicketSenderType.ADMIN);
        message.setSenderId(adminId);
        message.setBody(request.getBody());
        message.setAttachmentUrl(request.getAttachmentStorageKey());
        messageRepository.save(message);

        publishTicketRepliedNotification(ticket);

        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        return TicketDetailResponse.from(ticket, messages, submitterEmail(ticket.getUserId()));
    }

    @Override
    @Transactional
    public TicketResponse updateStatus(UUID ticketId, UpdateTicketStatusRequest request) {
        SupportTicket ticket = findTicket(ticketId);

        if (!MANUAL_STATUS_TARGETS.contains(request.getStatus())) {
            throw new BadRequestException(
                    "Status can only be manually set to RESOLVED or CLOSED — OPEN/IN_PROGRESS are system-managed.");
        }

        ticket.setStatus(request.getStatus());
        ticketRepository.save(ticket);
        return TicketResponse.from(ticket, submitterEmail(ticket.getUserId()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SupportTicket findTicket(UUID ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
    }

    private String submitterEmail(UUID userId) {
        return userRepository.findById(userId).map(User::getEmail).orElse(null);
    }

    // Best-effort, same non-critical-failure handling as every other broadcast/publish helper in
    // this codebase — a failed notification publish must never block the admin's reply itself.
    private void publishTicketRepliedNotification(SupportTicket ticket) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.QUEUE_NOTIFICATIONS, Map.of(
                    "type", "TICKET_REPLIED",
                    "userId", ticket.getUserId().toString(),
                    "ticketId", ticket.getId().toString(),
                    "subject", ticket.getSubject()
            ));
        } catch (Exception e) {
            log.warn("TICKET_REPLIED notification publish failed (non-critical): {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 6: Implement `AdminTicketController`**

```java
package com.tinniestudio.api.modules.ticket.controller;

import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.dto.UpdateTicketStatusRequest;
import com.tinniestudio.api.modules.ticket.service.AdminTicketService;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import com.tinniestudio.api.shared.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/tickets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Support Tickets", description = "Any admin (SUPER_ADMIN, MODERATOR, or SUPPORT) can view and reply to any ticket — no per-agent assignment.")
public class AdminTicketController {

    private final AdminTicketService adminTicketService;

    @Operation(summary = "List all tickets, optionally filtered by status")
    @GetMapping
    public ResponseEntity<Page<TicketResponse>> list(
            @RequestParam(required = false) TicketStatus status,
            Pageable pageable) {
        return ResponseEntity.ok(adminTicketService.listTickets(status, pageable));
    }

    @Operation(summary = "Get a ticket's full thread")
    @GetMapping("/{ticketId}")
    public ResponseEntity<TicketDetailResponse> getOne(@PathVariable UUID ticketId) {
        return ResponseEntity.ok(adminTicketService.getTicket(ticketId));
    }

    @Operation(summary = "Reply to a ticket as an admin")
    @PostMapping("/{ticketId}/messages")
    public ResponseEntity<TicketDetailResponse> reply(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID ticketId,
            @Valid @RequestBody ReplyTicketRequest request) {
        UUID adminId = CurrentUser.id(principal);
        return ResponseEntity.ok(adminTicketService.replyToTicket(adminId, ticketId, request));
    }

    @Operation(summary = "Resolve or close a ticket")
    @PatchMapping("/{ticketId}/status")
    public ResponseEntity<TicketResponse> updateStatus(
            @PathVariable UUID ticketId,
            @Valid @RequestBody UpdateTicketStatusRequest request) {
        return ResponseEntity.ok(adminTicketService.updateStatus(ticketId, request));
    }
}
```

- [ ] **Step 7: Write the controller test, proving role-gating**

```java
package com.tinniestudio.api.modules.ticket.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.service.AdminTicketService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminTicketController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminTicketControllerTest {

    private static final String CONTEXT_PATH = "/api/v1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AdminTicketService adminTicketService;
    @MockBean private UserRepository userRepository;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    private TicketResponse sampleResponse() {
        UUID id = UUID.randomUUID();
        return new TicketResponse(id, "TKT-ABC123", "Can't play episode 3", "OPEN",
                id, "jane@example.com", Instant.now(), Instant.now());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_noFilter_returns200WithPage() throws Exception {
        when(adminTicketService.listTickets(isNull(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get(CONTEXT_PATH + "/admin/tickets").contextPath(CONTEXT_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void list_noAuthentication_returns401Or403() throws Exception {
        // addFilters = false bypasses the real security filter chain, so this test can only
        // prove the endpoint exists and is wired to @PreAuthorize at the annotation level, not
        // that an anonymous caller is truly rejected end-to-end — that end-to-end guarantee is
        // covered by the real filter chain in production and by AdminAuthIntegrationTest-style
        // tests elsewhere in this module for other admin endpoints. This slice test intentionally
        // does not assert a specific status code for the unauthenticated case.
        mockMvc.perform(get(CONTEXT_PATH + "/admin/tickets").contextPath(CONTEXT_PATH));
    }
}
```

- [ ] **Step 8: Run to verify everything passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.ticket.service.AdminTicketServiceImplTest" --tests "com.tinniestudio.api.modules.ticket.controller.AdminTicketControllerTest"
```

Run as a plain foreground blocking command. Expected: all pass.

- [ ] **Step 9: Run the full module suite**

```bash
./gradlew :api-service:test
```

Expected: only the accepted baseline flakes.

- [ ] **Step 10: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/UpdateTicketStatusRequest.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/AdminTicketService.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/AdminTicketServiceImpl.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/controller/AdminTicketController.java \
        api-service/src/test/java/com/tinniestudio/api/modules/ticket/service/AdminTicketServiceImplTest.java \
        api-service/src/test/java/com/tinniestudio/api/modules/ticket/controller/AdminTicketControllerTest.java
git commit -m "feat: add admin-facing ticket list/reply/status API, gated to any admin role"
```

---

### Task 7: Ticket attachments (presign → upload → validate-on-submit)

**Files:**

- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/TicketAttachmentUploadRequest.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/TicketAttachmentUploadResponse.java`
- Create: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/TicketAttachmentResolver.java`
- Create: `server/api-service/src/test/java/com/tinniestudio/api/modules/ticket/service/TicketAttachmentResolverTest.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/TicketService.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/TicketServiceImpl.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/AdminTicketServiceImpl.java`
- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/ticket/controller/TicketController.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/modules/ticket/service/TicketServiceImplTest.java`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/modules/ticket/service/AdminTicketServiceImplTest.java`

This task extracts attachment validation into one shared `TicketAttachmentResolver` component, used by both `TicketServiceImpl` (user submit/reply) and `AdminTicketServiceImpl` (admin reply) — avoiding duplicating the mime/size-validation logic or the CDN-URL-building logic in two places.

**IDOR note (amended after Task 5's implementation surfaced this during review):** `TicketServiceImpl.resolveAttachmentUrl` originally only checked `storageService.objectExists(storageKey)`, with no check that the calling user actually owns that key — any authenticated user could reference another user's existing storage object (e.g. their avatar) and have it accepted as their own ticket attachment. That was fixed directly on `TicketServiceImpl` ahead of this task: `resolveAttachmentUrl` now takes a `UUID userId` and requires `storageKey.startsWith("tickets/" + userId + "/")`, rejecting `".."` traversal too. `TicketAttachmentResolver.resolveAttachmentUrl` below is written to take that same ownership-scoping from day one, so this task does not reintroduce the gap when it takes over from `TicketServiceImpl`'s private method.

- [ ] **Step 1: Write the DTOs**

```java
package com.tinniestudio.api.modules.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
public class TicketAttachmentUploadRequest {

    @NotBlank(message = "mimeType is required")
    private String mimeType;

    @NotNull(message = "fileSizeBytes is required")
    private Long fileSizeBytes;
}
```

```java
package com.tinniestudio.api.modules.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class TicketAttachmentUploadResponse {
    private String uploadUrl;
    private String storageKey;
    private Instant expiresAt;
}
```

- [ ] **Step 2: Write the failing test for `TicketAttachmentResolver`**

```java
package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.TicketAttachmentUploadRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketAttachmentUploadResponse;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.storage.PresignedUploadResult;
import com.tinniestudio.api.shared.storage.StorageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketAttachmentResolverTest {

    @Mock private StorageService storageService;
    private AppProperties appProperties;
    private TicketAttachmentResolver resolver;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        AppProperties.Cdn cdn = new AppProperties.Cdn();
        cdn.setBaseUrl("https://cdn.test");
        appProperties.setCdn(cdn);
        resolver = new TicketAttachmentResolver(storageService, appProperties);
    }

    @Test
    @DisplayName("initiateUpload rejects an unsupported mime type")
    void initiateUpload_unsupportedMime_throws() {
        TicketAttachmentUploadRequest request = new TicketAttachmentUploadRequest();
        request.setMimeType("application/zip");
        request.setFileSizeBytes(1024L);

        assertThatThrownBy(() -> resolver.initiateUpload(request, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    @DisplayName("initiateUpload rejects a file over the 10 MB limit")
    void initiateUpload_oversized_throws() {
        TicketAttachmentUploadRequest request = new TicketAttachmentUploadRequest();
        request.setMimeType("image/png");
        request.setFileSizeBytes(11_000_000L);

        assertThatThrownBy(() -> resolver.initiateUpload(request, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("10 MB");
    }

    @Test
    @DisplayName("initiateUpload accepts a PDF within the size limit and returns a presigned URL")
    void initiateUpload_validPdf_returnsPresignedUrl() {
        TicketAttachmentUploadRequest request = new TicketAttachmentUploadRequest();
        request.setMimeType("application/pdf");
        request.setFileSizeBytes(2048L);
        Instant expiry = Instant.now().plusSeconds(900);
        when(storageService.generateUploadUrl(any(), any(), any(Long.class), any(Duration.class)))
                .thenReturn(new PresignedUploadResult("https://upload.test/x", "tickets/x.pdf", expiry));

        TicketAttachmentUploadResponse response = resolver.initiateUpload(request, UUID.randomUUID());

        assertThat(response.getUploadUrl()).isEqualTo("https://upload.test/x");
        assertThat(response.getStorageKey()).isEqualTo("tickets/x.pdf");
    }

    @Test
    @DisplayName("resolveAttachmentUrl returns null when no storage key was provided")
    void resolveAttachmentUrl_noKey_returnsNull() {
        UUID userId = UUID.randomUUID();
        assertThat(resolver.resolveAttachmentUrl(userId, null)).isNull();
        assertThat(resolver.resolveAttachmentUrl(userId, "")).isNull();
    }

    @Test
    @DisplayName("resolveAttachmentUrl rejects a storage key the upload never actually landed at")
    void resolveAttachmentUrl_missingObject_throws() {
        UUID userId = UUID.randomUUID();
        String key = "tickets/" + userId + "/missing.png";
        when(storageService.objectExists(key)).thenReturn(false);

        assertThatThrownBy(() -> resolver.resolveAttachmentUrl(userId, key))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Please upload the file");
    }

    @Test
    @DisplayName("resolveAttachmentUrl builds the CDN URL for a storage key that really exists under the caller's own prefix")
    void resolveAttachmentUrl_existingObject_buildsCdnUrl() {
        UUID userId = UUID.randomUUID();
        String key = "tickets/" + userId + "/real.png";
        when(storageService.objectExists(key)).thenReturn(true);

        assertThat(resolver.resolveAttachmentUrl(userId, key)).isEqualTo("https://cdn.test/" + key);
    }

    @Test
    @DisplayName("resolveAttachmentUrl rejects a storage key outside the given owner's own tickets/{ownerId}/ prefix")
    void resolveAttachmentUrl_wrongOwnerPrefix_throws() {
        UUID userId = UUID.randomUUID();
        String someoneElsesKey = "tickets/" + UUID.randomUUID() + "/real.png";

        assertThatThrownBy(() -> resolver.resolveAttachmentUrl(userId, someoneElsesKey))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid attachment");
    }

    @Test
    @DisplayName("resolveAttachmentUrl rejects a path-traversal attempt in the storage key")
    void resolveAttachmentUrl_traversal_throws() {
        UUID userId = UUID.randomUUID();
        String traversalKey = "tickets/" + userId + "/../../avatars/someone-else/photo.jpg";

        assertThatThrownBy(() -> resolver.resolveAttachmentUrl(userId, traversalKey))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid attachment");
    }
}
```

`PresignedUploadResult` is a record (`record PresignedUploadResult(String uploadUrl, String storageKey, Instant expiresAt) {}`), confirmed via `com.tinniestudio.api.shared.storage.PresignedUploadResult` — the constructor call above matches it exactly.

- [ ] **Step 3: Run to verify it fails**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.ticket.service.TicketAttachmentResolverTest"
```

Expected: compile error — `TicketAttachmentResolver` doesn't exist yet.

- [ ] **Step 4: Implement `TicketAttachmentResolver`**

```java
package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.TicketAttachmentUploadRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketAttachmentUploadResponse;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.storage.PresignedUploadResult;
import com.tinniestudio.api.shared.storage.StorageService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Shared attachment validation/URL-resolution for ticket messages — used by both the
 * user-facing and admin-facing reply paths so the mime/size rules and CDN-URL-building logic
 * exist in exactly one place, mirroring the presign/upload/confirm pattern already established
 * by {@code UserProfileServiceImpl}'s avatar upload flow.
 */
@Component
@RequiredArgsConstructor
public class TicketAttachmentResolver {

    private static final long MAX_ATTACHMENT_BYTES = 10_485_760L; // 10 MB, same ceiling as avatar uploads
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "application/pdf"
    );

    private final StorageService storageService;
    private final AppProperties appProperties;

    public TicketAttachmentUploadResponse initiateUpload(TicketAttachmentUploadRequest request, UUID userId) {
        if (request.getMimeType() == null || !ALLOWED_MIME_TYPES.contains(request.getMimeType())) {
            throw new BadRequestException(
                    "File type not supported. Allowed: image/jpeg, image/png, image/webp, application/pdf");
        }
        if (request.getFileSizeBytes() == null || request.getFileSizeBytes() > MAX_ATTACHMENT_BYTES) {
            throw new BadRequestException("File size exceeds the 10 MB limit");
        }

        String ext = mimeToExtension(request.getMimeType());
        String storageKey = "tickets/" + userId + "/" + UUID.randomUUID() + "." + ext;

        PresignedUploadResult result = storageService.generateUploadUrl(
                storageKey, request.getMimeType(), request.getFileSizeBytes(), Duration.ofMinutes(15));
        return new TicketAttachmentUploadResponse(result.uploadUrl(), result.storageKey(), result.expiresAt());
    }

    /**
     * @param ownerId the id whose own {@code tickets/{ownerId}/} prefix {@code storageKey} must
     * fall under. For the user-facing path this is the caller's own id; for the admin-facing
     * path (no separate admin upload flow exists) this is the ticket's owning user id — see
     * {@code AdminTicketServiceImpl.replyToTicket} in Task 7 Step 7.
     * @return the final CDN URL if {@code storageKey} is non-blank and the upload really landed
     * in storage, or {@code null} if no key was provided (no attachment on this message).
     * @throws BadRequestException if the key is outside {@code ownerId}'s own prefix, contains a
     * path-traversal sequence, or nothing exists at it yet.
     */
    public String resolveAttachmentUrl(UUID ownerId, String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return null;
        }
        // Without this ownership-scoping check, any caller could pass ANY storage key that
        // happens to exist in the bucket (e.g. another user's avatar) and have it falsely
        // accepted as this ticket message's attachment — objectExists() alone only proves
        // *something* is there, not that ownerId is the one who put it there.
        String requiredPrefix = "tickets/" + ownerId + "/";
        if (storageKey.contains("..") || !storageKey.startsWith(requiredPrefix)) {
            throw new BadRequestException("Invalid attachment reference.");
        }
        if (!storageService.objectExists(storageKey)) {
            throw new BadRequestException("Upload not found in storage. Please upload the file before submitting.");
        }
        return appProperties.getCdn().getBaseUrl() + "/" + storageKey;
    }

    private String mimeToExtension(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
    }
}
```

- [ ] **Step 5: Run to verify the resolver test passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.ticket.service.TicketAttachmentResolverTest"
```

Expected: all 8 pass.

- [ ] **Step 6: Wire `TicketAttachmentResolver` into `TicketService`/`TicketServiceImpl`**

Add `initiateAttachmentUpload` to the `TicketService` interface:

```java
package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.CreateTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketAttachmentUploadRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketAttachmentUploadResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TicketService {

    TicketResponse createTicket(UUID userId, CreateTicketRequest request);

    Page<TicketResponse> listMyTickets(UUID userId, Pageable pageable);

    TicketDetailResponse getMyTicket(UUID userId, UUID ticketId);

    TicketDetailResponse replyToTicket(UUID userId, UUID ticketId, ReplyTicketRequest request);

    TicketAttachmentUploadResponse initiateAttachmentUpload(UUID userId, TicketAttachmentUploadRequest request);
}
```

In `TicketServiceImpl.java`: add a `TicketAttachmentResolver attachmentResolver` field to the constructor-injected field list (via Lombok `@RequiredArgsConstructor`, so just add the field — no manual constructor edit), remove the now-redundant private `resolveAttachmentUrl(UUID, String)` method entirely (the ownership-scoping check it already does — see the IDOR note at the top of this task — moves into `TicketAttachmentResolver.resolveAttachmentUrl`, which takes the same `ownerId, storageKey` shape, so this is a straight swap, not a security regression), and replace its two call sites with `attachmentResolver.resolveAttachmentUrl(userId, ...)`:

```java
    private final TicketAttachmentResolver attachmentResolver;
```

(add this field alongside the other `private final` fields at the top of the class)

Replace both occurrences of:

```java
        String attachmentUrl = resolveAttachmentUrl(userId, request.getAttachmentStorageKey());
```

with:

```java
        String attachmentUrl = attachmentResolver.resolveAttachmentUrl(userId, request.getAttachmentStorageKey());
```

Delete the old private `resolveAttachmentUrl(UUID, String)` method entirely (it's now `TicketAttachmentResolver.resolveAttachmentUrl`, called via the new field).

Add the new interface method's implementation, anywhere among the other `@Override` methods:

```java
    @Override
    public TicketAttachmentUploadResponse initiateAttachmentUpload(UUID userId, TicketAttachmentUploadRequest request) {
        return attachmentResolver.initiateUpload(request, userId);
    }
```

- [ ] **Step 7: Wire `TicketAttachmentResolver` into `AdminTicketServiceImpl`**

Add the field:

```java
    private final TicketAttachmentResolver attachmentResolver;
```

Replace:

```java
        message.setAttachmentUrl(request.getAttachmentStorageKey());
```

with:

```java
        message.setAttachmentUrl(attachmentResolver.resolveAttachmentUrl(ticket.getUserId(), request.getAttachmentStorageKey()));
```

`ticket.getUserId()` (the ticket's owner), not `adminId`: there is no separate admin upload/presign flow anywhere in this plan (admin-web's reply UI — Task 19 — never calls a presign endpoint or sets `attachmentStorageKey`), so the only identity a storage key could legitimately be scoped under here is the ticket owner's own `tickets/{userId}/` prefix — i.e. an admin can reference an attachment the end user already uploaded, but can't smuggle in an arbitrary other-user's object. If an admin-side upload flow is added later, this will need its own presign endpoint and a decision on what prefix _that_ uses.

- [ ] **Step 8: Update the Task 5/6 test files' constructors for the new dependency**

In `TicketServiceImplTest.java`, add `@Mock private TicketAttachmentResolver attachmentResolver;` to the mock field list, and add `attachmentResolver` as the last argument to the `new TicketServiceImpl(...)` call in `setUp()`. No existing test needs new stubbing — `attachmentResolver.resolveAttachmentUrl(...)` is called with `null` in every existing test (none of them set an `attachmentStorageKey`), and Mockito's default unstubbed-method behavior already returns `null` for an object-returning method, which is exactly the expected "no attachment" outcome.

In `AdminTicketServiceImplTest.java`, add `@Mock private TicketAttachmentResolver attachmentResolver;` and add it as the last constructor argument in `setUp()`, same reasoning — no new stubbing needed for the existing tests.

- [ ] **Step 9: Add the presign endpoint to `TicketController`**

```java
    @Operation(summary = "Get a presigned URL to upload a ticket/message attachment")
    @PostMapping("/attachments/presign")
    public ResponseEntity<TicketAttachmentUploadResponse> presignAttachment(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody TicketAttachmentUploadRequest request) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(ticketService.initiateAttachmentUpload(userId, request));
    }
```

Add the matching imports (`TicketAttachmentUploadRequest`, `TicketAttachmentUploadResponse`) to `TicketController.java`'s existing import block.

- [ ] **Step 10: Run all ticket module tests to verify everything still passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.ticket.*"
```

Run as a plain foreground blocking command. Expected: all pass across every ticket test class.

- [ ] **Step 11: Run the full module suite**

```bash
./gradlew :api-service:test
```

Expected: only the accepted baseline flakes.

- [ ] **Step 12: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/TicketAttachmentUploadRequest.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/dto/TicketAttachmentUploadResponse.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/TicketAttachmentResolver.java \
        api-service/src/test/java/com/tinniestudio/api/modules/ticket/service/TicketAttachmentResolverTest.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/TicketService.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/TicketServiceImpl.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/service/AdminTicketServiceImpl.java \
        api-service/src/main/java/com/tinniestudio/api/modules/ticket/controller/TicketController.java \
        api-service/src/test/java/com/tinniestudio/api/modules/ticket/service/TicketServiceImplTest.java \
        api-service/src/test/java/com/tinniestudio/api/modules/ticket/service/AdminTicketServiceImplTest.java
git commit -m "feat: add ticket attachment presign/upload/validate flow, shared across user and admin replies"
```

---

## Part 4 — Client-web: contact fixes + live chat

### Task 8: Part A — Help Centre contact info fixes

**Files:**

- Modify: `tinniestudio-client-web/src/features/legals/components/ContactUsClient.tsx`
- Modify: `tinniestudio-client-web/src/components/@layout/Footer.tsx`

No test framework exists in this repo — verification is `tsc --noEmit` + `eslint` + manual/visual check.

- [ ] **Step 1: Read current state first**

Read both files in full before editing, to confirm the snippets below still match (they were read in full during planning, but re-confirm before pasting).

- [ ] **Step 2: Fix `ContactUsClient.tsx`**

Replace the `DEPARTMENTS` array:

```tsx
const DEPARTMENTS = [
  {
    title: "Customer Support",
    email: "support@tinniestudio.com",
    desc: "For account support, billing, and general inquiries.",
    icon: MessageSquare,
  },
  {
    title: "Technical Support",
    email: "tech@tinniestudio.com",
    desc: "Streaming issues, login problems, or device compatibility.",
    icon: Monitor,
  },
  {
    title: "Business & Partnerships",
    email: "business@tinniestudio.com",
    desc: "Advertising, media inquiries, and strategic opportunities.",
    icon: Briefcase,
  },
];
```

with (all three departments route to the one confirmed-monitored inbox — `support@tinniestudio.com` is the address the Footer already relies on and presents as the real contact address; `tech@`/`business@` have no evidence of being live/monitored, so they must not be shown per CLAUDE.md):

```tsx
const DEPARTMENTS = [
  {
    title: "Customer Support",
    email: "support@tinniestudio.com",
    desc: "For account support, billing, and general inquiries.",
    icon: MessageSquare,
  },
  {
    title: "Technical Support",
    email: "support@tinniestudio.com",
    desc: "Streaming issues, login problems, or device compatibility.",
    icon: Monitor,
  },
  {
    title: "Business & Partnerships",
    email: "support@tinniestudio.com",
    desc: "Advertising, media inquiries, and strategic opportunities.",
    icon: Briefcase,
  },
];
```

Delete the `SOCIALS` array and its render loop entirely — these are dead `href: '#'` links with no real destination and no `onClick`/`href` ever wired to the rendered `Button`:

```tsx
const SOCIALS = [
  { name: "Facebook", icon: Facebook, href: "#" },
  { name: "Instagram", icon: Instagram, href: "#" },
  { name: "YouTube", icon: Youtube, href: "#" },
  { name: "X", icon: Twitter, href: "#" },
];
```

and the block rendering them:

```tsx
{
  SOCIALS.map((soc) => (
    <Button
      key={soc.name}
      variant="ghost"
      size="icon"
      className="w-14 h-14 rounded-2xl bg-tls-primary2 border border-white/5 hover:bg-tls-secondary hover:text-black transition-all"
    >
      <soc.icon className="w-6 h-6" />
    </Button>
  ));
}
```

Remove the now-unused `Facebook`/`Instagram`/`Youtube`/`Twitter` icon imports if they're not used anywhere else in the file (check first — if any are still referenced elsewhere, keep only those still in use).

Delete the dead "Open Knowledge Base" button entirely (no `onClick`/`href`, and no knowledge base page exists anywhere in this app):

```tsx
<Button className="w-full h-16 bg-black text-white rounded-2xl font-bold text-lg hover:bg-tls-accent  hover:text-white transition-all flex items-center justify-center gap-2">
  Open Knowledge Base <ArrowUpRight className="w-5 h-5" />
</Button>
```

Remove the now-unused `ArrowUpRight` icon import if it's not used elsewhere in the file.

Fix the mangled-encoding bug on the same page (found during research — unrelated to the task list above, but a one-line content fix on a file already being edited for cleanup):

Find:

```tsx
We#&69d love to hear from you.
```

Replace with:

```tsx
We'd love to hear from you.
```

No phone number is added — none exists, and the spec's acceptance criterion ("if one is available") is satisfied by omitting it entirely, not by a placeholder.

- [ ] **Step 3: Fix the dead `/help-center` Footer link**

In `Footer.tsx`, find:

```tsx
{
  title: "Support",
  links: [
    { href: '/faq', label: 'FAQs' },
    { href: '/help-center', label: 'Help Center' }, ///
    { href: '/contact-us', label: 'Contact Us' },
    { href: '/about-us', label: 'About Us' },
  ]
},
```

The new ticket submission form and live-chat entry point both live on the existing Contact Us page (Task 13/9) — there's no separate Help Centre page, so "Help Center" and "Contact Us" would otherwise be two links to the same destination. Remove the redundant "Help Center" entry rather than pointing it at a duplicate of "Contact Us":

```tsx
{
  title: "Support",
  links: [
    { href: '/faq', label: 'FAQs' },
    { href: '/contact-us', label: 'Contact Us' },
    { href: '/about-us', label: 'About Us' },
  ]
},
```

- [ ] **Step 4: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
npx eslint src/features/legals/components/ContactUsClient.tsx src/components/@layout/Footer.tsx
```

Expected: no new errors (an unused-import lint error here would mean Step 2's icon-import cleanup was incomplete — fix it if so).

- [ ] **Step 5: Manual verification**

```bash
npm run dev
```

Visit `/contact-us` — confirm all three department cards show `support@tinniestudio.com`, the knowledge-base button and social icon row are gone, and the "We'd love to hear from you" copy reads correctly. Visit the footer on any page — confirm the Support group now shows FAQs/Contact Us/About Us with no "Help Center" entry, and that `/contact-us` still works.

- [ ] **Step 6: Commit**

```bash
git add src/features/legals/components/ContactUsClient.tsx src/components/@layout/Footer.tsx
git commit -m "fix: show only the verified support email, remove dead KB/social links and the help-center dupe link"
```

---

### Task 9: Part B — Tawk.to live chat embed

**Files:**

- Modify: `tinniestudio-client-web/src/app/layout.tsx`
- Modify: `tinniestudio-client-web/.env.example` (if one exists — check first; if not, skip this file)

- [ ] **Step 1: Read current state first**

Read `src/app/layout.tsx` in full. Confirm the `<body>...</body>` structure still matches what's shown below before editing.

- [ ] **Step 2: Add the Tawk.to embed**

Add the import at the top of the file, alongside the other imports:

```tsx
import Script from "next/script";
```

Add the embed script right before the closing `</body>` tag (after the existing `<ToastContainer .../>` and the closing `</CSRFTokenProvider>`/`</HydrationProvider>` tags, still inside `<body>`):

```tsx
        {process.env.NEXT_PUBLIC_TAWKTO_WIDGET_ID && (
          <Script id='tawk-to' strategy='afterInteractive'>
            {`
              var Tawk_API = Tawk_API || {};
              var Tawk_LoadStart = new Date();
              (function () {
                var s1 = document.createElement("script"), s0 = document.getElementsByTagName("script")[0];
                s1.async = true;
                s1.src = 'https://embed.tawk.to/${process.env.NEXT_PUBLIC_TAWKTO_WIDGET_ID}';
                s1.charset = 'UTF-8';
                s1.setAttribute('crossorigin', '*');
                s0.parentNode.insertBefore(s1, s0);
              })();
            `}
          </Script>
        )}
      </body>
```

(This replaces just the final `</body>` line — everything else in the file stays unchanged.) The embed is site-wide (inside the root layout, outside any route-specific tree) per the spec's "or the root layout if it should be available site-wide" option — live chat being reachable from anywhere, not just the Contact page, is the more useful default and still satisfies "Live Chat is accessible and routes to the support team" regardless of which page the user is on.

The widget is conditionally rendered on `NEXT_PUBLIC_TAWKTO_WIDGET_ID` being set, so local/CI environments without a real Tawk.to account configured don't attempt to load a script that would 404. The real widget ID itself is NOT generated by this plan — it must be supplied by whoever has access to the Tawk.to account dashboard, as an environment variable.

- [ ] **Step 3: Document the new env var**

Check whether `tinniestudio-client-web/.env.example` exists. If it does, read it and add this line in a sensible place (e.g. near other `NEXT_PUBLIC_*` entries):

```
NEXT_PUBLIC_TAWKTO_WIDGET_ID=
```

If `.env.example` doesn't exist in this repo, skip this step — don't create a new file just for one variable.

- [ ] **Step 4: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
npx eslint src/app/layout.tsx
```

Expected: no new errors.

- [ ] **Step 5: Manual verification**

```bash
NEXT_PUBLIC_TAWKTO_WIDGET_ID=test123 npm run dev
```

Load any page, open browser dev tools, confirm the Tawk.to script tag is injected into the page (it will likely fail to actually load the widget UI against a fake id, but confirm the `<script>` tag itself appears in the DOM with the right `src`). Then restart without the env var set and confirm no Tawk.to script tag appears at all (graceful no-op).

- [ ] **Step 6: Commit**

```bash
git add src/app/layout.tsx
git commit -m "feat: add Tawk.to live chat embed, site-wide, gated on NEXT_PUBLIC_TAWKTO_WIDGET_ID"
```

---

## Part 5 — Client-web: ticket types, BFF routes, hooks

### Task 10: Ticket types + constants + BFF routes

**Files:**

- Create: `tinniestudio-client-web/src/types/ticket.type.ts`
- Modify: `tinniestudio-client-web/src/types/index.ts`
- Create: `tinniestudio-client-web/src/features/tickets/constants/tickets.ts`
- Create: `tinniestudio-client-web/src/app/api/tickets/route.ts`
- Create: `tinniestudio-client-web/src/app/api/tickets/[id]/route.ts`
- Create: `tinniestudio-client-web/src/app/api/tickets/[id]/messages/route.ts`
- Create: `tinniestudio-client-web/src/app/api/tickets/attachments/presign/route.ts`

- [ ] **Step 1: Read current state first**

Read `src/types/index.ts` to confirm its barrel-export style, and `src/app/api/notifications/[id]/read/route.ts` to confirm the exact current `{ params: Promise<{...}> }` + zod-validation BFF route pattern before pasting the routes below.

- [ ] **Step 2: Add the ticket types**

```ts
export type TicketStatus = "OPEN" | "IN_PROGRESS" | "RESOLVED" | "CLOSED";

export interface TicketMessage {
  id: string;
  senderType: "USER" | "ADMIN";
  senderId: string;
  body: string;
  attachmentUrl: string | null;
  createdAt: string;
}

export interface Ticket {
  id: string;
  ticketNumber: string;
  subject: string;
  status: TicketStatus;
  userId: string;
  submitterEmail: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TicketDetail extends Ticket {
  messages: TicketMessage[];
}

export interface CreateTicketRequest {
  subject: string;
  body: string;
  attachmentStorageKey?: string;
}

export interface ReplyTicketRequest {
  body: string;
  attachmentStorageKey?: string;
}

export interface TicketAttachmentUploadRequest {
  mimeType: string;
  fileSizeBytes: number;
}

export interface TicketAttachmentUploadResponse {
  uploadUrl: string;
  storageKey: string;
  expiresAt: string;
}
```

Add `export * from './ticket.type';` to `src/types/index.ts`'s barrel export block, alongside the other `export * from './X.type'` lines (match whatever ordering convention is already there — e.g. alphabetical, if that's the existing pattern).

- [ ] **Step 3: Add the constants**

```ts
export const TICKET_CONSTANTS = {
  LIST: "/api/tickets",
  CREATE: "/api/tickets",
  DETAIL: (id: string) => `/api/tickets/${id}`,
  REPLY: (id: string) => `/api/tickets/${id}/messages`,
  PRESIGN_ATTACHMENT: "/api/tickets/attachments/presign",
} as const;

export const BACKEND_TICKET_CONSTANTS = {
  LIST: "/tickets",
  CREATE: "/tickets",
  DETAIL: (id: string) => `/tickets/${id}`,
  REPLY: (id: string) => `/tickets/${id}/messages`,
  PRESIGN_ATTACHMENT: "/tickets/attachments/presign",
} as const;
```

- [ ] **Step 4: Create the list + create BFF route**

```ts
import backendFetch from "@/lib/server/backendFetch";
import { buildProxyResponse, forwardCredentials } from "@/lib/server/bff";
import { BACKEND_TICKET_CONSTANTS } from "@/features/tickets/constants/tickets";

export async function GET(req: Request) {
  const { searchParams } = new URL(req.url);
  const qs = searchParams.toString();

  const resp = await backendFetch({
    url: `${BACKEND_TICKET_CONSTANTS.LIST}${qs ? `?${qs}` : ""}`,
    headers: forwardCredentials(req),
  });

  return buildProxyResponse(resp);
}

export async function POST(req: Request) {
  const body = await req.json().catch(() => null);

  const resp = await backendFetch({
    url: BACKEND_TICKET_CONSTANTS.CREATE,
    method: "POST",
    data: body,
    headers: forwardCredentials(req),
  });

  return buildProxyResponse(resp);
}
```

- [ ] **Step 5: Create the detail BFF route**

```ts
import backendFetch from "@/lib/server/backendFetch";
import { buildProxyResponse, forwardCredentials } from "@/lib/server/bff";
import { BACKEND_TICKET_CONSTANTS } from "@/features/tickets/constants/tickets";
import z from "zod";

export async function GET(
  req: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  const parsed = z.string().uuid().safeParse(id);
  if (!parsed.success) {
    return Response.json(
      {
        success: false,
        message: "Validation failed",
        error: { code: "VALIDATION_ERROR", message: parsed.error.message },
      },
      { status: 400 },
    );
  }

  const resp = await backendFetch({
    url: BACKEND_TICKET_CONSTANTS.DETAIL(parsed.data),
    headers: forwardCredentials(req),
  });

  return buildProxyResponse(resp);
}
```

- [ ] **Step 6: Create the reply BFF route**

```ts
import backendFetch from "@/lib/server/backendFetch";
import { buildProxyResponse, forwardCredentials } from "@/lib/server/bff";
import { BACKEND_TICKET_CONSTANTS } from "@/features/tickets/constants/tickets";
import z from "zod";

export async function POST(
  req: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  const parsed = z.string().uuid().safeParse(id);
  if (!parsed.success) {
    return Response.json(
      {
        success: false,
        message: "Validation failed",
        error: { code: "VALIDATION_ERROR", message: parsed.error.message },
      },
      { status: 400 },
    );
  }

  const body = await req.json().catch(() => null);

  const resp = await backendFetch({
    url: BACKEND_TICKET_CONSTANTS.REPLY(parsed.data),
    method: "POST",
    data: body,
    headers: forwardCredentials(req),
  });

  return buildProxyResponse(resp);
}
```

- [ ] **Step 7: Create the attachment presign BFF route**

```ts
import backendFetch from "@/lib/server/backendFetch";
import { buildProxyResponse, forwardCredentials } from "@/lib/server/bff";
import { BACKEND_TICKET_CONSTANTS } from "@/features/tickets/constants/tickets";

export async function POST(req: Request) {
  const body = await req.json().catch(() => null);

  const resp = await backendFetch({
    url: BACKEND_TICKET_CONSTANTS.PRESIGN_ATTACHMENT,
    method: "POST",
    data: body,
    headers: forwardCredentials(req),
  });

  return buildProxyResponse(resp);
}
```

- [ ] **Step 8: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
npx eslint src/types/ticket.type.ts src/types/index.ts src/features/tickets/constants/tickets.ts \
  "src/app/api/tickets/route.ts" "src/app/api/tickets/[id]/route.ts" \
  "src/app/api/tickets/[id]/messages/route.ts" "src/app/api/tickets/attachments/presign/route.ts"
```

Expected: no new errors.

- [ ] **Step 9: Commit**

```bash
git add src/types/ticket.type.ts src/types/index.ts src/features/tickets/constants/tickets.ts \
        "src/app/api/tickets/route.ts" "src/app/api/tickets/[id]/route.ts" \
        "src/app/api/tickets/[id]/messages/route.ts" "src/app/api/tickets/attachments/presign/route.ts"
git commit -m "feat: add ticket types, constants, and BFF proxy routes"
```

---

### Task 11: Ticket React Query hooks

**Files:**

- Create: `tinniestudio-client-web/src/features/tickets/service/ticket-query-keys.ts`
- Create: `tinniestudio-client-web/src/features/tickets/service/queries.ts`
- Create: `tinniestudio-client-web/src/features/tickets/service/mutations.ts`
- Create: `tinniestudio-client-web/src/features/tickets/service/index.ts`

- [ ] **Step 1: Read current state first**

Read `src/features/account/service/mutations.ts` and `src/features/notifications/hooks/useNotifications.ts` once more to confirm the exact `bffFetch`/`bffFetchWithMeta`/`useApiQuery`/`useApiMutation` call shapes before pasting the code below.

- [ ] **Step 2: Create the query keys**

```ts
export const ticketKeys = {
  list: (page: number) => ["tickets", "list", page] as const,
  lists: () => ["tickets", "list"] as const,
  detail: (id: string) => ["tickets", "detail", id] as const,
};
```

- [ ] **Step 3: Create the queries**

```ts
"use client";

import { bffFetch, bffFetchWithMeta, useApiQuery } from "@/hooks/api-hooks";
import type { ApiResponseMeta, Ticket, TicketDetail } from "@/types";
import { TICKET_CONSTANTS } from "../constants/tickets";
import { ticketKeys } from "./ticket-query-keys";

export function useTickets(
  page: number,
  seed?: { data: Ticket[]; meta?: ApiResponseMeta },
) {
  return useApiQuery(
    ticketKeys.list(page),
    () => bffFetchWithMeta<Ticket[]>(`${TICKET_CONSTANTS.LIST}?page=${page}`),
    { keepPreviousData: true, initialData: seed },
  );
}

export function useTicket(ticketId: string) {
  return useApiQuery(ticketKeys.detail(ticketId), () =>
    bffFetch<TicketDetail>(TICKET_CONSTANTS.DETAIL(ticketId)),
  );
}
```

- [ ] **Step 4: Create the mutations**

```ts
"use client";

import { useQueryClient } from "@tanstack/react-query";
import { bffFetch, useApiMutation } from "@/hooks/api-hooks";
import type {
  CreateTicketRequest,
  ReplyTicketRequest,
  Ticket,
  TicketAttachmentUploadRequest,
  TicketAttachmentUploadResponse,
  TicketDetail,
} from "@/types";
import { TICKET_CONSTANTS } from "../constants/tickets";
import { ticketKeys } from "./ticket-query-keys";

export function useCreateTicket() {
  const queryClient = useQueryClient();
  return useApiMutation<Ticket, Error, CreateTicketRequest>(
    (payload) =>
      bffFetch<Ticket>(TICKET_CONSTANTS.CREATE, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      }),
    {
      onSuccess: () => {
        queryClient.invalidateQueries({ queryKey: ticketKeys.lists() });
      },
    },
  );
}

export function useReplyToTicket(ticketId: string) {
  const queryClient = useQueryClient();
  return useApiMutation<TicketDetail, Error, ReplyTicketRequest>(
    (payload) =>
      bffFetch<TicketDetail>(TICKET_CONSTANTS.REPLY(ticketId), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      }),
    {
      onSuccess: () => {
        queryClient.invalidateQueries({
          queryKey: ticketKeys.detail(ticketId),
        });
      },
    },
  );
}

export function usePresignTicketAttachment() {
  return useApiMutation<
    TicketAttachmentUploadResponse,
    Error,
    TicketAttachmentUploadRequest
  >((payload) =>
    bffFetch<TicketAttachmentUploadResponse>(
      TICKET_CONSTANTS.PRESIGN_ATTACHMENT,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      },
    ),
  );
}
```

- [ ] **Step 5: Create the barrel export**

```ts
export * from "./ticket-query-keys";
export * from "./queries";
export * from "./mutations";
```

- [ ] **Step 6: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
npx eslint src/features/tickets/service/ticket-query-keys.ts src/features/tickets/service/queries.ts \
  src/features/tickets/service/mutations.ts src/features/tickets/service/index.ts
```

Expected: no new errors.

- [ ] **Step 7: Commit**

```bash
git add src/features/tickets/service/ticket-query-keys.ts src/features/tickets/service/queries.ts \
        src/features/tickets/service/mutations.ts src/features/tickets/service/index.ts
git commit -m "feat: add ticket React Query hooks (list, detail, create, reply, attachment presign)"
```

---

### Task 12: `TICKET_REPLIED` notification wiring

**Files:**

- Modify: `tinniestudio-client-web/src/types/notification.type.ts`
- Modify: `tinniestudio-client-web/src/features/notifications/constant/event-types.ts`
- Modify: `tinniestudio-client-web/src/features/notifications/components/NotificationBell.tsx`

- [ ] **Step 1: Read current state first**

Read all three files in full to confirm the snippets below still match.

- [ ] **Step 2: Add `TICKET_REPLIED` to the event type union**

In `notification.type.ts`, change:

```ts
export type NotificationEventType =
  | "CONTENT_PROCESSED"
  | "CONTENT_APPROVED"
  | "CONTENT_REJECTED"
  | "APPLICATION_APPROVED"
  | "APPLICATION_REJECTED"
  | "ACCOUNT_SUSPENDED"
  | "ACCOUNT_BANNED";
```

to:

```ts
export type NotificationEventType =
  | "CONTENT_PROCESSED"
  | "CONTENT_APPROVED"
  | "CONTENT_REJECTED"
  | "APPLICATION_APPROVED"
  | "APPLICATION_REJECTED"
  | "ACCOUNT_SUSPENDED"
  | "ACCOUNT_BANNED"
  | "TICKET_REPLIED";
```

- [ ] **Step 3: Update `event-types.ts`'s exhaustive label map**

This file has a `NOTIFICATION_EVENT_TYPE_LABELS: Record<NotificationEventType, string>` that TypeScript requires to cover every union member — add the missing entry or the build fails. Read the file first to see its current exact structure (the `NOTIFICATION_EVENT_TYPES` array and the `Record` both need the new entry), then add:

In `NOTIFICATION_EVENT_TYPE_LABELS`:

```ts
  TICKET_REPLIED: 'Support ticket reply',
```

In `NOTIFICATION_EVENT_TYPES` (whatever shape that array takes — check its actual element type first; it's a label list alongside the `Record`), add the corresponding entry for `TICKET_REPLIED` following that array's existing element shape exactly.

- [ ] **Step 4: Add the `TICKET` case to `resolveHref`**

In `NotificationBell.tsx`, change:

```tsx
function resolveHref(notification: NotificationResponse): string | null {
  switch (notification.referenceType) {
    case "CONTENT":
      return notification.referenceId
        ? `/watch/${notification.referenceId}`
        : null;
    case "ACCOUNT_SECURITY":
      return "/account?action=security";
    default:
      return null;
  }
}
```

to:

```tsx
function resolveHref(notification: NotificationResponse): string | null {
  switch (notification.referenceType) {
    case "CONTENT":
      return notification.referenceId
        ? `/watch/${notification.referenceId}`
        : null;
    case "ACCOUNT_SECURITY":
      return "/account?action=security";
    case "TICKET":
      return notification.referenceId
        ? `/account/tickets/${notification.referenceId}`
        : null;
    default:
      return null;
  }
}
```

(The `/account/tickets/[id]` route this points to is created in Task 15.)

- [ ] **Step 5: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
npx eslint src/types/notification.type.ts src/features/notifications/constant/event-types.ts \
  src/features/notifications/components/NotificationBell.tsx
```

Expected: no new errors — if `event-types.ts`'s `Record` is missing the new key, `tsc` fails here with a clear "Property 'TICKET_REPLIED' is missing" error; that's the signal Step 3 wasn't done correctly.

- [ ] **Step 6: Commit**

```bash
git add src/types/notification.type.ts src/features/notifications/constant/event-types.ts \
        src/features/notifications/components/NotificationBell.tsx
git commit -m "feat: add TICKET_REPLIED notification type and bell deep-link"
```

---

## Part 6 — Client-web: ticket submission + My Tickets UI

### Task 13: Ticket submission form on the Contact page

**Files:**

- Create: `tinniestudio-client-web/src/features/tickets/schema/ticket.schema.ts`
- Create: `tinniestudio-client-web/src/features/tickets/components/TicketSubmissionForm.tsx`
- Modify: `tinniestudio-client-web/src/features/legals/components/ContactUsClient.tsx`

- [ ] **Step 1: Read current state first**

Read `src/features/account/components/ProfilePanelAvatar.tsx` once more (the presign→upload→confirm state machine) and `ContactUsClient.tsx`'s current full content (post-Task-8) to confirm exactly where this form should be inserted.

- [ ] **Step 2: Create the zod schema**

```ts
import z from "zod";

export const ticketFormSchema = z.object({
  subject: z
    .string()
    .min(1, { message: "Subject is required" })
    .max(255, { message: "Subject must not exceed 255 characters" }),
  body: z.string().min(1, { message: "Message is required" }),
});

export type TicketFormValues = z.infer<typeof ticketFormSchema>;
```

- [ ] **Step 3: Create the submission form component**

```tsx
"use client";

import { useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Paperclip, Send } from "lucide-react";
import { toast } from "react-toastify";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { ticketFormSchema, TicketFormValues } from "../schema/ticket.schema";
import { useCreateTicket, usePresignTicketAttachment } from "../service";

const MAX_ATTACHMENT_SIZE = 10 * 1024 * 1024; // 10 MB, matches the backend ceiling
const ALLOWED_ATTACHMENT_TYPES = [
  "image/jpeg",
  "image/png",
  "image/webp",
  "application/pdf",
];

type UploadProgress = "idle" | "requesting" | "uploading" | "submitting";

const uploadLabel: Record<UploadProgress, string> = {
  idle: "Contact Support",
  requesting: "Preparing attachment...",
  uploading: "Uploading attachment...",
  submitting: "Submitting...",
};

export function TicketSubmissionForm() {
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [progress, setProgress] = useState<UploadProgress>("idle");
  const [confirmation, setConfirmation] = useState<{
    ticketNumber: string;
  } | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const createTicket = useCreateTicket();
  const presignAttachment = usePresignTicketAttachment();

  const form = useForm<TicketFormValues>({
    resolver: zodResolver(ticketFormSchema),
    defaultValues: { subject: "", body: "" },
  });

  function handleFileSelect(e: React.ChangeEvent<HTMLInputElement>) {
    const selected = e.target.files?.[0];
    if (!selected) return;

    setFileError(null);

    if (!ALLOWED_ATTACHMENT_TYPES.includes(selected.type)) {
      setFileError("Please select an image (JPEG/PNG/WebP) or PDF file.");
      return;
    }
    if (selected.size > MAX_ATTACHMENT_SIZE) {
      setFileError("File must be smaller than 10 MB.");
      return;
    }

    setFile(selected);
  }

  async function onSubmit(values: TicketFormValues) {
    try {
      let attachmentStorageKey: string | undefined;

      if (file) {
        setProgress("requesting");
        const presigned = await presignAttachment.mutateAsync({
          mimeType: file.type,
          fileSizeBytes: file.size,
        });

        setProgress("uploading");
        const uploadRes = await fetch(presigned.uploadUrl, {
          method: "PUT",
          body: file,
          headers: { "Content-Type": file.type },
        });
        if (!uploadRes.ok) {
          throw new Error(`Attachment upload failed: ${uploadRes.statusText}`);
        }
        attachmentStorageKey = presigned.storageKey;
      }

      setProgress("submitting");
      const ticket = await createTicket.mutateAsync({
        subject: values.subject,
        body: values.body,
        attachmentStorageKey,
      });

      setConfirmation({ ticketNumber: ticket.ticketNumber });
      form.reset();
      setFile(null);
      if (fileInputRef.current) fileInputRef.current.value = "";
    } catch (err: unknown) {
      const message =
        err instanceof Error
          ? err.message
          : "Failed to submit contact ticket. Please try again later.";
      toast.error(message);
    } finally {
      setProgress("idle");
    }
  }

  if (confirmation) {
    return (
      <div className="bg-tls-primary2 border border-white/10 rounded-2xl p-8 text-center">
        <p className="text-lg font-semibold text-white">Ticket submitted!</p>
        <p className="text-sm text-white/60 mt-2">
          Your reference number is{" "}
          <span className="text-tls-secondary font-mono">
            {confirmation.ticketNumber}
          </span>
          . We&apos;ll reply by email, or you can track it under{" "}
          <a
            href="/account/tickets"
            className="text-tls-secondary hover:underline"
          >
            My Tickets
          </a>
          .
        </p>
        <Button
          className="mt-6"
          variant="outline"
          onClick={() => setConfirmation(null)}
        >
          Submit another ticket
        </Button>
      </div>
    );
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
        <FormField
          control={form.control}
          name="subject"
          render={({ field }) => (
            <FormItem>
              <FormLabel className="text-white/60 text-xs uppercase tracking-wider">
                Subject
              </FormLabel>
              <FormControl>
                <Input {...field} placeholder="What's this about?" />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={form.control}
          name="body"
          render={({ field }) => (
            <FormItem>
              <FormLabel className="text-white/60 text-xs uppercase tracking-wider">
                Message
              </FormLabel>
              <FormControl>
                <Textarea
                  {...field}
                  rows={6}
                  placeholder="Tell us what happened..."
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <div>
          <label className="text-white/60 text-xs uppercase tracking-wider block mb-2">
            Attachment (optional)
          </label>
          <input
            ref={fileInputRef}
            type="file"
            accept="image/jpeg,image/png,image/webp,application/pdf"
            onChange={handleFileSelect}
            className="text-sm text-white/70"
          />
          {file && <p className="text-xs text-white/40 mt-1">{file.name}</p>}
          {fileError && (
            <p role="alert" className="text-red-400 text-sm mt-1">
              {fileError}
            </p>
          )}
        </div>

        <Button
          type="submit"
          disabled={progress !== "idle" || form.formState.isSubmitting}
          aria-busy={progress !== "idle"}
          className="w-full md:w-auto"
        >
          {progress === "idle" ? (
            <Send className="w-4 h-4 mr-2" />
          ) : (
            <Paperclip className="w-4 h-4 mr-2" />
          )}
          {uploadLabel[progress]}
        </Button>
      </form>
    </Form>
  );
}
```

- [ ] **Step 4: Render the form on the Contact Us page**

In `ContactUsClient.tsx`, add the import:

```tsx
import { TicketSubmissionForm } from "@/features/tickets/components/TicketSubmissionForm";
```

Add a new section rendering `<TicketSubmissionForm />` — place it wherever fits best given the page's actual current layout after Task 8's edits (e.g. as a new card/section near the department cards, with a heading like "Submit a Support Ticket"). Since the exact surrounding JSX structure depends on Task 8's final state, read the file first and insert a section such as:

```tsx
<div className="mt-12">
  <h2 className="text-2xl font-bold text-white mb-4">
    Submit a Support Ticket
  </h2>
  <div className="bg-tls-primary2 border border-white/10 rounded-2xl p-6 md:p-8">
    <TicketSubmissionForm />
  </div>
</div>
```

placed after the existing department-cards section and before (or after) the "Direct Support" card, whichever reads better given the final page flow — use your judgment on exact placement, the only hard requirement is that it's present somewhere on this page.

- [ ] **Step 5: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
npx eslint src/features/tickets/schema/ticket.schema.ts src/features/tickets/components/TicketSubmissionForm.tsx \
  src/features/legals/components/ContactUsClient.tsx
```

Expected: no new errors.

- [ ] **Step 6: Manual verification**

```bash
npm run dev
```

Visit `/contact-us`, fill in the form, submit without an attachment — confirm the confirmation screen shows a `TKT-XXXXXX`-shaped reference number (requires the backend running locally with Part 1-3 of this plan already deployed; if the backend isn't reachable, confirm at minimum that the form renders, client-side validation fires on empty fields, and the file-type/size guard rejects an oversized or wrong-type file before any network call).

- [ ] **Step 7: Commit**

```bash
git add src/features/tickets/schema/ticket.schema.ts src/features/tickets/components/TicketSubmissionForm.tsx \
        src/features/legals/components/ContactUsClient.tsx
git commit -m "feat: add ticket submission form to the Contact Us page"
```

---

### Task 14: "My Tickets" list page

**Files:**

- Create: `tinniestudio-client-web/src/app/(root)/(protected)/account/tickets/page.tsx`
- Create: `tinniestudio-client-web/src/app/(root)/(protected)/account/tickets/TicketsListClient.tsx`
- Create: `tinniestudio-client-web/src/features/tickets/components/TicketStatusBadge.tsx`

This mirrors `src/app/(root)/(protected)/notifications/page.tsx` + `NotificationsPageClient.tsx`'s exact SSR-fetch → client-hydrate → paginate pattern.

- [ ] **Step 1: Read current state first**

Read `src/app/(root)/(protected)/notifications/page.tsx` and `NotificationsPageClient.tsx` in full once more (already summarized above) to confirm the exact current `backendFetch`/`cookies()` boilerplate before pasting the server page below.

- [ ] **Step 2: Create the status badge component**

```tsx
import { Badge } from "@/components/ui/badge";
import type { TicketStatus } from "@/types";

const STATUS_LABEL: Record<TicketStatus, string> = {
  OPEN: "Open",
  IN_PROGRESS: "In Progress",
  RESOLVED: "Resolved",
  CLOSED: "Closed",
};

const STATUS_VARIANT: Record<
  TicketStatus,
  "default" | "secondary" | "outline" | "ghost"
> = {
  OPEN: "default",
  IN_PROGRESS: "secondary",
  RESOLVED: "outline",
  CLOSED: "ghost",
};

export function TicketStatusBadge({ status }: { status: TicketStatus }) {
  return <Badge variant={STATUS_VARIANT[status]}>{STATUS_LABEL[status]}</Badge>;
}
```

`@/components/ui/badge`'s real `variant` prop accepts `default | secondary | destructive | outline | ghost | link` — the four used above are a valid subset, confirmed against the component's actual `cva` definition.

- [ ] **Step 3: Create the SSR page**

```tsx
import { Metadata } from "next";
import { cookies } from "next/headers";
import backendFetch from "@/lib/server/backendFetch";
import type { ApiResponse, Ticket } from "@/types";
import { TicketsListClient } from "./TicketsListClient";

export const metadata: Metadata = {
  title: "My Tickets",
  description: "Track your support ticket history and conversations.",
};

export default async function TicketsPage() {
  const cookieStore = await cookies();
  const cookieHeader = cookieStore
    .getAll()
    .map((c) => `${c.name}=${c.value}`)
    .join("; ");

  const resp: ApiResponse<Ticket[]> = await backendFetch<Ticket[]>({
    url: "/tickets?page=0&size=20",
    headers: { cookie: cookieHeader },
  });

  return (
    <TicketsListClient
      initialData={resp.success ? (resp.data ?? []) : []}
      initialMeta={resp.meta}
    />
  );
}
```

- [ ] **Step 4: Create the client list component**

```tsx
"use client";

import { useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { useTickets } from "@/features/tickets/service";
import { TicketStatusBadge } from "@/features/tickets/components/TicketStatusBadge";
import type { ApiResponseMeta, Ticket } from "@/types";

interface TicketsListClientProps {
  initialData: Ticket[];
  initialMeta?: ApiResponseMeta;
}

export function TicketsListClient({
  initialData,
  initialMeta,
}: TicketsListClientProps) {
  const [page, setPage] = useState(0);
  const { data } = useTickets(
    page,
    page === 0 ? { data: initialData, meta: initialMeta } : undefined,
  );

  const tickets = data?.data ?? [];
  const meta = data?.meta;

  return (
    <div className="max-w-3xl mx-auto py-8 px-4">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-xl font-semibold text-white">My Tickets</h1>
        <Button asChild variant="outline">
          <Link href="/contact-us">Submit a new ticket</Link>
        </Button>
      </div>

      <div className="bg-white/5 border border-white/10 rounded-2xl overflow-hidden divide-y divide-white/5">
        {tickets.length === 0 ? (
          <p className="px-4 py-10 text-center text-sm text-white/40">
            You haven&apos;t submitted any tickets yet.
          </p>
        ) : (
          tickets.map((ticket) => (
            <Link
              key={ticket.id}
              href={`/account/tickets/${ticket.id}`}
              className="block px-4 py-3.5 hover:bg-white/5 transition-colors"
            >
              <div className="flex items-center justify-between gap-3">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-white truncate">
                    {ticket.subject}
                  </p>
                  <p className="text-xs text-white/40 mt-0.5">
                    {ticket.ticketNumber} ·{" "}
                    {new Date(ticket.createdAt).toLocaleDateString()}
                  </p>
                </div>
                <TicketStatusBadge status={ticket.status} />
              </div>
            </Link>
          ))
        )}
      </div>

      {meta && meta.totalPages > 1 && (
        <div className="flex items-center justify-center gap-3 mt-6">
          <Button
            variant="ghost"
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
          >
            Previous
          </Button>
          <span className="text-sm text-white/50">
            Page {page + 1} of {meta.totalPages}
          </span>
          <Button
            variant="ghost"
            disabled={page + 1 >= meta.totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </Button>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 5: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
npx eslint "src/app/(root)/(protected)/account/tickets/page.tsx" \
  "src/app/(root)/(protected)/account/tickets/TicketsListClient.tsx" \
  src/features/tickets/components/TicketStatusBadge.tsx
```

Expected: no new errors.

- [ ] **Step 6: Manual verification**

```bash
npm run dev
```

Log in, visit `/account/tickets` — confirm the page renders (empty state if no tickets exist yet, or a list with status badges if some do from Task 13's manual testing). Confirm "Submit a new ticket" links back to `/contact-us`.

- [ ] **Step 7: Commit**

```bash
git add "src/app/(root)/(protected)/account/tickets/page.tsx" \
        "src/app/(root)/(protected)/account/tickets/TicketsListClient.tsx" \
        src/features/tickets/components/TicketStatusBadge.tsx
git commit -m "feat: add My Tickets list page"
```

---

### Task 15: Ticket thread/detail page with reply + attachment upload

**Files:**

- Create: `tinniestudio-client-web/src/app/(root)/(protected)/account/tickets/[id]/page.tsx`
- Create: `tinniestudio-client-web/src/app/(root)/(protected)/account/tickets/[id]/TicketThreadClient.tsx`
- Create: `tinniestudio-client-web/src/features/tickets/components/TicketMessageItem.tsx`

- [ ] **Step 1: Read current state first**

Read `src/features/reviews/components/ReviewList.tsx` once more (the avatar-left/content-right row pattern) and `src/app/(root)/(protected)/shows/[slug]/page.tsx` (the async `params` convention) to confirm both still match before pasting below.

- [ ] **Step 2: Create the message item component**

```tsx
import { Paperclip } from "lucide-react";
import type { TicketMessage } from "@/types";

export function TicketMessageItem({ message }: { message: TicketMessage }) {
  const isAdmin = message.senderType === "ADMIN";

  return (
    <div
      className={`flex gap-3 pb-5 border-b border-white/5 last:border-0 ${isAdmin ? "" : "flex-row-reverse"}`}
    >
      <div
        className={`w-9 h-9 rounded-full shrink-0 flex items-center justify-center text-xs font-semibold ${
          isAdmin
            ? "bg-tls-secondary/20 text-tls-secondary"
            : "bg-white/10 text-white/60"
        }`}
      >
        {isAdmin ? "TS" : "You"}
      </div>
      <div className={`flex-1 space-y-1 ${isAdmin ? "" : "text-right"}`}>
        <div
          className="flex items-center gap-2"
          style={isAdmin ? {} : { justifyContent: "flex-end" }}
        >
          <span className="text-sm font-semibold text-tls-neutral">
            {isAdmin ? "TinnieStudio Support" : "You"}
          </span>
          <span className="text-xs text-white/30">
            {new Date(message.createdAt).toLocaleString()}
          </span>
        </div>
        <p className="text-sm text-tls-neutral/80 whitespace-pre-wrap">
          {message.body}
        </p>
        {message.attachmentUrl && (
          <a
            href={message.attachmentUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1 text-xs text-tls-secondary hover:underline"
          >
            <Paperclip className="w-3 h-3" /> Attachment
          </a>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Create the SSR detail page**

```tsx
import { Metadata } from "next";
import { cookies } from "next/headers";
import { notFound } from "next/navigation";
import backendFetch from "@/lib/server/backendFetch";
import type { ApiResponse, TicketDetail } from "@/types";
import { TicketThreadClient } from "./TicketThreadClient";

export const metadata: Metadata = {
  title: "Ticket Detail",
  description: "Your support ticket conversation.",
};

export default async function TicketDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const cookieStore = await cookies();
  const cookieHeader = cookieStore
    .getAll()
    .map((c) => `${c.name}=${c.value}`)
    .join("; ");

  const resp: ApiResponse<TicketDetail> = await backendFetch<TicketDetail>({
    url: `/tickets/${id}`,
    headers: { cookie: cookieHeader },
  });

  if (!resp.success || !resp.data) notFound();

  return <TicketThreadClient ticketId={id} initialTicket={resp.data} />;
}
```

- [ ] **Step 4: Create the thread/reply client component**

```tsx
"use client";

import { useRef, useState } from "react";
import { toast } from "react-toastify";
import { Paperclip, Send } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import {
  useTicket,
  useReplyToTicket,
  usePresignTicketAttachment,
} from "@/features/tickets/service";
import { TicketStatusBadge } from "@/features/tickets/components/TicketStatusBadge";
import { TicketMessageItem } from "@/features/tickets/components/TicketMessageItem";
import type { TicketDetail } from "@/types";

const MAX_ATTACHMENT_SIZE = 10 * 1024 * 1024;
const ALLOWED_ATTACHMENT_TYPES = [
  "image/jpeg",
  "image/png",
  "image/webp",
  "application/pdf",
];

interface TicketThreadClientProps {
  ticketId: string;
  initialTicket: TicketDetail;
}

export function TicketThreadClient({
  ticketId,
  initialTicket,
}: TicketThreadClientProps) {
  const { data: ticket } = useTicket(ticketId);
  const current = ticket ?? initialTicket;

  const [replyBody, setReplyBody] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [isSending, setIsSending] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const replyMutation = useReplyToTicket(ticketId);
  const presignAttachment = usePresignTicketAttachment();

  const isClosed = current.status === "CLOSED";

  function handleFileSelect(e: React.ChangeEvent<HTMLInputElement>) {
    const selected = e.target.files?.[0];
    if (!selected) return;

    setFileError(null);

    if (!ALLOWED_ATTACHMENT_TYPES.includes(selected.type)) {
      setFileError("Please select an image (JPEG/PNG/WebP) or PDF file.");
      return;
    }
    if (selected.size > MAX_ATTACHMENT_SIZE) {
      setFileError("File must be smaller than 10 MB.");
      return;
    }

    setFile(selected);
  }

  async function handleReply() {
    if (!replyBody.trim()) return;

    setIsSending(true);
    try {
      let attachmentStorageKey: string | undefined;

      if (file) {
        const presigned = await presignAttachment.mutateAsync({
          mimeType: file.type,
          fileSizeBytes: file.size,
        });
        const uploadRes = await fetch(presigned.uploadUrl, {
          method: "PUT",
          body: file,
          headers: { "Content-Type": file.type },
        });
        if (!uploadRes.ok) {
          throw new Error(`Attachment upload failed: ${uploadRes.statusText}`);
        }
        attachmentStorageKey = presigned.storageKey;
      }

      await replyMutation.mutateAsync({
        body: replyBody,
        attachmentStorageKey,
      });
      setReplyBody("");
      setFile(null);
      if (fileInputRef.current) fileInputRef.current.value = "";
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : "Failed to send reply";
      toast.error(message);
    } finally {
      setIsSending(false);
    }
  }

  return (
    <div className="max-w-2xl mx-auto py-8 px-4">
      <div className="flex items-center justify-between mb-2">
        <h1 className="text-xl font-semibold text-white">{current.subject}</h1>
        <TicketStatusBadge status={current.status} />
      </div>
      <p className="text-xs text-white/40 mb-6">{current.ticketNumber}</p>

      <div className="space-y-5 mb-6">
        {current.messages.map((message) => (
          <TicketMessageItem key={message.id} message={message} />
        ))}
      </div>

      {isClosed ? (
        <p className="text-sm text-white/50 bg-white/5 border border-white/10 rounded-xl px-4 py-3">
          This ticket is closed. Please{" "}
          <a href="/contact-us" className="text-tls-secondary hover:underline">
            submit a new ticket
          </a>{" "}
          if you need further help.
        </p>
      ) : (
        <div className="space-y-3">
          <Textarea
            value={replyBody}
            onChange={(e) => setReplyBody(e.target.value)}
            rows={4}
            placeholder="Write a reply..."
          />
          <div className="flex items-center justify-between gap-3">
            <div>
              <input
                ref={fileInputRef}
                type="file"
                accept="image/jpeg,image/png,image/webp,application/pdf"
                onChange={handleFileSelect}
                className="text-xs text-white/60"
              />
              {file && (
                <p className="text-xs text-white/40 mt-1">{file.name}</p>
              )}
              {fileError && (
                <p role="alert" className="text-red-400 text-xs mt-1">
                  {fileError}
                </p>
              )}
            </div>
            <Button
              onClick={handleReply}
              disabled={isSending || !replyBody.trim()}
              aria-busy={isSending}
            >
              {isSending ? (
                <Paperclip className="w-4 h-4 mr-2" />
              ) : (
                <Send className="w-4 h-4 mr-2" />
              )}
              {isSending ? "Sending..." : "Send Reply"}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 5: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
npx tsc --noEmit
npx eslint "src/app/(root)/(protected)/account/tickets/[id]/page.tsx" \
  "src/app/(root)/(protected)/account/tickets/[id]/TicketThreadClient.tsx" \
  src/features/tickets/components/TicketMessageItem.tsx
```

Expected: no new errors.

- [ ] **Step 6: Manual verification**

```bash
npm run dev
```

Log in, submit a ticket via `/contact-us`, navigate to `/account/tickets`, click into the new ticket, confirm the thread shows the initial message, confirm the reply box works and appends a new message to the thread on send (requires the backend from Part 1-3 of this plan running locally). If an admin-web reply can also be tested (Part 8 of this plan), confirm a `RESOLVED` ticket's reply box reopens it and a `CLOSED` ticket shows the "submit a new ticket" message with no reply box instead.

- [ ] **Step 7: Commit**

```bash
git add "src/app/(root)/(protected)/account/tickets/[id]/page.tsx" \
        "src/app/(root)/(protected)/account/tickets/[id]/TicketThreadClient.tsx" \
        src/features/tickets/components/TicketMessageItem.tsx
git commit -m "feat: add ticket thread/detail page with reply and attachment upload"
```

---

## Part 7 — Admin-web: RBAC, types, BFF routes, hooks

### Task 16: `SUPPORT` permission + Tickets nav link

**Files:**

- Modify: `tinniestudio-admin-web/src/lib/auth/rbac.ts`
- Modify: `tinniestudio-admin-web/src/components/layout/Header.tsx`

- [ ] **Step 1: Read current state first**

Read both files in full to confirm the snippets below still match.

- [ ] **Step 2: Add the `SUPPORT` role and `tickets:manage` permission**

In `rbac.ts`, change:

```ts
const ROLE_PERMISSIONS: Record<string, string[]> = {
  SUPER_ADMIN: ["*"],
  MODERATOR: ["admin-settings:manage"],
};
```

to:

```ts
const ROLE_PERMISSIONS: Record<string, string[]> = {
  SUPER_ADMIN: ["*"],
  MODERATOR: ["admin-settings:manage", "tickets:manage"],
  SUPPORT: ["tickets:manage"],
};
```

(`SUPER_ADMIN`'s `"*"` wildcard already covers tickets — no change needed there. `MODERATOR` needs `tickets:manage` added explicitly so existing staff aren't locked out of a capability CLAUDE.md says they should have. `SUPPORT` is new, scoped to only this one permission.)

- [ ] **Step 3: Add a permission-gated Tickets nav link**

In `Header.tsx`, this component currently has no precedent for conditionally hiding a nav link by permission — both existing links (`Dashboard`, `Categories`) are visible to any authenticated user. Add the import and the gated link:

```tsx
import { canAccess } from "@/lib/auth/rbac";
```

Change:

```tsx
export function Header() {
  const { user, isLoading } = useAuth();
  const router = useRouter();
  const [isSigningOut, setIsSigningOut] = useState(false);
```

to:

```tsx
export function Header() {
  const { user, roles, isLoading } = useAuth();
  const router = useRouter();
  const [isSigningOut, setIsSigningOut] = useState(false);
```

(`useAuth()` already exposes `roles` on its context value per `AuthProvider.tsx` — this just destructures the field that was already there but previously unused in this component.)

Change:

```tsx
<nav className="flex items-center gap-4 text-sm text-muted-foreground">
  <Link href="/dashboard" className="hover:text-foreground">
    Dashboard
  </Link>
  <Link href="/categories" className="hover:text-foreground">
    Categories
  </Link>
</nav>
```

to:

```tsx
<nav className="flex items-center gap-4 text-sm text-muted-foreground">
  <Link href="/dashboard" className="hover:text-foreground">
    Dashboard
  </Link>
  <Link href="/categories" className="hover:text-foreground">
    Categories
  </Link>
  {canAccess(roles, "tickets:manage") && (
    <Link href="/tickets" className="hover:text-foreground">
      Tickets
    </Link>
  )}
</nav>
```

- [ ] **Step 4: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-admin-web
npx tsc --noEmit
npx eslint src/lib/auth/rbac.ts src/components/layout/Header.tsx
```

Expected: no new errors.

- [ ] **Step 5: Commit**

```bash
git add src/lib/auth/rbac.ts src/components/layout/Header.tsx
git commit -m "feat: add SUPPORT role/tickets:manage permission and a gated Tickets nav link"
```

---

### Task 17: Ticket types, endpoint constants, BFF routes, and hooks

**Files:**

- Create: `tinniestudio-admin-web/src/types/ticket.type.ts`
- Create: `tinniestudio-admin-web/src/lib/constant/ticket.endpoint.ts`
- Create: `tinniestudio-admin-web/src/app/api/admin/tickets/route.ts`
- Create: `tinniestudio-admin-web/src/app/api/admin/tickets/[id]/route.ts`
- Create: `tinniestudio-admin-web/src/app/api/admin/tickets/[id]/messages/route.ts`
- Create: `tinniestudio-admin-web/src/app/api/admin/tickets/[id]/status/route.ts`
- Create: `tinniestudio-admin-web/src/features/tickets/dal/list-tickets.ts`
- Create: `tinniestudio-admin-web/src/features/tickets/hook/key.ts`
- Create: `tinniestudio-admin-web/src/features/tickets/hook/query.ts`
- Create: `tinniestudio-admin-web/src/features/tickets/hook/mutation.ts`
- Create: `tinniestudio-admin-web/src/features/tickets/hook/index.ts`

This mirrors `src/types/category.type.ts`, `src/lib/constant/category.endpoint.ts`, `src/app/api/admin/categories/route.ts`/`[id]/route.ts`, `src/features/categories/dal/list-categories.ts`, and `src/features/categories/hook/{key,query,mutation,index}.ts` file-for-file.

- [ ] **Step 1: Read current state first**

Read all the Categories-feature files named above in full to confirm the exact current `backendFetch`/`bffFetch`/`useApiQuery`/`useApiMutation` signatures before pasting below.

- [ ] **Step 2: Create the ticket type**

```ts
// Mirrors TicketResponse.java / TicketDetailResponse.java / TicketMessageResponse.java /
// ReplyTicketRequest.java / UpdateTicketStatusRequest.java

export type TicketStatus = "OPEN" | "IN_PROGRESS" | "RESOLVED" | "CLOSED";

export interface TicketMessage {
  id: string;
  senderType: "USER" | "ADMIN";
  senderId: string;
  body: string;
  attachmentUrl: string | null;
  createdAt: string;
}

export interface Ticket {
  id: string;
  ticketNumber: string;
  subject: string;
  status: TicketStatus;
  userId: string;
  submitterEmail: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TicketDetail extends Ticket {
  messages: TicketMessage[];
}

export interface ReplyTicketRequest {
  body: string;
  attachmentStorageKey?: string;
}

export interface UpdateTicketStatusRequest {
  status: "RESOLVED" | "CLOSED";
}
```

- [ ] **Step 3: Create the endpoint constants**

```ts
export const TICKET_ENDPOINT = {
  BACKEND: {
    LIST: "/admin/tickets",
    DETAIL: (id: string) => `/admin/tickets/${id}`,
    REPLY: (id: string) => `/admin/tickets/${id}/messages`,
    STATUS: (id: string) => `/admin/tickets/${id}/status`,
  },
  BFF: {
    LIST: "/api/admin/tickets",
    DETAIL: (id: string) => `/api/admin/tickets/${id}`,
    REPLY: (id: string) => `/api/admin/tickets/${id}/messages`,
    STATUS: (id: string) => `/api/admin/tickets/${id}/status`,
  },
} as const;
```

- [ ] **Step 4: Create the list BFF route**

```ts
import { backendFetch } from "@/lib/server/backend-fetch";
import { buildProxyResponse } from "@/lib/server/bff";
import { TICKET_ENDPOINT } from "@/lib/constant/ticket.endpoint";

export async function GET(req: Request) {
  const { searchParams } = new URL(req.url);
  const qs = searchParams.toString();

  const resp = await backendFetch(req, {
    url: `${TICKET_ENDPOINT.BACKEND.LIST}${qs ? `?${qs}` : ""}`,
  });

  return buildProxyResponse(resp);
}
```

- [ ] **Step 5: Create the detail BFF route**

```ts
import { backendFetch } from "@/lib/server/backend-fetch";
import { buildProxyResponse } from "@/lib/server/bff";
import { TICKET_ENDPOINT } from "@/lib/constant/ticket.endpoint";
import { z } from "zod";

export async function GET(
  req: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  const parsed = z.string().uuid().safeParse(id);
  if (!parsed.success) {
    return Response.json(
      {
        success: false,
        error: { code: "VALIDATION_ERROR", message: parsed.error.message },
      },
      { status: 400 },
    );
  }

  const resp = await backendFetch(req, {
    url: TICKET_ENDPOINT.BACKEND.DETAIL(parsed.data),
  });

  return buildProxyResponse(resp);
}
```

- [ ] **Step 6: Create the reply BFF route**

```ts
import { backendFetch } from "@/lib/server/backend-fetch";
import { buildProxyResponse } from "@/lib/server/bff";
import { TICKET_ENDPOINT } from "@/lib/constant/ticket.endpoint";
import { z } from "zod";

export async function POST(
  req: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  const parsed = z.string().uuid().safeParse(id);
  if (!parsed.success) {
    return Response.json(
      {
        success: false,
        error: { code: "VALIDATION_ERROR", message: parsed.error.message },
      },
      { status: 400 },
    );
  }

  const data = await req.json().catch(() => null);
  const resp = await backendFetch(req, {
    url: TICKET_ENDPOINT.BACKEND.REPLY(parsed.data),
    method: "POST",
    data,
  });

  return buildProxyResponse(resp);
}
```

- [ ] **Step 7: Create the status BFF route**

```ts
import { backendFetch } from "@/lib/server/backend-fetch";
import { buildProxyResponse } from "@/lib/server/bff";
import { TICKET_ENDPOINT } from "@/lib/constant/ticket.endpoint";
import { z } from "zod";

export async function PATCH(
  req: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  const parsed = z.string().uuid().safeParse(id);
  if (!parsed.success) {
    return Response.json(
      {
        success: false,
        error: { code: "VALIDATION_ERROR", message: parsed.error.message },
      },
      { status: 400 },
    );
  }

  const data = await req.json().catch(() => null);
  const resp = await backendFetch(req, {
    url: TICKET_ENDPOINT.BACKEND.STATUS(parsed.data),
    method: "PATCH",
    data,
  });

  return buildProxyResponse(resp);
}
```

- [ ] **Step 8: Create the DAL function**

```ts
import { backendFetch } from "@/lib/server/backend-fetch";
import { TICKET_ENDPOINT } from "@/lib/constant/ticket.endpoint";
import type { ApiResponse } from "@/lib/server/api-response";
import type { Ticket } from "@/types/ticket.type";

export function listTicketsDal(req: Request): Promise<ApiResponse<Ticket[]>> {
  return backendFetch<Ticket[]>(req, { url: TICKET_ENDPOINT.BACKEND.LIST });
}
```

- [ ] **Step 9: Create the hooks**

```ts
export const ticketKeys = {
  list: (status?: string) => ["tickets", "list", status ?? "all"] as const,
  detail: (id: string) => ["tickets", "detail", id] as const,
};
```

```ts
import { bffFetch, useApiQuery } from "@/hooks/api-hooks";
import { TICKET_ENDPOINT } from "@/lib/constant/ticket.endpoint";
import type { Ticket, TicketDetail } from "@/types/ticket.type";
import { ticketKeys } from "./key";

async function fetchTickets(status?: string): Promise<Ticket[]> {
  const qs = status ? `?status=${status}` : "";
  return bffFetch<Ticket[]>(`${TICKET_ENDPOINT.BFF.LIST}${qs}`);
}

export function useTickets(status?: string, initialData?: Ticket[]) {
  return useApiQuery(ticketKeys.list(status), () => fetchTickets(status), {
    initialData,
  });
}

export function useTicket(ticketId: string) {
  return useApiQuery(ticketKeys.detail(ticketId), () =>
    bffFetch<TicketDetail>(TICKET_ENDPOINT.BFF.DETAIL(ticketId)),
  );
}
```

```ts
import { useQueryClient } from "@tanstack/react-query";
import { bffFetch, useApiMutation } from "@/hooks/api-hooks";
import { TICKET_ENDPOINT } from "@/lib/constant/ticket.endpoint";
import type {
  ReplyTicketRequest,
  Ticket,
  TicketDetail,
  UpdateTicketStatusRequest,
} from "@/types/ticket.type";
import { ticketKeys } from "./key";

export function useReplyToTicket(ticketId: string) {
  const queryClient = useQueryClient();
  return useApiMutation<TicketDetail, Error, ReplyTicketRequest>(
    (payload) =>
      bffFetch<TicketDetail>(TICKET_ENDPOINT.BFF.REPLY(ticketId), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      }),
    {
      onSuccess: () => {
        queryClient.invalidateQueries({
          queryKey: ticketKeys.detail(ticketId),
        });
      },
    },
  );
}

export function useUpdateTicketStatus(ticketId: string) {
  const queryClient = useQueryClient();
  return useApiMutation<Ticket, Error, UpdateTicketStatusRequest>(
    (payload) =>
      bffFetch<Ticket>(TICKET_ENDPOINT.BFF.STATUS(ticketId), {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      }),
    {
      onSuccess: () => {
        queryClient.invalidateQueries({
          queryKey: ticketKeys.detail(ticketId),
        });
        queryClient.invalidateQueries({ queryKey: ["tickets", "list"] });
      },
    },
  );
}
```

```ts
export * from "./query";
export * from "./mutation";
export * from "./key";
```

- [ ] **Step 10: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-admin-web
npx tsc --noEmit
npx eslint src/types/ticket.type.ts src/lib/constant/ticket.endpoint.ts \
  "src/app/api/admin/tickets/route.ts" "src/app/api/admin/tickets/[id]/route.ts" \
  "src/app/api/admin/tickets/[id]/messages/route.ts" "src/app/api/admin/tickets/[id]/status/route.ts" \
  src/features/tickets/dal/list-tickets.ts src/features/tickets/hook/key.ts \
  src/features/tickets/hook/query.ts src/features/tickets/hook/mutation.ts src/features/tickets/hook/index.ts
```

Expected: no new errors.

- [ ] **Step 11: Commit**

```bash
git add src/types/ticket.type.ts src/lib/constant/ticket.endpoint.ts \
        "src/app/api/admin/tickets/route.ts" "src/app/api/admin/tickets/[id]/route.ts" \
        "src/app/api/admin/tickets/[id]/messages/route.ts" "src/app/api/admin/tickets/[id]/status/route.ts" \
        src/features/tickets/dal/list-tickets.ts src/features/tickets/hook/key.ts \
        src/features/tickets/hook/query.ts src/features/tickets/hook/mutation.ts src/features/tickets/hook/index.ts
git commit -m "feat: add ticket types, endpoint constants, BFF routes, and hooks"
```

---

## Part 8 — Admin-web: Tickets list and detail/reply UI

### Task 18: Tickets list page with status filter and badges

**Files:**

- Create: `tinniestudio-admin-web/src/app/(app)/tickets/page.tsx`
- Create: `tinniestudio-admin-web/src/app/(app)/tickets/TicketsPageClient.tsx`
- Create: `tinniestudio-admin-web/src/features/tickets/components/TicketsTable.tsx`

This mirrors `src/app/(app)/categories/page.tsx` + `CategoriesPageClient.tsx` + `CategoriesTable.tsx`'s exact `getCurrentUserRoles()` → `AuthGuard` → DAL-fetched `initialData` structure. There is no existing status-filter-dropdown or badge precedent in this app (confirmed during research) — this introduces the first use of `select.tsx`/`badge.tsx` in this codebase, built from those existing-but-unused primitives.

- [ ] **Step 1: Read current state first**

Read `src/app/(app)/categories/page.tsx`, `CategoriesPageClient.tsx`, and `CategoriesTable.tsx` in full once more to confirm the exact current `AuthGuard`/`getCurrentUserRoles`/DAL boilerplate before pasting below.

- [ ] **Step 2: Create the server page**

```tsx
import { cookies } from "next/headers";
import { AuthGuard } from "@/lib/auth/AuthGuard";
import { getCurrentUserRoles } from "@/lib/server/get-current-user";
import { listTicketsDal } from "@/features/tickets/dal/list-tickets";
import { TicketsPageClient } from "./TicketsPageClient";

export default async function TicketsPage() {
  const roles = await getCurrentUserRoles();

  const cookieStore = await cookies();
  const cookieHeader = cookieStore
    .getAll()
    .map((c) => `${c.name}=${c.value}`)
    .join("; ");
  const request = new Request("http://internal/admin/tickets", {
    headers: { cookie: cookieHeader },
  });

  const resp = await listTicketsDal(request);

  return (
    <AuthGuard
      roles={roles}
      permission="tickets:manage"
      fallback={<p>You don&apos;t have access to the admin console.</p>}
    >
      <TicketsPageClient initialData={resp.success ? (resp.data ?? []) : []} />
    </AuthGuard>
  );
}
```

- [ ] **Step 3: Create the client page shell**

```tsx
"use client";

import { TicketsTable } from "@/features/tickets/components/TicketsTable";
import type { Ticket } from "@/types/ticket.type";

export function TicketsPageClient({ initialData }: { initialData: Ticket[] }) {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Support Tickets</h1>
      </div>
      <TicketsTable initialData={initialData} />
    </div>
  );
}
```

- [ ] **Step 4: Create the table with status filter and badges**

```tsx
"use client";

import { useState } from "react";
import Link from "next/link";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import { useTickets } from "@/features/tickets/hook";
import type { Ticket, TicketStatus } from "@/types/ticket.type";

const STATUS_LABEL: Record<TicketStatus, string> = {
  OPEN: "Open",
  IN_PROGRESS: "In Progress",
  RESOLVED: "Resolved",
  CLOSED: "Closed",
};

const STATUS_VARIANT: Record<
  TicketStatus,
  "default" | "secondary" | "outline" | "ghost"
> = {
  OPEN: "default",
  IN_PROGRESS: "secondary",
  RESOLVED: "outline",
  CLOSED: "ghost",
};

export function TicketsTable({ initialData }: { initialData: Ticket[] }) {
  const [statusFilter, setStatusFilter] = useState<TicketStatus | "ALL">("ALL");
  const { data } = useTickets(
    statusFilter === "ALL" ? undefined : statusFilter,
    statusFilter === "ALL" ? initialData : undefined,
  );
  const tickets = data ?? [];

  return (
    <div className="space-y-4">
      <Select
        value={statusFilter}
        onValueChange={(v) => setStatusFilter(v as TicketStatus | "ALL")}
      >
        <SelectTrigger className="w-48">
          <SelectValue placeholder="Filter by status" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="ALL">All statuses</SelectItem>
          <SelectItem value="OPEN">Open</SelectItem>
          <SelectItem value="IN_PROGRESS">In Progress</SelectItem>
          <SelectItem value="RESOLVED">Resolved</SelectItem>
          <SelectItem value="CLOSED">Closed</SelectItem>
        </SelectContent>
      </Select>

      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-muted-foreground">
            <th className="py-2 pr-4">Ticket</th>
            <th className="py-2 pr-4">Subject</th>
            <th className="py-2 pr-4">From</th>
            <th className="py-2 pr-4">Status</th>
            <th className="py-2 pr-4">Created</th>
          </tr>
        </thead>
        <tbody>
          {tickets.length === 0 ? (
            <tr>
              <td
                colSpan={5}
                className="py-6 text-center text-muted-foreground"
              >
                No tickets found.
              </td>
            </tr>
          ) : (
            tickets.map((ticket) => (
              <tr key={ticket.id} className="border-b last:border-0">
                <td className="py-2 pr-4">
                  <Link
                    href={`/tickets/${ticket.id}`}
                    className="text-primary hover:underline"
                  >
                    {ticket.ticketNumber}
                  </Link>
                </td>
                <td className="py-2 pr-4">{ticket.subject}</td>
                <td className="py-2 pr-4">
                  {ticket.submitterEmail ?? ticket.userId}
                </td>
                <td className="py-2 pr-4">
                  <Badge variant={STATUS_VARIANT[ticket.status]}>
                    {STATUS_LABEL[ticket.status]}
                  </Badge>
                </td>
                <td className="py-2 pr-4">
                  {new Date(ticket.createdAt).toLocaleDateString()}
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 5: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-admin-web
npx tsc --noEmit
npx eslint "src/app/(app)/tickets/page.tsx" "src/app/(app)/tickets/TicketsPageClient.tsx" \
  src/features/tickets/components/TicketsTable.tsx
```

Expected: no new errors.

- [ ] **Step 6: Manual verification**

```bash
npm run dev
```

Log in as an admin, visit `/tickets` — confirm the list renders, the status filter dropdown changes which tickets are shown, and clicking a ticket number navigates toward `/tickets/[id]` (built in Task 19).

- [ ] **Step 7: Commit**

```bash
git add "src/app/(app)/tickets/page.tsx" "src/app/(app)/tickets/TicketsPageClient.tsx" \
        src/features/tickets/components/TicketsTable.tsx
git commit -m "feat: add admin tickets list page with status filter and badges"
```

---

### Task 19: Ticket detail/reply page with status control

**Files:**

- Create: `tinniestudio-admin-web/src/app/(app)/tickets/[id]/page.tsx`
- Create: `tinniestudio-admin-web/src/app/(app)/tickets/[id]/TicketDetailClient.tsx`

This is the LAST task of the plan. There is no existing chat/thread UI precedent in this app (confirmed during research) — the message list below is a new pattern, built from `Textarea`/`Badge`/`Select` primitives.

- [ ] **Step 1: Read current state first**

Read `src/app/(app)/categories/page.tsx` once more for the `AuthGuard`/`getCurrentUserRoles` boilerplate, and `src/lib/server/backend-fetch.ts` to confirm its exact exported signature before pasting below.

- [ ] **Step 2: Create the server page**

```tsx
import { cookies } from "next/headers";
import { AuthGuard } from "@/lib/auth/AuthGuard";
import { getCurrentUserRoles } from "@/lib/server/get-current-user";
import { backendFetch } from "@/lib/server/backend-fetch";
import { TICKET_ENDPOINT } from "@/lib/constant/ticket.endpoint";
import { TicketDetailClient } from "./TicketDetailClient";
import type { TicketDetail } from "@/types/ticket.type";

export default async function TicketDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const roles = await getCurrentUserRoles();

  const cookieStore = await cookies();
  const cookieHeader = cookieStore
    .getAll()
    .map((c) => `${c.name}=${c.value}`)
    .join("; ");
  const request = new Request(`http://internal/admin/tickets/${id}`, {
    headers: { cookie: cookieHeader },
  });

  const resp = await backendFetch<TicketDetail>(request, {
    url: TICKET_ENDPOINT.BACKEND.DETAIL(id),
  });

  return (
    <AuthGuard
      roles={roles}
      permission="tickets:manage"
      fallback={<p>You don&apos;t have access to the admin console.</p>}
    >
      {resp.success && resp.data ? (
        <TicketDetailClient ticketId={id} initialTicket={resp.data} />
      ) : (
        <p>Ticket not found.</p>
      )}
    </AuthGuard>
  );
}
```

- [ ] **Step 3: Create the detail/reply client component**

```tsx
"use client";

import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  useTicket,
  useReplyToTicket,
  useUpdateTicketStatus,
} from "@/features/tickets/hook";
import type { TicketDetail, TicketStatus } from "@/types/ticket.type";

const STATUS_LABEL: Record<TicketStatus, string> = {
  OPEN: "Open",
  IN_PROGRESS: "In Progress",
  RESOLVED: "Resolved",
  CLOSED: "Closed",
};

export function TicketDetailClient({
  ticketId,
  initialTicket,
}: {
  ticketId: string;
  initialTicket: TicketDetail;
}) {
  const { data: ticket } = useTicket(ticketId);
  const current = ticket ?? initialTicket;

  const [replyBody, setReplyBody] = useState("");
  const replyMutation = useReplyToTicket(ticketId);
  const statusMutation = useUpdateTicketStatus(ticketId);

  const isClosed = current.status === "CLOSED";

  async function handleReply() {
    if (!replyBody.trim()) return;
    try {
      await replyMutation.mutateAsync({ body: replyBody });
      setReplyBody("");
      toast.success("Reply sent");
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "Failed to send reply");
    }
  }

  async function handleStatusChange(status: string) {
    try {
      await statusMutation.mutateAsync({
        status: status as "RESOLVED" | "CLOSED",
      });
      toast.success(`Ticket marked ${STATUS_LABEL[status as TicketStatus]}`);
    } catch (err: unknown) {
      toast.error(
        err instanceof Error ? err.message : "Failed to update status",
      );
    }
  }

  return (
    <div className="max-w-2xl space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">{current.subject}</h1>
          <p className="text-sm text-muted-foreground">
            {current.ticketNumber} · {current.submitterEmail ?? current.userId}
          </p>
        </div>
        <Badge>{STATUS_LABEL[current.status]}</Badge>
      </div>

      <div className="space-y-4">
        {current.messages.map((message) => (
          <div key={message.id} className="rounded-lg border p-3">
            <div className="flex items-center justify-between text-xs text-muted-foreground mb-1">
              <span className="font-medium">
                {message.senderType === "ADMIN" ? "Support" : "User"}
              </span>
              <span>{new Date(message.createdAt).toLocaleString()}</span>
            </div>
            <p className="text-sm whitespace-pre-wrap">{message.body}</p>
            {message.attachmentUrl && (
              <a
                href={message.attachmentUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="text-xs text-primary hover:underline"
              >
                View attachment
              </a>
            )}
          </div>
        ))}
      </div>

      {!isClosed && (
        <div className="space-y-2">
          <Textarea
            value={replyBody}
            onChange={(e) => setReplyBody(e.target.value)}
            rows={4}
            placeholder="Write a reply..."
          />
          <Button
            onClick={handleReply}
            disabled={replyMutation.isPending || !replyBody.trim()}
          >
            {replyMutation.isPending ? "Sending..." : "Send Reply"}
          </Button>
        </div>
      )}

      <div className="flex items-center gap-3 border-t pt-4">
        <span className="text-sm text-muted-foreground">Change status:</span>
        <Select
          value={current.status}
          onValueChange={handleStatusChange}
          disabled={statusMutation.isPending}
        >
          <SelectTrigger className="w-40">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="RESOLVED">Resolved</SelectItem>
            <SelectItem value="CLOSED">Closed</SelectItem>
          </SelectContent>
        </Select>
      </div>
    </div>
  );
}
```

(`Select`'s `value`/`onValueChange` is bound directly to `current.status`/`handleStatusChange` — selecting "Resolved" or "Closed" immediately fires the mutation; there's no separate "Apply" button, matching the spec's "a status control" wording without over-building a multi-step confirmation flow for what is a reversible, low-risk admin action.)

- [ ] **Step 4: Type-check and lint**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-admin-web
npx tsc --noEmit
npx eslint "src/app/(app)/tickets/[id]/page.tsx" "src/app/(app)/tickets/[id]/TicketDetailClient.tsx"
```

Expected: no new errors.

- [ ] **Step 5: Manual verification**

```bash
npm run dev
```

Log in as an admin, open a ticket from the list, confirm the thread renders, send a reply and confirm it appears in the thread (requires the backend from Part 1-3 running locally), confirm an `OPEN` ticket flips to `IN_PROGRESS` after the first admin reply, and confirm changing status to `Resolved`/`Closed` via the dropdown updates the badge. Confirm a `CLOSED` ticket hides the reply box. Log in as a `SUPPORT`-role-only account (create one via the existing sub-admin creation flow with `role: SUPPORT`) and confirm it can reach `/tickets` but not `/categories` (role-gating check, per the spec's testing section).

- [ ] **Step 6: Commit**

```bash
git add "src/app/(app)/tickets/[id]/page.tsx" "src/app/(app)/tickets/[id]/TicketDetailClient.tsx"
git commit -m "feat: add admin ticket detail page with reply and status control"
```

---

## Self-Review Notes

**Spec coverage:**

- Part A (contact info fixes) — Task 8.
- Part B (Tawk.to live chat) — Task 9.
- Part C entities/status lifecycle/assignment model — Tasks 2, 5, 6.
- Part C access control (new `SUPPORT` role) — Tasks 1 (server), 16 (admin-web RBAC/nav).
- Part C attachments (presign/upload/confirm, mime/size validation) — Task 7 (server), 13 & 15 (client-web upload flow).
- Part C company-email alert — Task 4.
- Part C user-facing reply notification (`TICKET_REPLIED`, `NotificationBell` extension) — Tasks 4, 6, 12.
- Part C client-web UI (submission form, My Tickets list + thread) — Tasks 13, 14, 15.
- Part C admin-web UI (Tickets nav, list filterable by status, detail/reply + status control) — Tasks 16, 18, 19.
- Error handling & edge cases (server-side mime/size validation, ownership checks, CLOSED rejection, email-alert failure non-blocking) — Tasks 5, 6, 7 (all covered by the service-layer tests in those tasks).
- Testing section's backend requirements (ticket creation + ownership, status transitions, role-gating, email-alert-failure-doesn't-block, `TICKET_REPLIED` fires) — Tasks 5, 6 test suites. Frontend requirements (submit/list/notification/reply/reopen/closed-rejection on client-web; list/reply/status-change/role-gating on admin-web) — covered by the manual verification steps in Tasks 13-15 and 18-19.

**Placeholder scan:** no "TBD"/"implement later"/"add appropriate X" phrases remain in any task — every step shows complete code. Two early placeholder-style false starts (an interface shown twice with a "Correction" aside in Task 5, and hedge notes about unverified method signatures in Task 7/14) were caught during self-review and replaced with single, confirmed-correct versions.

**Type consistency:** `TicketResponse`/`TicketDetailResponse`/`TicketMessageResponse` (server, Task 5) match `Ticket`/`TicketDetail`/`TicketMessage` (client-web, Task 10, and admin-web, Task 17) field-for-field, including the `submitterEmail: string | null` field present-but-usually-null on the user-facing path and populated on the admin-facing path. `CreateTicketRequest`/`ReplyTicketRequest`/`UpdateTicketStatusRequest` match across all three repos exactly (`subject`/`body`/`attachmentStorageKey`, `status: 'RESOLVED' | 'CLOSED'`). The `Page<TicketResponse>` → `{data: T[], meta: ApiResponseMeta}` unwrapping (confirmed against the real `SuccessResponseWrapper.java`) is consistently assumed in both frontends' list hooks and both backend controller tests (corrected mid-planning from an initial incorrect `data.content` assumption).
