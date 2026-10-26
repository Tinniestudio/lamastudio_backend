# Legacy Card-Setup Grandfathering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop pre-existing active-plan subscribers from being hard-blocked for missing a card, while keeping the hard block for genuinely new signups, and narrow the client's app-wide access block so an expired/past-due user can use the platform except for watching content.

**Architecture:** Add a `cardSetupRequired` boolean to `UserProfile` (default `TRUE` for new rows), backfilled to `FALSE` for every pre-existing user by a one-time Flyway migration. `SubscriptionServiceImpl.getSubscriptionStatus` only returns `NEEDS_SETUP` when that flag is true. On the client, `AuthGuard` narrows its app-wide redirect to `blockReason === 'NEEDS_SETUP'` only, and a new dismissible banner nudges grandfathered users (`hasAccess=true`, `paymentSetup=false`) to add a card without blocking them.

**Tech Stack:** Spring Boot (Java 21, Gradle), PostgreSQL + Flyway, JUnit 5 / Mockito / AssertJ / Testcontainers, Next.js (TypeScript, React Query), Tailwind.

**Design doc:** `docs/superpowers/specs/2026-10-26-legacy-card-setup-grandfathering-design.md`

---

## File Structure

- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/UserProfile.java` — new field.
- Create: `api-service/src/main/resources/db/migration/V60__add_card_setup_required.sql` — backfill migration.
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/billing/service/SubscriptionServiceImpl.java:261-272` — gate the `NEEDS_SETUP` branch on the new flag.
- Modify: `api-service/src/test/java/com/tinniestudio/api/billing/service/SubscriptionServiceTest.java` — two new cases in the `GetSubscriptionStatus` nested class.
- Create: `api-service/src/test/java/com/tinniestudio/api/modules/billing/migration/CardSetupRequiredMigrationTest.java` — verifies the backfill against a real Postgres.
- Modify: `tinniestudio-client-web/src/features/auth/components/AuthGuard.tsx` — narrow the redirect condition.
- Create: `tinniestudio-client-web/src/features/account/components/CardSetupNudge.tsx` — the non-blocking banner.
- Modify: `tinniestudio-client-web/src/app/(root)/(protected)/layout.tsx` — render the banner above `children`.

---

### Task 1: Add `cardSetupRequired` to `UserProfile`

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/UserProfile.java`

- [ ] **Step 1: Add the field**

In `UserProfile.java`, add the new field right after `paymentSetup` (currently lines 43-44):

```java
    @Column(name = "payment_setup", nullable = false)
    private boolean paymentSetup = false;

    @Column(name = "card_setup_required", nullable = false)
    private boolean cardSetupRequired = true;
```

- [ ] **Step 2: Compile to confirm Lombok generates `isCardSetupRequired()`/`setCardSetupRequired(boolean)`**

Run: `cd api-service && ../gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/UserProfile.java
git commit -m "feat: add UserProfile.cardSetupRequired field"
```

---

### Task 2: Flyway migration to backfill existing users

**Files:**
- Create: `api-service/src/main/resources/db/migration/V60__add_card_setup_required.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V60__add_card_setup_required.sql
-- Legacy card-setup grandfathering: the card-required-before-access policy only applies to
-- users created after this migration. Every account that already exists is exempt, regardless
-- of its current plan/card state (see docs/superpowers/specs/2026-10-26-legacy-card-setup-grandfathering-design.md).

ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS card_setup_required BOOLEAN NOT NULL DEFAULT TRUE;

-- Backfill: any user who already has a profile row is exempt.
UPDATE user_profiles SET card_setup_required = FALSE;

-- Backfill: profiles are created lazily (see UserProfileServiceImpl.createEmptyProfile), so many
-- pre-existing users have no user_profiles row at all yet. Insert one for them, exempt too.
INSERT INTO user_profiles (user_id, card_setup_required)
SELECT u.id, FALSE
FROM users u
LEFT JOIN user_profiles p ON p.user_id = u.id
WHERE p.user_id IS NULL;
```

Note the ordering: `UPDATE ... SET card_setup_required = FALSE` with no `WHERE` clause runs before the `INSERT`, so it only ever touches rows that existed before this migration — the newly inserted rows are added after, already `FALSE`, and are not re-touched.

- [ ] **Step 2: Verify the migration runs cleanly against a disposable DB**

Run: `cd api-service && ../gradlew flywayMigrate -Dflyway.url=jdbc:postgresql://localhost:5432/tinniestudio_dev -Dflyway.user=<local-dev-user> -Dflyway.password=<local-dev-password>` (use whatever local Postgres the repo's existing dev setup already points at — check `api-service/src/main/resources/application.yml` / `.env` for the actual datasource if these placeholders don't match).
Expected: `Successfully applied 1 migration to schema "public", now at version v60`.

If there's no convenient local Postgres to hand, skip this step — Task 3's Testcontainers test exercises the same SQL and is the real verification.

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/resources/db/migration/V60__add_card_setup_required.sql
git commit -m "feat: add V60 migration backfilling card_setup_required for existing users"
```

---

### Task 3: Migration backfill test (Testcontainers)

**Files:**
- Create: `api-service/src/test/java/com/tinniestudio/api/modules/billing/migration/CardSetupRequiredMigrationTest.java`

This test runs Flyway up to V59 directly (not via Spring Boot autoconfiguration, which always migrates to latest), inserts rows simulating pre-existing legacy data, then migrates to V60 and checks the backfill. There's no existing precedent in this repo for a versioned-migration-step test (existing Testcontainers tests like `EpisodeSlugTriggerTest` always run to latest via `@DataJpaTest`), so this uses the Flyway Java API directly instead.

- [ ] **Step 1: Write the test**

```java
package com.tinniestudio.api.modules.billing.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CardSetupRequiredMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_migration_test")
            .withUsername("test")
            .withPassword("test");

    private Flyway flywayTo(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void existingUsersAreBackfilledExempt_newUsersDefaultRequired() throws Exception {
        // Migrate to the version immediately before the one under test, then seed data that
        // represents accounts which already existed when V60 ships.
        flywayTo("59").migrate();

        UUID legacyWithProfile = UUID.randomUUID();
        UUID legacyWithoutProfile = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, email) VALUES ('" + legacyWithProfile + "', 'legacy-with-profile@example.com')");
            stmt.execute("INSERT INTO user_profiles (user_id) VALUES ('" + legacyWithProfile + "')");
            stmt.execute("INSERT INTO users (id, email) VALUES ('" + legacyWithoutProfile + "', 'legacy-without-profile@example.com')");
            // legacyWithoutProfile intentionally gets no user_profiles row — simulates the lazy-creation gap.
        }

        // Now apply V60.
        flywayTo("60").migrate();

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            ResultSet rs1 = stmt.executeQuery(
                "SELECT card_setup_required FROM user_profiles WHERE user_id = '" + legacyWithProfile + "'");
            assertThat(rs1.next()).isTrue();
            assertThat(rs1.getBoolean("card_setup_required")).isFalse();

            ResultSet rs2 = stmt.executeQuery(
                "SELECT card_setup_required FROM user_profiles WHERE user_id = '" + legacyWithoutProfile + "'");
            assertThat(rs2.next()).isTrue();
            assertThat(rs2.getBoolean("card_setup_required")).isFalse();

            // A brand-new user created after V60 ships gets a profile with no explicit value —
            // the column default (TRUE) must apply, proving new signups still require a card.
            UUID newUser = UUID.randomUUID();
            stmt.execute("INSERT INTO users (id, email) VALUES ('" + newUser + "', 'new-user@example.com')");
            stmt.execute("INSERT INTO user_profiles (user_id) VALUES ('" + newUser + "')");
            ResultSet rs3 = stmt.executeQuery(
                "SELECT card_setup_required FROM user_profiles WHERE user_id = '" + newUser + "'");
            assertThat(rs3.next()).isTrue();
            assertThat(rs3.getBoolean("card_setup_required")).isTrue();
        }
    }
}
```

- [ ] **Step 2: Run it**

Run: `cd api-service && ../gradlew test --tests "com.tinniestudio.api.modules.billing.migration.CardSetupRequiredMigrationTest"`
Expected: `BUILD SUCCESSFUL`, 1 test passed. (Requires Docker running locally for Testcontainers — same requirement as the repo's other `@Testcontainers` tests.)

- [ ] **Step 3: Commit**

```bash
git add api-service/src/test/java/com/tinniestudio/api/modules/billing/migration/CardSetupRequiredMigrationTest.java
git commit -m "test: verify V60 backfills existing users as card-setup-exempt"
```

---

### Task 4: Gate `NEEDS_SETUP` on `cardSetupRequired` — tests first

**Files:**
- Modify: `api-service/src/test/java/com/tinniestudio/api/billing/service/SubscriptionServiceTest.java`

- [ ] **Step 1: Add two failing tests inside the existing `GetSubscriptionStatus` nested class**

Add these two methods right after the existing `getSubscriptionStatus_legacyFreePlanNoCard_stillHasAccess` test (the one ending around current line 393 of that file):

```java
        @Test
        @DisplayName("grandfathered legacy paid subscriber with no card still has access, not NEEDS_SETUP")
        void getSubscriptionStatus_grandfatheredPaidPlanNoCard_stillHasAccess() {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setContentWatchesUsed(0);
            sub.setStripeCustomerId(null);
            sub.setEndDate(null);

            UserProfile profile = new UserProfile();
            profile.setPaymentSetup(false);
            profile.setCardSetupRequired(false);

            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub));
            when(userProfileRepository.findById(userId)).thenReturn(Optional.of(profile));
            when(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

            SubscriptionStatusResponse result = service.getSubscriptionStatus(userId);

            assertThat(result.isHasAccess()).isTrue();
            assertThat(result.getBlockReason()).isNull();
            assertThat(result.isPaymentSetup()).isFalse();
        }

        @Test
        @DisplayName("non-grandfathered paid subscriber with no card is blocked with NEEDS_SETUP")
        void getSubscriptionStatus_newPaidPlanNoCard_blocksWithNeedsSetup() {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setContentWatchesUsed(0);
            sub.setStripeCustomerId(null);
            sub.setEndDate(null);

            UserProfile profile = new UserProfile();
            profile.setPaymentSetup(false);
            profile.setCardSetupRequired(true);

            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub));
            when(userProfileRepository.findById(userId)).thenReturn(Optional.of(profile));
            when(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

            SubscriptionStatusResponse result = service.getSubscriptionStatus(userId);

            assertThat(result.isHasAccess()).isFalse();
            assertThat(result.getBlockReason()).isEqualTo("NEEDS_SETUP");
        }
```

- [ ] **Step 2: Run the tests to confirm the first one currently fails**

Run: `cd api-service && ../gradlew test --tests "com.tinniestudio.api.billing.service.SubscriptionServiceTest"`
Expected: `getSubscriptionStatus_grandfatheredPaidPlanNoCard_stillHasAccess` FAILS (`result.isHasAccess()` is `false`, not `true` — the current code blocks it). `getSubscriptionStatus_newPaidPlanNoCard_blocksWithNeedsSetup` PASSES already (current behavior already blocks this case; the test exists to pin the behavior so Task 5 can't accidentally remove it).

- [ ] **Step 3: Commit**

```bash
git add api-service/src/test/java/com/tinniestudio/api/billing/service/SubscriptionServiceTest.java
git commit -m "test: pin grandfathered-vs-new NEEDS_SETUP behavior for getSubscriptionStatus"
```

---

### Task 5: Implement the gate

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/billing/service/SubscriptionServiceImpl.java:261-272`

- [ ] **Step 1: Replace the block**

Current code (`SubscriptionServiceImpl.java:261-274`):

```java
        boolean paymentSetup = userProfileRepository.findById(userId)
            .map(UserProfile::isPaymentSetup).orElse(false);

        // A subscription still on the FREE plan never needed a card under the pre-recurring-billing
        // model — only SILVER/GOLD (trial or paid) requires one. Without this, every legacy free
        // signup (no stripeCustomerId, payment_setup defaulted false by the V59 migration) gets
        // walled off with NEEDS_SETUP even though they never agreed to add a card.
        boolean isFreePlan = sub.getPlan() == null || "FREE".equalsIgnoreCase(sub.getPlan().getName());

        String blockReason;
        boolean hasAccess;
        if (!isFreePlan && !paymentSetup && sub.getStripeCustomerId() == null) {
            blockReason = "NEEDS_SETUP";
            hasAccess = false;
        } else if (sub.getStatus() == SubscriptionStatus.PAST_DUE) {
```

Replace with:

```java
        Optional<UserProfile> profile = userProfileRepository.findById(userId);
        boolean paymentSetup = profile.map(UserProfile::isPaymentSetup).orElse(false);

        // The card-required-before-access policy (V59, 2026-10-18) only applies to accounts
        // created after it shipped. V60 backfilled every pre-existing user's profile to
        // cardSetupRequired=false, so this flag — not isFreePlan/paymentSetup alone — is what
        // actually distinguishes a genuinely new blocked signup from a grandfathered legacy
        // subscriber who was never asked for a card. See
        // docs/superpowers/specs/2026-10-26-legacy-card-setup-grandfathering-design.md.
        boolean cardSetupRequired = profile.map(UserProfile::isCardSetupRequired).orElse(true);

        // A subscription still on the FREE plan never needed a card under the pre-recurring-billing
        // model — only SILVER/GOLD (trial or paid) requires one. Without this, every legacy free
        // signup (no stripeCustomerId, payment_setup defaulted false by the V59 migration) gets
        // walled off with NEEDS_SETUP even though they never agreed to add a card.
        boolean isFreePlan = sub.getPlan() == null || "FREE".equalsIgnoreCase(sub.getPlan().getName());

        String blockReason;
        boolean hasAccess;
        if (cardSetupRequired && !isFreePlan && !paymentSetup && sub.getStripeCustomerId() == null) {
            blockReason = "NEEDS_SETUP";
            hasAccess = false;
        } else if (sub.getStatus() == SubscriptionStatus.PAST_DUE) {
```

(Everything after this `else if` is unchanged — only the opening `if` condition and the `paymentSetup`/new `cardSetupRequired` lookups above it change.)

- [ ] **Step 2: Run the full subscription test class**

Run: `cd api-service && ../gradlew test --tests "com.tinniestudio.api.billing.service.SubscriptionServiceTest"`
Expected: `BUILD SUCCESSFUL`, all tests pass, including both new ones from Task 4 and the pre-existing `getSubscriptionStatus_legacyFreePlanNoCard_stillHasAccess` / `getSubscriptionStatus_periodExpired_blocksAccessRegardlessOfPaymentSetup` / `disableCard_periodNotYetExpired_stillHasAccess` tests.

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/billing/service/SubscriptionServiceImpl.java
git commit -m "fix: only block NEEDS_SETUP for accounts where cardSetupRequired is true"
```

---

### Task 6: Narrow `AuthGuard`'s app-wide block to `NEEDS_SETUP` only

**Files:**
- Modify: `tinniestudio-client-web/src/features/auth/components/AuthGuard.tsx`

- [ ] **Step 1: Update the effect's redirect condition**

Current (`AuthGuard.tsx:39-48`):

```tsx
    // /account (and its sub-routes, including /account/billing-gate itself) is exempt — it's
    // exactly where a blocked user needs to go to fix their billing, so it must stay reachable
    // even when hasAccess is false. Every other protected route redirects to the gate.
    if (
      subscription &&
      !subscription.hasAccess &&
      !pathname.startsWith('/account')
    ) {
      router.replace('/account/billing-gate');
    }
```

Replace with:

```tsx
    // /account (and its sub-routes, including /account/billing-gate itself) is exempt — it's
    // exactly where a blocked user needs to go to fix their billing, so it must stay reachable
    // even when hasAccess is false. Only NEEDS_SETUP (a genuinely new, un-migrated signup) blocks
    // the whole app — EXPIRED/PAST_DUE users can use the rest of the platform and are gated at
    // the point of watching content instead (see watch/[slug]/page.tsx's own access check).
    if (
      subscription &&
      subscription.blockReason === 'NEEDS_SETUP' &&
      !pathname.startsWith('/account')
    ) {
      router.replace('/account/billing-gate');
    }
```

- [ ] **Step 2: Update the matching render guard**

Current (`AuthGuard.tsx:76-82`):

```tsx
  if (
    subscription &&
    !subscription.hasAccess &&
    !pathname.startsWith('/account')
  ) {
    return null;
  }
```

Replace with:

```tsx
  if (
    subscription &&
    subscription.blockReason === 'NEEDS_SETUP' &&
    !pathname.startsWith('/account')
  ) {
    return null;
  }
```

- [ ] **Step 3: Type-check and lint**

Run: `cd tinniestudio-client-web && npm run type-check && npm run lint`
Expected: both succeed with no new errors.

- [ ] **Step 4: Commit**

```bash
git add src/features/auth/components/AuthGuard.tsx
git commit -m "fix: only hard-block the whole app for NEEDS_SETUP, not EXPIRED/PAST_DUE"
```

---

### Task 7: Add the non-blocking card-setup nudge banner

**Files:**
- Create: `tinniestudio-client-web/src/features/account/components/CardSetupNudge.tsx`
- Modify: `tinniestudio-client-web/src/app/(root)/(protected)/layout.tsx`

- [ ] **Step 1: Write the banner component**

```tsx
'use client';

import { useState } from 'react';
import Link from 'next/link';
import { CreditCard, X } from 'lucide-react';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { useSubscription } from '../service/queries';

/**
 * Non-blocking nudge for grandfathered legacy subscribers (cardSetupRequired=false on the
 * backend) who are on an active, unexpired paid plan with no card on file. hasAccess is true
 * and blockReason is null for this account shape — paymentSetup=false is the only signal that
 * distinguishes it, which is exactly what this banner renders on.
 */
export function CardSetupNudge() {
  const { data: subscription } = useSubscription();
  const [dismissed, setDismissed] = useState(false);

  if (
    dismissed ||
    !subscription ||
    !subscription.hasAccess ||
    subscription.paymentSetup ||
    subscription.blockReason !== null
  ) {
    return null;
  }

  return (
    <Alert className='bg-yellow-500/5 border-yellow-500/20 text-yellow-300 rounded-none border-x-0 border-t-0'>
      <CreditCard className='h-4 w-4' />
      <div className='ml-3 flex flex-1 flex-wrap items-center justify-between gap-3'>
        <AlertDescription className='text-yellow-300/80'>
          Add a card to keep your plan active — your access isn&apos;t affected right now.
        </AlertDescription>
        <div className='flex shrink-0 items-center gap-2'>
          <Button asChild size='sm' variant='outline' className='h-8 rounded-lg'>
            <Link href='/account?action=billing'>Add card</Link>
          </Button>
          <button
            type='button'
            aria-label='Dismiss'
            onClick={() => setDismissed(true)}
            className='text-yellow-300/60 hover:text-yellow-300'
          >
            <X className='h-4 w-4' />
          </button>
        </div>
      </div>
    </Alert>
  );
}
```

- [ ] **Step 2: Render it in the protected layout**

Current (`app/(root)/(protected)/layout.tsx`):

```tsx
import AuthGuard from '@/features/auth/components/AuthGuard';
import React from 'react';

export default async function ProtectedLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return <AuthGuard>{children}</AuthGuard>;
  // return <>{children}</>;
}
```

Replace with:

```tsx
import AuthGuard from '@/features/auth/components/AuthGuard';
import { CardSetupNudge } from '@/features/account/components/CardSetupNudge';
import React from 'react';

export default async function ProtectedLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <AuthGuard>
      <CardSetupNudge />
      {children}
    </AuthGuard>
  );
}
```

(This also drops the dead `// return <>{children}</>;` comment line while touching this file.)

- [ ] **Step 3: Type-check and lint**

Run: `cd tinniestudio-client-web && npm run type-check && npm run lint`
Expected: both succeed with no new errors.

- [ ] **Step 4: Commit**

```bash
git add src/features/account/components/CardSetupNudge.tsx "src/app/(root)/(protected)/layout.tsx"
git commit -m "feat: add non-blocking card-setup nudge banner for grandfathered subscribers"
```

---

### Task 8: Manual verification in the browser

No test framework exists in `tinniestudio-client-web` ([[feedback_no_frontend_test_framework]]) — this task is a manual walkthrough, not automated.

**Files:** none (verification only).

- [ ] **Step 1: Start the backend and frontend dev servers**

Run backend: `cd api-service && ../gradlew bootRun` (confirm it points at a Postgres you can freely write test rows into).
Run frontend: `cd tinniestudio-client-web && npm run dev`

- [ ] **Step 2: Seed three account states directly in the DB and confirm each behaves correctly**

For a grandfathered legacy paid subscriber (`card_setup_required=false`, active non-free plan, no `stripe_customer_id`): log in as that user, confirm you can navigate anywhere in the app, and confirm the yellow "Add a card to keep your plan active" banner is visible and dismissible without blocking navigation.

For an expired subscriber (`sub.end_date` in the past): log in, confirm general navigation works (no redirect to billing-gate on page load), then open any content's watch page and confirm it redirects to `/account/billing-gate` with the "Your plan has expired" copy.

For a genuinely new signup forced into the `NEEDS_SETUP` shape (`card_setup_required=true`, active non-free plan, no card): log in, confirm every route except `/account/*` immediately redirects to `/account/billing-gate`.

- [ ] **Step 3: Report results**

Note any deviation from the expected behavior above before considering this plan done.

---

## Self-Review Notes

- **Spec coverage:** Problem/Goals → Tasks 1-7. Migration semantics (unconditional exemption, lazy-profile backfill) → Task 2 + verified by Task 3. Nudge dismissibility ("can close the page") → Task 7. `EXPIRED`/`PAST_DUE` platform-access-but-no-content → Task 6 (removes app-wide block) relies on the pre-existing, unchanged watch-page gate (`watch/[slug]/page.tsx:67-78`) — no code change needed there, confirmed already correct in the design doc's research. Testing section → Tasks 3, 4, 8.
- **Type consistency:** `cardSetupRequired` / `isCardSetupRequired()` / `setCardSetupRequired()` used identically in Task 1 (entity), Task 4 (tests), Task 5 (service). `card_setup_required` column name consistent across Task 2 SQL and Task 3 test SQL.
- Pre-existing, unrelated stray token at `AuthGuard.tsx:16` (`const router = useRouter();AuthGuard`) is out of scope for this plan — flagged separately, not touched here.
