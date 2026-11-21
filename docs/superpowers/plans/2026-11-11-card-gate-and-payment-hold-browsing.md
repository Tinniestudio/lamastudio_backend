# Card-Required Access Gate & Payment-Hold Browsing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the loophole that lets new signups access content forever without ever adding a card, fix the Add Card button that currently schedules a plan switch instead of capturing a card, add Try-Again recovery to a Stripe load failure, and let existing PAST_DUE/EXPIRED subscribers keep browsing the catalogue and watching trailers while blocking only the actual "Play" action behind an in-place recovery modal.

**Architecture:** Part A is a one-line backend condition fix (delete an undocumented `isFreePlan` carve-out) plus three independent client-web UI fixes (pricing copy, a routing fix, a load-error state). Part B adds one new client-web modal component wired into the two existing "Play" CTA render sites (`HLSPlayer.tsx` trailer mode, `movie-card.tsx`), with a server-side redirect fallback for direct/bookmarked links. No new entities, no new endpoints — every mutation this plan uses already exists.

**Tech Stack:** Spring Boot / Java (server), Next.js / React / TypeScript (tinniestudio-client-web), JUnit 5 + Mockito (backend tests), manual + `tsc --noEmit` + `eslint` (frontend — no test framework exists in this repo).

**Spec:** `docs/superpowers/specs/2026-11-11-card-gate-and-payment-hold-browsing-design.md`

---

## Part A — Card-required-for-access fix

### Task 1: Backend — remove the `isFreePlan` carve-out

**Files:**

- Modify: `server/api-service/src/main/java/com/tinniestudio/api/modules/billing/service/SubscriptionServiceImpl.java:274-291`
- Modify: `server/api-service/src/test/java/com/tinniestudio/api/billing/service/SubscriptionServiceTest.java:369-398`

Current code at `SubscriptionServiceImpl.java:274-291`:

```java
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
        if (cardSetupRequired && !isFreePlan && !paymentSetup && sub.getStripeCustomerId() == null && !periodExpired) {
            blockReason = "NEEDS_SETUP";
            hasAccess = false;
        } else if (sub.getStatus() == SubscriptionStatus.PAST_DUE) {
```

- [ ] **Step 1: Write the failing regression test**

Add this test inside the existing `GetSubscriptionStatus` nested class, immediately after `getSubscriptionStatus_legacyFreePlanNoCard_stillHasAccess` (after line 398):

```java
        @Test
        @DisplayName("new (non-grandfathered) FREE-plan subscriber with no card is blocked with NEEDS_SETUP")
        void getSubscriptionStatus_newFreePlanNoCard_blocksWithNeedsSetup() {
            SubscriptionPlan freePlan = new SubscriptionPlan();
            freePlan.setId(UUID.randomUUID());
            freePlan.setName("FREE");

            UserSubscription sub = new UserSubscription();
            sub.setId(UUID.randomUUID());
            sub.setUserId(userId);
            sub.setPlan(freePlan);
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

Also fix the existing `getSubscriptionStatus_legacyFreePlanNoCard_stillHasAccess` test (lines 371-398) — it currently never sets `cardSetupRequired` explicitly, so it was only passing because of the `isFreePlan` bypass this task removes, not because of the grandfathering flag its name claims to test. Add one line so it actually exercises the grandfathering path:

```java
            UserProfile profile = new UserProfile();
            profile.setPaymentSetup(false);
            profile.setCardSetupRequired(false);
```

(This replaces the existing two-line `UserProfile profile = new UserProfile(); profile.setPaymentSetup(false);` block at lines 386-387.)

- [ ] **Step 2: Run tests to verify the new one fails and the existing ones still compile**

Run from `/home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server`:

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.billing.service.SubscriptionServiceTest"
```

Expected: `getSubscriptionStatus_newFreePlanNoCard_blocksWithNeedsSetup` FAILS (`result.isHasAccess()` is `true`, not `false` — the bug is still present). All other tests in the class still PASS.

- [ ] **Step 3: Remove the `isFreePlan` carve-out**

In `SubscriptionServiceImpl.java`, delete the `isFreePlan` variable declaration and its comment (the 5-line block ending in `boolean isFreePlan = ...;`), and remove `!isFreePlan &&` from the `if` condition. The method body from the `cardSetupRequired` line onward becomes:

```java
        boolean cardSetupRequired = profile.map(UserProfile::isCardSetupRequired).orElse(true);

        String blockReason;
        boolean hasAccess;
        if (cardSetupRequired && !paymentSetup && sub.getStripeCustomerId() == null && !periodExpired) {
            blockReason = "NEEDS_SETUP";
            hasAccess = false;
        } else if (sub.getStatus() == SubscriptionStatus.PAST_DUE) {
```

(Everything from the `else if (sub.getStatus() == SubscriptionStatus.PAST_DUE)` line onward is unchanged — leave it exactly as it is.)

- [ ] **Step 4: Run tests to verify they all pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.billing.service.SubscriptionServiceTest"
```

Expected: PASS — all tests in `GetSubscriptionStatus`, including the new one and the corrected legacy one.

- [ ] **Step 5: Run the full test module to check for unrelated regressions**

```bash
./gradlew :api-service:test
```

Expected: PASS (or the same pre-existing failures as before this change, if any — no new failures outside `SubscriptionServiceTest`).

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/billing/service/SubscriptionServiceImpl.java api-service/src/test/java/com/tinniestudio/api/billing/service/SubscriptionServiceTest.java
git commit -m "fix: close isFreePlan loophole letting new signups skip card setup"
```

---

### Task 2: Frontend — onboarding "$0.00 due today" pricing copy

**Files:**

- Modify: `tinniestudio-client-web/src/features/account/components/SubscriptionPlansPanel.tsx:236-255`

- [ ] **Step 1: Replace the price block**

Replace lines 236-255 (the `<div className='mb-6'>...</div>` price block inside `CardContent`):

```tsx
<div className="mb-6">
  {isCouponApplied && (
    <p className="text-white/40 line-through text-sm mb-1">
      CA {formatAmount(plan.price)}
    </p>
  )}
  <span className="text-4xl font-bold text-white">
    {formatAmount(displayPrice)}
  </span>
  <span className="text-white/40 text-sm ml-2">
    / {plan.billingCycle.toLowerCase()}
  </span>
  {isCouponApplied && (
    <Badge className="ml-2 bg-green-500/10 text-green-400 border-green-500/20 text-xs">
      {couponResult!.discountType === "PERCENTAGE"
        ? `${couponResult!.discountValue}% off`
        : `${plan.currency} ${couponResult!.discountValue} off`}
    </Badge>
  )}
</div>
```

with:

```tsx
<div className="mb-6">
  {isFirstEverSetup ? (
    <>
      <span className="text-4xl font-bold text-white">$0.00</span>
      <span className="text-white/40 text-sm ml-2">due today</span>
      <p className="text-white/40 text-xs mt-1">
        then {formatAmount(displayPrice)} / {plan.billingCycle.toLowerCase()}{" "}
        after your 30-day trial
      </p>
    </>
  ) : (
    <>
      {isCouponApplied && (
        <p className="text-white/40 line-through text-sm mb-1">
          CA {formatAmount(plan.price)}
        </p>
      )}
      <span className="text-4xl font-bold text-white">
        {formatAmount(displayPrice)}
      </span>
      <span className="text-white/40 text-sm ml-2">
        / {plan.billingCycle.toLowerCase()}
      </span>
    </>
  )}
  {isCouponApplied && (
    <Badge className="ml-2 bg-green-500/10 text-green-400 border-green-500/20 text-xs">
      {couponResult!.discountType === "PERCENTAGE"
        ? `${couponResult!.discountValue}% off`
        : `${plan.currency} ${couponResult!.discountValue} off`}
    </Badge>
  )}
</div>
```

(`isFirstEverSetup` and `displayPrice` are both already in scope — defined at lines 85 and 185-187 respectively. No other changes in this file.)

- [ ] **Step 2: Type-check and lint**

Run from `/home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web`:

```bash
npm run type-check && npm run lint
```

Expected: both PASS with no new errors.

- [ ] **Step 3: Manual verification**

Start the dev server (`npm run dev`), register a brand-new test account, reach the onboarding plan screen (`/account?action=billing` while `blockReason=NEEDS_SETUP`), and confirm both SILVER and GOLD cards show "$0.00 due today" plus "then {price} / {cycle} after your 30-day trial" — and that an _existing_ subscriber's "switch plan" dialog (not first-ever-setup) still shows the plain standing price, unchanged.

- [ ] **Step 4: Commit**

```bash
git add src/features/account/components/SubscriptionPlansPanel.tsx
git commit -m "feat: show \$0.00-due-today framing on first-time trial plan cards"
```

---

### Task 3: Frontend — fix the Add Card entry-point routing bug

**Files:**

- Modify: `tinniestudio-client-web/src/features/account/components/AccountClient.tsx:30-33,112`
- Modify: `tinniestudio-client-web/src/features/account/components/CardSetupNudge.tsx:44`

Root cause (confirmed by reading the code, not just the spec): `ProfilePanelSubscription.tsx`'s "Add a Card" card (lines 415-456) already renders correctly and unconditionally whenever `!subscription.paymentSetup` — it needs no new routing target. The only bug is that `AccountClient.tsx` forces the Plans dialog (`openPlansDialog`) open _on top of_ that already-correct card for any truthy `action` value, and the Plans dialog's `handleCheckout` schedules a plan switch for anyone who isn't `NEEDS_SETUP`. The fix is to stop forcing the dialog open for the "just add a card" case — not to build a new destination.

- [ ] **Step 1: Fix `AccountClient.tsx`'s action handling**

Replace lines 30-33:

```tsx
const parasms = useSearchParams();
const action = (parasms.get("action") as TAB_TYPES | null) || null;
const [localUser, setLocalUser] = useState<BackendUser>(user);
const [activeTab, setActiveTab] = useState<TAB_TYPES>(action || "profile");
```

with:

```tsx
const parasms = useSearchParams();
const action = parasms.get("action");
const initialTab: TAB_TYPES =
  action === "billing" || action === "add-card"
    ? "billing"
    : (action as TAB_TYPES) || "profile";
const [localUser, setLocalUser] = useState<BackendUser>(user);
const [activeTab, setActiveTab] = useState<TAB_TYPES>(initialTab);
```

Then replace line 112:

```tsx
<TabsContent value="billing" className="mt-0 outline-none">
  <ProfilePanelSubscription
    subscription={subscription}
    openPlansDialog={!!action}
  />
</TabsContent>
```

with:

```tsx
<TabsContent value="billing" className="mt-0 outline-none">
  <ProfilePanelSubscription
    subscription={subscription}
    openPlansDialog={action === "billing"}
  />
</TabsContent>
```

(`action === 'add-card'` now lands on the Billing tab — via `initialTab` — without forcing the Plans dialog open, so `ProfilePanelSubscription`'s existing, already-correct "Add a Card" card is immediately visible with nothing obscuring it. `action === 'billing'` keeps its current behavior unchanged, including the `NEEDS_SETUP` first-time-setup flow. No changes needed in `ProfilePanelSubscription.tsx` itself.)

- [ ] **Step 2: Update the nudge link**

In `CardSetupNudge.tsx`, replace line 44:

```tsx
<Link href="/account?action=billing">Add card</Link>
```

with:

```tsx
<Link href="/account?action=add-card">Add card</Link>
```

- [ ] **Step 3: Type-check and lint**

```bash
npm run type-check && npm run lint
```

Expected: both PASS.

- [ ] **Step 4: Manual verification**

Using a grandfathered test account (`cardSetupRequired=false`, active plan, no card — so the nudge banner shows per `CardSetupNudge.tsx`'s own render condition), click "Add card." Confirm you land on the Billing tab with the "Add a Card" card visible immediately (no Plans dialog on top), and that completing it calls the card-setup flow (toast "Card saved!") without ever showing a "Plan switch scheduled" toast or changing the subscription's plan.

- [ ] **Step 5: Commit**

```bash
git add src/features/account/components/AccountClient.tsx src/features/account/components/CardSetupNudge.tsx
git commit -m "fix: stop Add Card nudge from opening the plan-switch dialog"
```

---

### Task 4: Frontend — CardSetupForm "Try Again" on load failure

**Files:**

- Modify: `tinniestudio-client-web/src/features/account/components/CardSetupForm.tsx`
- Modify: `tinniestudio-client-web/src/features/account/components/ProfilePanelSubscription.tsx:429-433`

- [ ] **Step 1: Add the `onRetry` prop and load-error handling to `CardSetupForm.tsx`**

Replace the whole file content with:

```tsx
"use client";

import { useState } from "react";
import { loadStripe } from "@stripe/stripe-js";
import {
  Elements,
  PaymentElement,
  useStripe,
  useElements,
} from "@stripe/react-stripe-js";
import { Button } from "@/components/ui/button";
import { Loader2 } from "lucide-react";
import { getStripePublishableKey } from "@/config/public-env.config";

const stripeKey = getStripePublishableKey();
const stripePromise = stripeKey ? loadStripe(stripeKey) : null;

interface CardSetupFormProps {
  clientSecret: string;
  /** Called with the confirmed SetupIntent's id once Stripe.js has verified the card client-side. */
  onSetupIntentConfirmed: (setupIntentId: string) => void;
  submitLabel?: string;
  /** Called when the user clicks "Try Again" after a Stripe.js load failure — the caller should
   * fetch a fresh SetupIntent client secret and pass it back down via the `clientSecret` prop. */
  onRetry?: () => void;
}

export function CardSetupForm({
  clientSecret,
  onSetupIntentConfirmed,
  submitLabel = "Save card",
  onRetry,
}: CardSetupFormProps) {
  if (!stripePromise) {
    console.error("Stripe is not configured.");
    return (
      <p role="alert" className="text-red-400 text-sm">
        Something went wrong. Please contact support.
      </p>
    );
  }

  return (
    <Elements
      key={clientSecret}
      stripe={stripePromise}
      options={{
        clientSecret,
        appearance: {
          theme: "night",
          variables: {
            colorPrimary: "#fdcb2e",
            colorBackground: "#040926",
            colorText: "#ffffff",
            colorDanger: "#f87171",
          },
        },
      }}
    >
      <CardSetupFormInner
        onSetupIntentConfirmed={onSetupIntentConfirmed}
        submitLabel={submitLabel}
        onRetry={onRetry}
      />
    </Elements>
  );
}

function CardSetupFormInner({
  onSetupIntentConfirmed,
  submitLabel,
  onRetry,
}: Pick<
  CardSetupFormProps,
  "onSetupIntentConfirmed" | "submitLabel" | "onRetry"
>) {
  const stripe = useStripe();
  const elements = useElements();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [hasLoadError, setHasLoadError] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (hasLoadError) {
      onRetry?.();
      return;
    }

    if (!stripe || !elements) return;

    setIsSubmitting(true);
    setErrorMessage(null);

    try {
      const { error, setupIntent } = await stripe.confirmSetup({
        elements,
        redirect: "if_required",
      });

      if (error) {
        setErrorMessage(
          error.message ?? "Card setup failed. Please try again.",
        );
        return;
      }

      if (setupIntent?.status === "succeeded") {
        onSetupIntentConfirmed(setupIntent.id);
        return;
      }

      setErrorMessage("Card setup did not complete. Please try again.");
    } catch {
      // confirmSetup can reject outright (e.g. a network drop mid-call), not just resolve
      // with {error} — without this, isSubmitting would stay true forever with no way to retry.
      setErrorMessage("Card setup failed. Please try again.");
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <PaymentElement onLoadError={() => setHasLoadError(true)} />
      {hasLoadError && (
        <p role="alert" className="text-red-400 text-sm">
          The payment form failed to load. Check your connection and try again.
        </p>
      )}
      {errorMessage && (
        <p role="alert" className="text-red-400 text-sm">
          {errorMessage}
        </p>
      )}
      <Button
        type="submit"
        disabled={hasLoadError ? false : !stripe || !elements || isSubmitting}
        className="w-full h-12 bg-tls-secondary hover:bg-tls-secondary/90 text-tls-primary rounded-xl font-bold"
      >
        {isSubmitting ? (
          <Loader2 className="w-4 h-4 animate-spin mr-2" />
        ) : null}
        {hasLoadError ? "Try Again" : submitLabel}
      </Button>
    </form>
  );
}
```

(The `key={clientSecret}` on `<Elements>` is what makes "Try Again" actually work: once the caller's `onRetry` fetches a fresh client secret and passes a new `clientSecret` prop down, React fully remounts the `Elements`/`PaymentElement` tree — rather than reusing a Stripe.js instance that already failed to load — which resets `hasLoadError` back to `false` for a clean retry.)

- [ ] **Step 2: Wire `onRetry` at the one call site that needs it for now — `ProfilePanelSubscription.tsx`**

Replace lines 429-433:

```tsx
<CardSetupForm
  clientSecret={addCardClientSecret}
  onSetupIntentConfirmed={handleAddCardSetupIntentConfirmed}
  submitLabel="Save card"
/>
```

with:

```tsx
<CardSetupForm
  clientSecret={addCardClientSecret}
  onSetupIntentConfirmed={handleAddCardSetupIntentConfirmed}
  submitLabel="Save card"
  onRetry={handleStartAddCard}
/>
```

(`handleStartAddCard`, already defined at lines 191-199, already does exactly "fetch a fresh SetupIntent client secret and set it as state" — reusing it as `onRetry` needs no new function. `SubscriptionPlansPanel.tsx` and `BillingGate.tsx` also render `<CardSetupForm>` but `onRetry` is optional — leaving it unwired there means a load failure on those two screens shows "Try Again" with no action until a future pass; that's an acceptable, explicitly smaller follow-up, not a regression, since today none of the three screens handle this at all.)

- [ ] **Step 3: Type-check and lint**

```bash
npm run type-check && npm run lint
```

Expected: both PASS.

- [ ] **Step 4: Manual verification**

On the Billing tab's "Add a Card" card, open browser devtools, block requests to `js.stripe.com` (Network tab → request blocking), click "Add a card." Confirm the form shows the load-error message and a "Try Again" button instead of "Save card." Unblock the request, click "Try Again," confirm the form reloads and becomes usable.

- [ ] **Step 5: Commit**

```bash
git add src/features/account/components/CardSetupForm.tsx src/features/account/components/ProfilePanelSubscription.tsx
git commit -m "feat: add Try Again recovery for Stripe Payment Element load failures"
```

---

## Part B — Payment-hold catalogue browsing

### Task 5: Frontend — `PaymentHoldModal` component

**Files:**

- Create: `tinniestudio-client-web/src/components/content/PaymentHoldModal.tsx`

- [ ] **Step 1: Write the component**

```tsx
"use client";

import { useState } from "react";
import Link from "next/link";
import { CreditCard, RotateCcw } from "lucide-react";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { useRetryPayment } from "@/features/account/service/mutations";

interface PaymentHoldModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function PaymentHoldModal({
  open,
  onOpenChange,
}: PaymentHoldModalProps) {
  const retryPayment = useRetryPayment();
  const [retryError, setRetryError] = useState<string | null>(null);

  const handleRetryPayment = async () => {
    setRetryError(null);
    try {
      await retryPayment.mutateAsync();
      onOpenChange(false);
    } catch (err: unknown) {
      setRetryError(
        err instanceof Error
          ? err.message
          : "Payment retry failed. Try again or update your payment info.",
      );
    }
  };

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (next) setRetryError(null);
        onOpenChange(next);
      }}
    >
      <AlertDialogContent className="bg-tls-primary2 border-white/10">
        <AlertDialogHeader>
          <AlertDialogTitle className="text-white">
            Your account is on hold. Retry your payment?
          </AlertDialogTitle>
          <AlertDialogDescription className="text-white/60">
            We couldn&apos;t process your last payment. Retry your payment or
            update your payment information to continue enjoying the full movie
            library. You can still browse titles and watch available trailers.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {retryError && (
          <p role="alert" className="text-red-400 text-sm">
            {retryError}
          </p>
        )}
        <AlertDialogFooter>
          <AlertDialogCancel className="border-white/10 text-white/60 hover:bg-white/5 rounded-sm">
            Close
          </AlertDialogCancel>
          <Button
            asChild
            variant="outline"
            className="border-white/10 text-white rounded-sm"
          >
            <Link href="/account?action=add-card">
              <CreditCard className="w-4 h-4 mr-2" />
              Update Payment Info
            </Link>
          </Button>
          <Button
            onClick={handleRetryPayment}
            disabled={retryPayment.isPending}
            className="bg-tls-secondary hover:bg-tls-secondary/90 text-tls-primary rounded-sm"
          >
            <RotateCcw className="w-4 h-4 mr-2" />
            {retryPayment.isPending ? "Retrying…" : "Retry Card"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
```

Note on why "Retry Card" is a plain `Button`, not `AlertDialogAction`: confirmed by reading `node_modules/@radix-ui/react-alert-dialog/src/alert-dialog.tsx` that `AlertDialogAction` is literally `DialogPrimitive.Close` under the hood — it always closes the dialog on click. Since whether to close depends on an async mutation result that isn't known until after the click, a plain `Button` with manual `onOpenChange(false)` only on success is required; `AlertDialogCancel` is still used for "Close" since that action is correctly unconditional (dismiss always closes, no mutation involved).

- [ ] **Step 2: Type-check and lint**

```bash
npm run type-check && npm run lint
```

Expected: both PASS. (This component isn't wired into anything yet — Tasks 6 and 7 do that — so there's nothing to manually verify in isolation yet.)

- [ ] **Step 3: Commit**

```bash
git add src/components/content/PaymentHoldModal.tsx
git commit -m "feat: add PaymentHoldModal component"
```

---

### Task 6: Frontend — wire the modal into the content-detail hero CTA (`HLSPlayer.tsx`)

**Files:**

- Modify: `tinniestudio-client-web/src/components/content/HLSPlayer.tsx`

This covers the hero "Play"/"Watch Now" CTA in trailer mode, which both `ContentDetail.tsx` (movies/kids/lives) and `SeasonContentDetail.tsx` (shows/sermons) already render through — fixing it here covers both detail-page variants in one place. This task also implements the `?paymentHold=1` fallback flag handling (Task 8's redirect target), since this is the component that actually renders the modal.

- [ ] **Step 1: Add imports and state**

Add to the import block (after the existing `import { useRouter } from 'next/navigation';` at line 25):

```tsx
import { useRouter, usePathname, useSearchParams } from "next/navigation";
```

Add to the imports below `import { resolveWatchCTA, formatTime } from '@/utils';` (line 37):

```tsx
import { PaymentHoldModal } from "./PaymentHoldModal";
```

In the component body, after `const router = useRouter();` (line 117), add:

```tsx
const pathname = usePathname();
const searchParams = useSearchParams();
const [showPaymentHoldModal, setShowPaymentHoldModal] = useState(false);
```

- [ ] **Step 2: Handle the `?paymentHold=1` fallback flag**

Add this effect directly after the `cta` computation (`const cta = resolveWatchCTA(subscription, contentSlug, activeEpisodeId);`, line 122):

```tsx
useEffect(() => {
  if (mode !== "trailer") return;
  if (searchParams.get("paymentHold") !== "1") return;

  setShowPaymentHoldModal(true);
  const params = new URLSearchParams(searchParams.toString());
  params.delete("paymentHold");
  router.replace(
    params.toString() ? `${pathname}?${params.toString()}` : pathname,
    { scroll: false },
  );
  // Deliberately runs once on mount per `mode`/`pathname` change, not on every searchParams
  // change — it strips its own flag immediately, so re-running on the resulting searchParams
  // change would be a no-op anyway, but listing searchParams would also make eslint flag a
  // missing dependency incorrectly re-triggering this after the replace() above settles.
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [mode, pathname]);
```

- [ ] **Step 3: Replace the CTA render block to intercept PAST_DUE/EXPIRED clicks**

Replace lines 567-580 (the `{/* Subscription CTA */}` block):

```tsx
{
  /* Subscription CTA */
}
<Link href={cta.href}>
  <button
    className={cn(
      "flex items-center gap-2 px-5 py-2.5 rounded-xl text-sm font-semibold font-inter transition-all duration-300 active:scale-95 cursor-pointer",
      cta.isWatch
        ? "bg-tls-accent hover:bg-tls-accent/90 text-white shadow-lg shadow-tls-accent/20"
        : "bg-tls-secondary hover:bg-tls-secondary/90 text-tls-primary shadow-lg shadow-tls-secondary/20",
    )}
  >
    <Play className="w-4 h-4 fill-current" />
    {cta.label}
  </button>
</Link>;
```

with:

```tsx
{
  /* Subscription CTA */
}
{
  subscription?.blockReason === "PAST_DUE" ||
  subscription?.blockReason === "EXPIRED" ? (
    <button
      onClick={() => setShowPaymentHoldModal(true)}
      className="flex items-center gap-2 px-5 py-2.5 rounded-xl text-sm font-semibold font-inter transition-all duration-300 active:scale-95 cursor-pointer bg-tls-secondary hover:bg-tls-secondary/90 text-tls-primary shadow-lg shadow-tls-secondary/20"
    >
      <Play className="w-4 h-4 fill-current" />
      {cta.label}
    </button>
  ) : (
    <Link href={cta.href}>
      <button
        className={cn(
          "flex items-center gap-2 px-5 py-2.5 rounded-xl text-sm font-semibold font-inter transition-all duration-300 active:scale-95 cursor-pointer",
          cta.isWatch
            ? "bg-tls-accent hover:bg-tls-accent/90 text-white shadow-lg shadow-tls-accent/20"
            : "bg-tls-secondary hover:bg-tls-secondary/90 text-tls-primary shadow-lg shadow-tls-secondary/20",
        )}
      >
        <Play className="w-4 h-4 fill-current" />
        {cta.label}
      </button>
    </Link>
  );
}
```

- [ ] **Step 4: Render the modal**

Immediately after the closing `</AnimatePresence>` of the trailer-mode render block (right before its enclosing `</div>` at line 592-593 — the trailer-mode `return` block), add:

```tsx
<PaymentHoldModal
  open={showPaymentHoldModal}
  onOpenChange={setShowPaymentHoldModal}
/>
```

placed as a sibling just before the final `</div>` that closes the trailer-mode wrapper (so it's present in the DOM for trailer mode regardless of `showControls`/`AnimatePresence` state — it must not be nested inside the `AnimatePresence`/`motion.div` that only renders when controls are visible, since the modal should still be reachable from the `?paymentHold=1` auto-open path even if the controls overlay itself is currently hidden).

- [ ] **Step 5: Type-check and lint**

```bash
npm run type-check && npm run lint
```

Expected: both PASS.

- [ ] **Step 6: Manual verification**

Using a test account with `status=PAST_DUE` (or `EXPIRED`), navigate to a movie's detail page. Confirm: the page loads normally (no redirect), the trailer plays, and the hero CTA button (still labeled "Update Payment"/"Renew Your Plan" per the existing `cta.label`) opens `PaymentHoldModal` in place instead of navigating anywhere. Confirm "Retry Card" and "Update Payment Info" both work, and "Close" dismisses without granting access. Separately, visit the same detail page URL with `?paymentHold=1` appended manually and confirm the modal opens automatically and the query param disappears from the URL afterward.

- [ ] **Step 7: Commit**

```bash
git add src/components/content/HLSPlayer.tsx
git commit -m "feat: open PaymentHoldModal in place for PAST_DUE/EXPIRED hero CTA"
```

---

### Task 7: Frontend — wire the modal into catalogue grid cards (`movie-card.tsx`)

**Files:**

- Modify: `tinniestudio-client-web/src/components/ui/movie-card.tsx`

- [ ] **Step 1: Add imports and state**

Add below `import { resolveWatchCTA } from '@/utils/content';` (line 14):

```tsx
import { PaymentHoldModal } from "@/components/content/PaymentHoldModal";
```

In the component body, after `const cta = resolveWatchCTA(subscription, content.slug);` (line 29), add:

```tsx
const [showPaymentHoldModal, setShowPaymentHoldModal] = useState(false);
const isPaymentHoldBlocked =
  subscription?.blockReason === "PAST_DUE" ||
  subscription?.blockReason === "EXPIRED";
```

- [ ] **Step 2: Replace the Play button**

Replace lines 82-90:

```tsx
<Button
  size="icon"
  className="rounded-full bg-tls-secondary text-black hover:scale-110 transition-transform"
  asChild
>
  <Link href={cta.href}>
    <Play className="w-5 h-5 fill-current" />
  </Link>
</Button>
```

with:

```tsx
{
  isPaymentHoldBlocked ? (
    <Button
      size="icon"
      onClick={() => setShowPaymentHoldModal(true)}
      className="rounded-full bg-tls-secondary text-black hover:scale-110 transition-transform"
    >
      <Play className="w-5 h-5 fill-current" />
    </Button>
  ) : (
    <Button
      size="icon"
      className="rounded-full bg-tls-secondary text-black hover:scale-110 transition-transform"
      asChild
    >
      <Link href={cta.href}>
        <Play className="w-5 h-5 fill-current" />
      </Link>
    </Button>
  );
}
```

- [ ] **Step 3: Render the modal**

Add just before the component's closing `</motion.div>` (line 163):

```tsx
<PaymentHoldModal
  open={showPaymentHoldModal}
  onOpenChange={setShowPaymentHoldModal}
/>
```

- [ ] **Step 4: Type-check and lint**

```bash
npm run type-check && npm run lint
```

Expected: both PASS.

- [ ] **Step 5: Manual verification**

Using the same `PAST_DUE`/`EXPIRED` test account, browse a catalogue grid (e.g. `/movies`), hover a card, click its Play button. Confirm the modal opens in place (no navigation to `/subscription-billing`), and that an ACTIVE-subscription account's cards are completely unaffected (still navigate via the `Link` as before).

- [ ] **Step 6: Commit**

```bash
git add src/components/ui/movie-card.tsx
git commit -m "feat: open PaymentHoldModal in place for PAST_DUE/EXPIRED grid card CTA"
```

---

### Task 8: Frontend — server-side fallback redirect for direct `/watch` links

**Files:**

- Modify: `tinniestudio-client-web/src/app/(player)/watch/[slug]/page.tsx:73-79`

- [ ] **Step 1: Change the redirect target for PAST_DUE/EXPIRED**

Replace lines 73-79:

```tsx
if (!accessResp.success || !accessResp.data?.hasAccess) {
  const reason = accessResp.data?.reason;
  if (reason === "CONTENT_NOT_PUBLISHED") {
    redirect("/start");
  }
  redirect("/account/billing-gate");
}
```

with:

```tsx
if (!accessResp.success || !accessResp.data?.hasAccess) {
  const reason = accessResp.data?.reason;
  if (reason === "CONTENT_NOT_PUBLISHED") {
    redirect("/start");
  }
  if (reason === "PAST_DUE" || reason === "EXPIRED") {
    // Direct/bookmarked link that skipped the client-side gate (HLSPlayer's trailer-mode CTA
    // normally intercepts this before ever navigating here) — bounce back to the detail page
    // with a flag it reads on load to open the same PaymentHoldModal, instead of sending an
    // existing subscriber who can still browse away to billing-gate/a marketing page.
    const separator = returnTo.includes("?") ? "&" : "?";
    redirect(`${returnTo}${separator}paymentHold=1`);
  }
  redirect("/account/billing-gate");
}
```

(`returnTo` is already computed above at line 71 as `/${routeSegment}/${slug}` via `getSafeReturnTo`. `NEEDS_SETUP` falls through to the unchanged final `redirect('/account/billing-gate')`, exactly as today.)

- [ ] **Step 2: Type-check and lint**

```bash
npm run type-check && npm run lint
```

Expected: both PASS.

- [ ] **Step 3: Manual verification**

Using a `PAST_DUE`/`EXPIRED` test account, open a direct `/watch/{slug}` URL (bypassing the catalogue/detail page entirely — e.g. typing it directly into the address bar). Confirm it redirects to the content-detail page with `?paymentHold=1`, which (per Task 6) opens the modal automatically and then strips the flag from the URL. Using a `NEEDS_SETUP` test account, confirm `/watch/{slug}` still redirects to `/account/billing-gate` unchanged.

- [ ] **Step 4: Commit**

```bash
git add "src/app/(player)/watch/[slug]/page.tsx"
git commit -m "feat: redirect blocked /watch links to the detail page's payment-hold modal"
```

---

## Self-Review Notes

**Spec coverage:** Part A's four fix areas (backend gate, onboarding copy, Add Card routing, load-error recovery) map to Tasks 1-4. Part B's client-side gate, server-side fallback, and modal content/actions map to Tasks 5-8. The "episode-select trailer swap + scroll" addendum belongs to the separate new-content-notifications spec/plan, not this one — not included here.

**Scope correction carried from plan-writing:** the spec's "PAST_DUE/EXPIRED/CANCELLED" wording is corrected throughout this plan to PAST_DUE/EXPIRED only — `CANCELLED` is not a possible `blockReason` value (confirmed in `PlaybackServiceImpl.checkAccess`/`AccessCheckResponse.denied`). See the spec's own corrected text.

**Architecture simplification found while planning:** the spec described Part A's Add Card fix as routing to "a dedicated Add-Card UI," which reads as needing new UI. Reading the actual code showed that UI already renders unconditionally — the only real bug is `openPlansDialog` being forced open on top of it. Task 3 implements the smaller, equally-correct fix.
