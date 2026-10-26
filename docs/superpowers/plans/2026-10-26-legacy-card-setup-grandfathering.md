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
- Modify: `tinniestudio-client-web/src/features/account/components/ProfilePanelSubscription.tsx` — add the "Add a Card" entry point the banner links to (Task 9, added after code review surfaced the gap).

---

### Task 1: Add `cardSetupRequired` to `UserProfile`

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/entity/UserProfile.java`

- [x] **Step 1: Add the field**

In `UserProfile.java`, add the new field right after `paymentSetup` (currently lines 43-44):

```java
    @Column(name = "payment_setup", nullable = false)
    private boolean paymentSetup = false;

    @Column(name = "card_setup_required", nullable = false)
    private boolean cardSetupRequired = true;
```

- [x] **Step 2: Compile to confirm Lombok generates `isCardSetupRequired()`/`setCardSetupRequired(boolean)`**

Run: `cd api-service && ../gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/entity/UserProfile.java
git commit -m "feat: add UserProfile.cardSetupRequired field"
```

---

### Task 2: Flyway migration to backfill existing users

**Files:**
- Create: `api-service/src/main/resources/db/migration/V60__add_card_setup_required.sql`

- [x] **Step 1: Write the migration**

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

- [x] **Step 2: Verify the migration runs cleanly against a disposable DB**

Run: `cd api-service && ../gradlew flywayMigrate -Dflyway.url=jdbc:postgresql://localhost:5432/tinniestudio_dev -Dflyway.user=<local-dev-user> -Dflyway.password=<local-dev-password>` (use whatever local Postgres the repo's existing dev setup already points at — check `api-service/src/main/resources/application.yml` / `.env` for the actual datasource if these placeholders don't match).
Expected: `Successfully applied 1 migration to schema "public", now at version v60`.

If there's no convenient local Postgres to hand, skip this step — Task 3's Testcontainers test exercises the same SQL and is the real verification.

- [x] **Step 3: Commit**

```bash
git add api-service/src/main/resources/db/migration/V60__add_card_setup_required.sql
git commit -m "feat: add V60 migration backfilling card_setup_required for existing users"
```

---

### Task 3: Migration backfill test (Testcontainers)

**Files:**
- Create: `api-service/src/test/java/com/tinniestudio/api/modules/billing/migration/CardSetupRequiredMigrationTest.java`

This test runs Flyway up to V59 directly (not via Spring Boot autoconfiguration, which always migrates to latest), inserts rows simulating pre-existing legacy data, then migrates to V60 and checks the backfill. There's no existing precedent in this repo for a versioned-migration-step test (existing Testcontainers tests like `EpisodeSlugTriggerTest` always run to latest via `@DataJpaTest`), so this uses the Flyway Java API directly instead.

- [x] **Step 1: Write the test**

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

- [x] **Step 2: Run it**

Run: `cd api-service && ../gradlew test --tests "com.tinniestudio.api.modules.billing.migration.CardSetupRequiredMigrationTest"`
Expected: `BUILD SUCCESSFUL`, 1 test passed. (Requires Docker running locally for Testcontainers — same requirement as the repo's other `@Testcontainers` tests.)

- [x] **Step 3: Commit**

```bash
git add api-service/src/test/java/com/tinniestudio/api/modules/billing/migration/CardSetupRequiredMigrationTest.java
git commit -m "test: verify V60 backfills existing users as card-setup-exempt"
```

---

### Task 4: Gate `NEEDS_SETUP` on `cardSetupRequired` — tests first

**Files:**
- Modify: `api-service/src/test/java/com/tinniestudio/api/billing/service/SubscriptionServiceTest.java`

- [x] **Step 1: Add two failing tests inside the existing `GetSubscriptionStatus` nested class**

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

- [x] **Step 2: Run the tests to confirm the first one currently fails**

Run: `cd api-service && ../gradlew test --tests "com.tinniestudio.api.billing.service.SubscriptionServiceTest"`
Expected: `getSubscriptionStatus_grandfatheredPaidPlanNoCard_stillHasAccess` FAILS (`result.isHasAccess()` is `false`, not `true` — the current code blocks it). `getSubscriptionStatus_newPaidPlanNoCard_blocksWithNeedsSetup` PASSES already (current behavior already blocks this case; the test exists to pin the behavior so Task 5 can't accidentally remove it).

- [x] **Step 3: Commit**

```bash
git add api-service/src/test/java/com/tinniestudio/api/billing/service/SubscriptionServiceTest.java
git commit -m "test: pin grandfathered-vs-new NEEDS_SETUP behavior for getSubscriptionStatus"
```

---

### Task 5: Implement the gate

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/billing/service/SubscriptionServiceImpl.java:261-272`

- [x] **Step 1: Replace the block**

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

- [x] **Step 2: Run the full subscription test class**

Run: `cd api-service && ../gradlew test --tests "com.tinniestudio.api.billing.service.SubscriptionServiceTest"`
Expected: `BUILD SUCCESSFUL`, all tests pass, including both new ones from Task 4 and the pre-existing `getSubscriptionStatus_legacyFreePlanNoCard_stillHasAccess` / `getSubscriptionStatus_periodExpired_blocksAccessRegardlessOfPaymentSetup` / `disableCard_periodNotYetExpired_stillHasAccess` tests.

- [x] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/billing/service/SubscriptionServiceImpl.java
git commit -m "fix: only block NEEDS_SETUP for accounts where cardSetupRequired is true"
```

---

### Task 6: Narrow `AuthGuard`'s app-wide block to `NEEDS_SETUP` only

**Files:**
- Modify: `tinniestudio-client-web/src/features/auth/components/AuthGuard.tsx`

- [x] **Step 1: Update the effect's redirect condition**

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

- [x] **Step 2: Update the matching render guard**

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

- [x] **Step 3: Type-check and lint**

Run: `cd tinniestudio-client-web && npm run type-check && npm run lint`
Expected: both succeed with no new errors.

- [x] **Step 4: Commit**

```bash
git add src/features/auth/components/AuthGuard.tsx
git commit -m "fix: only hard-block the whole app for NEEDS_SETUP, not EXPIRED/PAST_DUE"
```

---

### Task 7: Add the non-blocking card-setup nudge banner

**Files:**
- Create: `tinniestudio-client-web/src/features/account/components/CardSetupNudge.tsx`
- Modify: `tinniestudio-client-web/src/app/(root)/(protected)/layout.tsx`

- [x] **Step 1: Write the banner component**

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

- [x] **Step 2: Render it in the protected layout**

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

- [x] **Step 3: Type-check and lint**

Run: `cd tinniestudio-client-web && npm run type-check && npm run lint`
Expected: both succeed with no new errors.

- [x] **Step 4: Commit**

```bash
git add src/features/account/components/CardSetupNudge.tsx "src/app/(root)/(protected)/layout.tsx"
git commit -m "feat: add non-blocking card-setup nudge banner for grandfathered subscribers"
```

---

### Task 9: Add a working "add a card" flow for grandfathered users

**Why this task exists:** code review of Task 7 found that the nudge banner's "Add card" button led to a dead end. It links to `/account?action=billing`, which opens `SubscriptionPlansPanel` in "switch plan" mode for any user whose `blockReason` isn't `NEEDS_SETUP` (`SubscriptionPlansPanel.tsx:85`, `isFirstEverSetup = subscription?.blockReason === 'NEEDS_SETUP'`) — a grandfathered user (`blockReason=null`) has no way to actually submit a card anywhere in the app. The backend already fully supports this case with no changes needed: `SubscriptionServiceImpl.confirmPaymentSetup` (`api-service/.../SubscriptionServiceImpl.java:164-169`), when called with an existing subscription and no `planId`, takes the "retry-with-new-card" branch — sets `stripeCustomerId`/`stripePaymentMethodId` and flips `paymentSetup=true` via `applyPaymentMethod`. This task only needs to add the missing frontend entry point.

**Files:**
- Modify: `tinniestudio-client-web/src/features/account/components/ProfilePanelSubscription.tsx`

- [x] **Step 1: Add the new imports**

Current imports (top of file):

```tsx
import {
  AlertCircle,
  Check,
  Zap,
  Monitor,
  ChevronRight,
  Sparkles,
  Receipt,
} from 'lucide-react';
```

Add `CreditCard` to this list:

```tsx
import {
  AlertCircle,
  Check,
  Zap,
  Monitor,
  ChevronRight,
  Sparkles,
  Receipt,
  CreditCard,
} from 'lucide-react';
```

Current:
```tsx
import { useDisableCard, useEnableCard } from '../service/mutations';
```

Replace with:
```tsx
import {
  useDisableCard,
  useEnableCard,
  useStartPaymentSetup,
  useConfirmPaymentSetup,
} from '../service/mutations';
import { CardSetupForm } from './CardSetupForm';
```

- [x] **Step 2: Add the new hooks and state**

Current (lines ~61-64):

```tsx
  const router = useRouter();
  const disableMutation = useDisableCard();
  const enableMutation = useEnableCard();
  const [isPlansOpen, setIsPlansOpen] = useState(openPlansDialog || false);
```

Replace with:

```tsx
  const router = useRouter();
  const disableMutation = useDisableCard();
  const enableMutation = useEnableCard();
  const startSetup = useStartPaymentSetup();
  const confirmSetup = useConfirmPaymentSetup();
  const [isPlansOpen, setIsPlansOpen] = useState(openPlansDialog || false);
  const [addCardClientSecret, setAddCardClientSecret] = useState<string | null>(null);
```

- [x] **Step 3: Add the two handler functions**

Add directly after the existing `handleEnableCard` function (right before the `return (` that starts the main `hasAccess=true` JSX, i.e. after what's currently the closing `};` of `handleEnableCard`):

```tsx
  const handleStartAddCard = async () => {
    try {
      const intent = await startSetup.mutateAsync();
      setAddCardClientSecret(intent.clientSecret);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Could not start card setup';
      toast.error(message);
    }
  };

  // No planId sent here — SubscriptionServiceImpl.confirmPaymentSetup takes its
  // "retry-with-new-card" branch for any existing subscription, which is exactly
  // "add a card, keep my current plan" (see Task 9's note above).
  const handleAddCardSetupIntentConfirmed = async (setupIntentId: string) => {
    try {
      await confirmSetup.mutateAsync({ setupIntentId });
      toast.success('Card saved!');
      setAddCardClientSecret(null);
      router.refresh();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Could not confirm card setup';
      toast.error(message);
    }
  };
```

- [x] **Step 4: Render the new card**

Find the end of the main plan `<Card>` block — it closes right before the Payment History section:

```tsx
        </CardFooter>
      </Card>

      {subscription.payments && subscription.payments.length > 0 && (
```

Insert a new conditional `<Card>` between them:

```tsx
        </CardFooter>
      </Card>

      {!subscription.paymentSetup && (
        <Card className='bg-white/5 border-white/10 backdrop-blur-md rounded-3xl overflow-hidden'>
          <CardHeader className='p-8 pb-4'>
            <div className='flex items-center gap-3'>
              <div className='p-2 rounded-xl bg-white/5 border border-white/10'>
                <CreditCard className='w-5 h-5 text-white/60' />
              </div>
              <CardTitle className='text-xl font-semibold text-white font-manrope'>
                Add a Card
              </CardTitle>
            </div>
          </CardHeader>
          <CardContent className='p-8 pt-0'>
            {addCardClientSecret ? (
              <CardSetupForm
                clientSecret={addCardClientSecret}
                onSetupIntentConfirmed={handleAddCardSetupIntentConfirmed}
                submitLabel='Save card'
              />
            ) : (
              <>
                <p className='text-white/60 text-sm mb-4'>
                  No card on file yet. Add one to keep your {subscription.plan} plan renewing
                  automatically.
                </p>
                <Button
                  onClick={handleStartAddCard}
                  disabled={startSetup.isPending}
                  className='h-12 bg-tls-secondary hover:bg-tls-secondary/90 text-tls-primary rounded-xl font-bold'
                >
                  <CreditCard className='w-4 h-4 mr-2' />
                  Add a card
                </Button>
              </>
            )}
          </CardContent>
        </Card>
      )}

      {subscription.payments && subscription.payments.length > 0 && (
```

- [x] **Step 5: Type-check and lint**

Run: `cd tinniestudio-client-web && npm run type-check && npm run lint`
Expected: both succeed with no new errors.

- [x] **Step 6: Commit**

```bash
git add src/features/account/components/ProfilePanelSubscription.tsx
git commit -m "feat: add working card-setup flow for grandfathered subscribers"
```

**Known, deliberately out-of-scope wrinkle:** `AccountClient.tsx:112` passes `openPlansDialog={!!action}` whenever the URL has any `?action=...` param, so navigating from the nudge banner to `/account?action=billing` will still auto-open the (now-irrelevant, for this case) Plans switch dialog on top of this new card. The user can close that dialog (it's not a hard block) and the new "Add a Card" card underneath works correctly regardless. Not fixed here — this pre-existing `openPlansDialog` behavior predates this plan and affects other states too; narrowing it is a separate, independent cleanup, not required to make the add-card flow functional.

---

### Task 10: Manual verification in the browser

No test framework exists in `tinniestudio-client-web` ([[feedback_no_frontend_test_framework]]) — this task is a manual walkthrough, not automated.

**Files:** none (verification only).

- [x] **Step 1: Start the backend and frontend dev servers**

Run backend: `cd api-service && ../gradlew bootRun` (confirm it points at a Postgres you can freely write test rows into).
Run frontend: `cd tinniestudio-client-web && npm run dev`

- [x] **Step 2: Seed four account states directly in the DB and confirm each behaves correctly**

For a grandfathered legacy paid subscriber (`card_setup_required=false`, active non-free plan, no `stripe_customer_id`): log in as that user, confirm you can navigate anywhere in the app, confirm the yellow "Add a card to keep your plan active" banner is visible and dismissible without blocking navigation (and does not render while already under `/account`), and confirm the new "Add a Card" section on the Billing & Plans tab actually lets you submit a test card and ends with `paymentSetup=true` (banner and "Add a Card" section both disappear after refresh).

For an expired subscriber (`sub.end_date` in the past): log in, confirm general navigation works (no redirect to billing-gate on page load), then open any content's watch page and confirm it redirects to `/account/billing-gate` with the "Your plan has expired" copy.

For a genuinely new signup forced into the `NEEDS_SETUP` shape (`card_setup_required=true`, active non-free plan, no card): log in, confirm every route except `/account/*` immediately redirects to `/account/billing-gate`.

For the same grandfathered user as above, before adding a card: visit `/account?action=billing` directly (the nudge banner's link target) and confirm the auto-opened Plans dialog can be closed without issue, revealing the working "Add a Card" section underneath (the known wrinkle noted in Task 9).

- [x] **Step 3: Report results**

Note any deviation from the expected behavior above before considering this plan done.

---

### Task 11: Fix NEEDS_SETUP/EXPIRED priority ordering bug

**Why this task exists:** live end-to-end verification against the real backend + Postgres (performed while executing Task 10) found that `SubscriptionServiceImpl.getSubscriptionStatus` reports `blockReason="NEEDS_SETUP"` instead of `"EXPIRED"` for a subscriber whose paid period has expired AND who also has no card on file AND is not grandfathered (`cardSetupRequired=true` or no profile row at all — the default for every user, since `UserProfile` rows are created lazily and registration never creates one). This bug **predates** this plan (the original pre-Task-5 condition had the identical ordering problem — the `NEEDS_SETUP` check never excluded `periodExpired` before either), but it sits in the exact method this plan is correcting, and it directly contradicts CLAUDE.md's requirement that an expired user (new or existing) be routed to "confirm/renew" billing, not "set up a new card" — `BillingGate.tsx` shows genuinely different copy for the two reasons (`NEEDS_SETUP`: "Add a payment method to continue... 30-day free trial starts"; `EXPIRED`: "Your plan has expired... re-enable your card or add a new one").

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/billing/service/SubscriptionServiceImpl.java` (the `if` condition added in Task 5)
- Modify: `api-service/src/test/java/com/tinniestudio/api/billing/service/SubscriptionServiceTest.java`

- [x] **Step 1: Add a failing pinning test**

Add this test inside the `GetSubscriptionStatus` nested class, right after `getSubscriptionStatus_newPaidPlanNoCard_blocksWithNeedsSetup` (the test added in Task 4):

```java
        @Test
        @DisplayName("expired non-grandfathered subscriber with no card is blocked with EXPIRED, not NEEDS_SETUP")
        void getSubscriptionStatus_newPaidPlanNoCardAndExpired_blocksWithExpiredNotNeedsSetup() {
            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlan(silverPlan);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setContentWatchesUsed(0);
            sub.setStripeCustomerId(null);
            sub.setEndDate(Instant.now().minusSeconds(60));

            UserProfile profile = new UserProfile();
            profile.setPaymentSetup(false);
            profile.setCardSetupRequired(true);

            when(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(sub));
            when(userProfileRepository.findById(userId)).thenReturn(Optional.of(profile));
            when(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

            SubscriptionStatusResponse result = service.getSubscriptionStatus(userId);

            assertThat(result.isHasAccess()).isFalse();
            assertThat(result.getBlockReason()).isEqualTo("EXPIRED");
        }
```

- [x] **Step 2: Run it, confirm it currently fails**

Run: `cd api-service && ../gradlew test --tests "com.tinniestudio.api.billing.service.SubscriptionServiceTest"`
Expected: `getSubscriptionStatus_newPaidPlanNoCardAndExpired_blocksWithExpiredNotNeedsSetup` FAILS — `result.getBlockReason()` is `"NEEDS_SETUP"`, not `"EXPIRED"`.

- [x] **Step 3: Fix the condition**

Find the `if` condition Task 5 introduced:

```java
        if (cardSetupRequired && !isFreePlan && !paymentSetup && sub.getStripeCustomerId() == null) {
            blockReason = "NEEDS_SETUP";
            hasAccess = false;
        } else if (sub.getStatus() == SubscriptionStatus.PAST_DUE) {
```

Add `&& !periodExpired` (the variable is already computed earlier in this same method, right before the `paymentSetup` lookup):

```java
        if (cardSetupRequired && !isFreePlan && !paymentSetup && sub.getStripeCustomerId() == null && !periodExpired) {
            blockReason = "NEEDS_SETUP";
            hasAccess = false;
        } else if (sub.getStatus() == SubscriptionStatus.PAST_DUE) {
```

This makes `EXPIRED` win over `NEEDS_SETUP` whenever both would otherwise apply — an expired subscription is never reported as "needs initial setup," regardless of grandfathering status. No other line in the method changes.

- [x] **Step 4: Run the full test class, confirm everything passes**

Run: `cd api-service && ../gradlew test --tests "com.tinniestudio.api.billing.service.SubscriptionServiceTest"`
Expected: `BUILD SUCCESSFUL` except for the one already-known, pre-existing, unrelated `ProcessRenewal` failure (see Task 5's notes) — every `GetSubscriptionStatus` test, including the new one, passes.

- [x] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/billing/service/SubscriptionServiceImpl.java api-service/src/test/java/com/tinniestudio/api/billing/service/SubscriptionServiceTest.java
git commit -m "fix: EXPIRED takes priority over NEEDS_SETUP when both conditions apply"
```

- [x] **Step 6: Re-run live verification for the expired case only**

Re-run the specific scenario from Task 10 that failed: a non-grandfathered (or no-profile-row) subscriber on a paid plan, no card, `endDate` in the past. Confirm `GET /api/v1/subscriptions/me` now returns `blockReason: "EXPIRED"` (not `"NEEDS_SETUP"`). Clean up any test rows created for this check the same way Task 10 did.

---

### Task 12: Reject re-enabling a card that was never set up

**Why this task exists:** code review of Task 11 traced through what happens when an account that has never completed card setup (no `stripeCustomerId`/`stripePaymentMethodId` at all — exactly the grandfathered/expired population Task 11's fix newly routes to the `EXPIRED` billing-gate screen) clicks "Re-enable my card" there. `enableCard` sets `autoRenew=true`/`paymentSetup=true` unconditionally, then calls `processRenewal`, which calls `stripeService.chargeOffSession(null, null, ...)`. That fails inside Stripe but `chargeOffSession`'s `catch (StripeException ex)` converts it into a normal `ChargeResult(null, false, ...)`, so `processRenewal` just sets `status=PAST_DUE` and returns normally — `enableCard` never throws, the controller returns 200, and `ProfilePanelSubscription.tsx`'s `handleEnableCard` shows `toast.success('Card re-enabled!')` even though no card exists and `paymentSetup` was just incorrectly flipped to `true`. There's a working fallback right below it ("Add a new card"), so this isn't a dead end, but it's misleading. The fix is backend-only: reject the request before any mutation happens, which the existing frontend error-handling (`handleEnableCard`'s existing `try/catch` → `toast.error`) already displays correctly with zero frontend changes needed.

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/billing/service/SubscriptionServiceImpl.java`
- Modify: `api-service/src/test/java/com/tinniestudio/api/billing/service/SubscriptionServiceTest.java`

- [x] **Step 1: Add a failing test**

Add a new `@Nested` class for `enableCard()`, right after the existing `@Nested @DisplayName("disableCard()") class DisableCard { ... }` block:

```java
    @Nested
    @DisplayName("enableCard()")
    class EnableCard {

        @Test
        @DisplayName("rejects re-enabling when there is no card on file to re-enable")
        void enableCard_noStripeCustomerId_throwsBadRequest() {
            UserSubscription sub = new UserSubscription();
            sub.setUserId(userId);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setStripeCustomerId(null);
            sub.setStripePaymentMethodId(null);

            when(subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(Optional.of(sub));

            assertThatThrownBy(() -> service.enableCard(userId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No card on file");

            verify(subscriptionRepository, never()).save(any());
            verify(userProfileRepository, never()).save(any());
        }
    }
```

- [x] **Step 2: Run it, confirm it currently fails**

Run: `cd api-service && ../gradlew test --tests "com.tinniestudio.api.billing.service.SubscriptionServiceTest"`
Expected: the new test FAILS — today `enableCard` doesn't throw at all (no exception is thrown, so `assertThatThrownBy` fails).

- [x] **Step 3: Add the guard**

Current `enableCard` (`SubscriptionServiceImpl.java`):

```java
    @Override
    @Transactional
    public void enableCard(UUID userId) {
        UserSubscription sub = subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId)
            .orElseThrow(() -> new ResourceNotFoundException("No subscription found"));

        boolean expired = sub.getEndDate() != null && sub.getEndDate().isBefore(Instant.now());
        sub.setAutoRenew(true);
        subscriptionRepository.save(sub);
        userProfileRepository.findById(userId).ifPresent(profile -> {
            profile.setPaymentSetup(true);
            userProfileRepository.save(profile);
        });

        if (expired) {
            processRenewal(sub.getId());
        }
    }
```

Replace with:

```java
    @Override
    @Transactional
    public void enableCard(UUID userId) {
        UserSubscription sub = subscriptionRepository.findTopByUserIdOrderByCreatedAtDesc(userId)
            .orElseThrow(() -> new ResourceNotFoundException("No subscription found"));

        // An account that never completed card setup (no Stripe customer/payment method at all —
        // e.g. a grandfathered legacy subscriber, see docs/superpowers/specs/2026-10-26-legacy-
        // card-setup-grandfathering-design.md) has nothing to "re-enable." Without this check,
        // chargeOffSession(null, null, ...) fails inside Stripe, gets swallowed into a normal
        // PAST_DUE status update by processRenewal, and this method would return successfully
        // anyway — the client then shows "Card re-enabled!" for a card that was never added.
        if (sub.getStripeCustomerId() == null || sub.getStripePaymentMethodId() == null) {
            throw new BadRequestException("No card on file to re-enable — add a new card instead");
        }

        boolean expired = sub.getEndDate() != null && sub.getEndDate().isBefore(Instant.now());
        sub.setAutoRenew(true);
        subscriptionRepository.save(sub);
        userProfileRepository.findById(userId).ifPresent(profile -> {
            profile.setPaymentSetup(true);
            userProfileRepository.save(profile);
        });

        if (expired) {
            processRenewal(sub.getId());
        }
    }
```

- [x] **Step 4: Run the full test class, confirm everything passes**

Run: `cd api-service && ../gradlew test --tests "com.tinniestudio.api.billing.service.SubscriptionServiceTest"`
Expected: `BUILD SUCCESSFUL` except for the one already-known, pre-existing, unrelated `ProcessRenewal` failure. The new `EnableCard` test passes, and the existing `disableCard_periodNotYetExpired_stillHasAccess` test (which exercises a subscription WITH a `stripeCustomerId` already set, per Task 5's review notes) is unaffected since it already has a non-null `stripeCustomerId`.

- [x] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/billing/service/SubscriptionServiceImpl.java api-service/src/test/java/com/tinniestudio/api/billing/service/SubscriptionServiceTest.java
git commit -m "fix: reject re-enabling a card on a subscription that never had one"
```

No frontend change is needed — `ProfilePanelSubscription.tsx`'s existing `handleEnableCard` already has a `try/catch` that shows `toast.error(message)` on any thrown error from this endpoint.

---

## Self-Review Notes

- **Spec coverage:** Problem/Goals → Tasks 1-7. Migration semantics (unconditional exemption, lazy-profile backfill) → Task 2 + verified by Task 3. Nudge dismissibility ("can close the page") → Task 7. `EXPIRED`/`PAST_DUE` platform-access-but-no-content → Task 6 (removes app-wide block) relies on the pre-existing, unchanged watch-page gate (`watch/[slug]/page.tsx:67-78`) — no code change needed there, confirmed already correct in the design doc's research. Testing section → Tasks 3, 4, 10 (manual, backend-verifiable portion executed live; full browser walkthrough not possible in this environment).
- **Type consistency:** `cardSetupRequired` / `isCardSetupRequired()` / `setCardSetupRequired()` used identically in Task 1 (entity), Task 4 (tests), Task 5 (service). `card_setup_required` column name consistent across Task 2 SQL and Task 3 test SQL.
- Pre-existing, unrelated stray token at `AuthGuard.tsx:16` (`const router = useRouter();AuthGuard`) is out of scope for this plan — flagged separately, not touched here.
- **Task 9 addendum (post-review):** code review of Task 7 found the nudge banner's CTA had no working destination for a grandfathered user — `SubscriptionPlansPanel` only knows "first-ever setup" (`blockReason=NEEDS_SETUP`) or "switch plan," nothing in between. Task 9 closes that gap with a frontend-only addition (the backend's `confirmPaymentSetup` already handles a card-only update against an existing subscription with no `planId`). Task 10 (originally numbered Task 8) absorbed a fourth manual-check scenario for it.
