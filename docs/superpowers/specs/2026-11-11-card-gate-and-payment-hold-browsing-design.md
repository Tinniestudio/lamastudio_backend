# Card-Required Access Gate & Payment-Hold Browsing — Design

## Background

Two related CLAUDE.md items, in `server` and `tinniestudio-client-web`, both concern how
subscription/payment state gates video access:

- Server: "free tier should be activated until user add card, as current logic allow
  user to access free tier without setting card."
- Client-web: 3.1–3.3 (simplify onboarding, require card before subscription-protected
  access, correct trial/renewal logic), the Add Card workflow bug, and the
  existing-user payment-hold browsing requirement.

Two prior specs already shipped in this area and remain authoritative for everything
they cover:

- `2026-10-18-recurring-subscription-billing-design.md` — card-on-file model, 30-day
  trial, renewal job, `blockReason` semantics (`NEEDS_SETUP`/`PAST_DUE`/`EXPIRED`).
- `2026-10-26-legacy-card-setup-grandfathering-design.md` — `UserProfile.cardSetupRequired`
  flag permanently exempting pre-existing (as of Oct 26) accounts from the card
  requirement, regardless of plan.

This spec covers only the gaps between those two designs' stated intent and what the
code actually does today, plus one net-new feature (payment-hold browsing). It does
not revisit or change the Oct 18/Oct 26 designs' own decisions.

## Part A — Card-required-for-access fix

### Problem

`SubscriptionServiceImpl.getSubscriptionStatus()` has an `isFreePlan` condition
(`SubscriptionServiceImpl.java:282-290`) that unconditionally skips the `NEEDS_SETUP`
block for any subscriber on the FREE plan:

```java
boolean isFreePlan = sub.getPlan() == null || "FREE".equalsIgnoreCase(sub.getPlan().getName());
...
if (cardSetupRequired && !isFreePlan && !paymentSetup && sub.getStripeCustomerId() == null && !periodExpired) {
    blockReason = "NEEDS_SETUP";
    hasAccess = false;
}
```

Every signup (`AuthService.createFreeSubscription`, `OAuth2Service.createFreeSubscription`)
auto-creates an `ACTIVE` FREE-plan `UserSubscription` with no card involved. Because
`isFreePlan` is true for that row, the block never fires — regardless of
`cardSetupRequired`. This defeats the Oct 18 design's stated goal ("new users must add
a payment method before accessing any content") for every new signup, not just
grandfathered legacy accounts. It is not part of either prior spec's intended logic;
the Oct 26 spec's own proposed condition already omits any FREE-plan carve-out from
its reasoning, confirming this is an undocumented regression layered on top, not a
deliberate design choice.

`CapabilityServiceImpl.canWatch()` is unrelated to this bug — it only drives a
cosmetic `canWatch` field used for a content-watch-quota display (confirmed via a full
call-site search), not the actual playback gate. The real gate,
`PlaybackServiceImpl.checkAccess()`, already delegates solely to
`getSubscriptionStatus().isHasAccess()`, so fixing that one method is sufficient.

### Fix

**Backend** — `SubscriptionServiceImpl.getSubscriptionStatus()`: delete the
`isFreePlan` variable and its clause from the gate condition. New condition:

```java
if (cardSetupRequired && !paymentSetup && sub.getStripeCustomerId() == null && !periodExpired) {
    blockReason = "NEEDS_SETUP";
    hasAccess = false;
}
```

Effect:
- Legacy accounts (`cardSetupRequired=false`, set by the Oct 26 `V60` migration):
  unaffected — still exempt, by that flag alone, exactly as Oct 26 intended.
- New signups (`cardSetupRequired=true`, the column default): now correctly blocked
  with `NEEDS_SETUP` even while sitting on the auto-created FREE plan row, until they
  complete card setup. This closes the loophole without any new migration or
  behavior change for legacy users.

No change to `AuthService.createFreeSubscription`/`OAuth2Service.createFreeSubscription`
— it's harmless to keep auto-creating the FREE row; the gate now blocks it correctly
regardless of plan.

**Onboarding UI** (`tinniestudio-client-web`, `SubscriptionPlansPanel.tsx`): keep both
SILVER and GOLD plan cards visible (not collapsed to a single offer) — plan choice is
not being hidden, per explicit decision during brainstorming. Update each card's
pricing copy to show two figures clearly: **"$0.00 due today"** and that plan's actual
post-trial renewal price/cycle (e.g. "then $X.XX/month after your 30-day trial"). This
replaces any copy that only shows the plan's standing price without the trial framing.

**Add Card entry-point bug**: `CardSetupNudge.tsx` currently links to
`/account?action=billing`, which `AccountClient.tsx` (`openPlansDialog={!!action}`)
routes to `SubscriptionPlansPanel`. That panel's `handleCheckout`
(`SubscriptionPlansPanel.tsx:87-106`) only treats `blockReason === 'NEEDS_SETUP'` as
"first-time setup" (calling `confirmPaymentSetup`); anyone else — including a
grandfathered or already-active user with no card — falls into the plan-switch
branch (`switchPlanMutation` → `PATCH /subscriptions/plan`), which schedules a plan
change and never captures a card. This is the exact bug described in CLAUDE.md
("plan switch scheduled... card is not added").

Fix: introduce a second action value, `action=add-card`, distinct from
`action=billing`. `AccountClient`/`ProfilePanelSubscription` route `add-card` directly
to the existing, already-correct dedicated Add-Card UI
(`ProfilePanelSubscription.tsx:415-456`, which already calls `confirmPaymentSetup`
with no `planId` — the right "add a card, keep my current plan" path), bypassing
`SubscriptionPlansPanel` entirely. Update `CardSetupNudge.tsx`'s link to use
`action=add-card`. `action=billing` remains reserved for genuine plan
browsing/switching (and for the `NEEDS_SETUP` first-time-setup flow, which already
works correctly through that path).

**Card-form load-error handling** (`CardSetupForm.tsx`): the Stripe.js `loaderror`
event (reported in CLAUDE.md: "Unhandled payment Element loaderror") is currently not
handled at all — no `onLoadError` prop on `<PaymentElement>`, no listener on
`<Elements>`. Add a `hasLoadError` state, wire `onLoadError` on the Payment Element to
set it, and swap the submit button from its default "Save card" label to "Try Again"
when set. "Try Again" re-fetches a fresh SetupIntent client secret
(`startPaymentSetup` again) and remounts `<Elements>` with a new `key`, rather than
attempting `confirmSetup` against a form that never finished loading.

## Part B — Payment-hold catalogue browsing

### Problem

Catalogue listing and content-detail pages already impose no access checks, and
trailer manifests are already unauthenticated and ungated — most of CLAUDE.md's
acceptance criteria for this item are already satisfied incidentally. The actual gap:
`watch/[slug]/page.tsx` (`:61-79`) does a server-side access check via
`GET /playback/access/{content.id}` and hard-redirects on any `hasAccess=false` to
either `/account/billing-gate` or, via `resolveWatchCTA` (`src/utils/content.ts`), a
static `/subscription-billing` marketing page — with no in-place recovery UI, and no
distinction between a genuinely-new user and an existing user whose access lapsed.

Per decision during brainstorming, this fix was meant to apply to all three
"existing user whose access lapsed" states: `PAST_DUE`, `EXPIRED`, and `CANCELLED`.
**Correction found during plan-writing**: `CANCELLED` is not actually a possible
`blockReason` value — `PlaybackServiceImpl.checkAccess()` returns
`AccessCheckResponse.denied(status.getBlockReason())`, and `blockReason` is only ever
`NEEDS_SETUP`, `PAST_DUE`, `EXPIRED`, or `null`. A cancelled-but-not-yet-expired
subscription still has `hasAccess=true`/`blockReason=null` today (access continues
until the paid period naturally ends, per the Oct 18 design) — it only becomes
blocked once that period lapses, at which point `blockReason` is `EXPIRED`, already
covered. So the modal's actual trigger set is `PAST_DUE`/`EXPIRED` only.
`CANCELLED` surfaces elsewhere, as a separate `resolveWatchCTA()` CTA-label quirk
(a cancelled-but-still-active user sees a "Resubscribe" CTA instead of "Watch Now")
— confirmed out of scope for this plan per decision during plan-writing. It
explicitly does not change `NEEDS_SETUP` handling either — those users are brand-new
and already can't reach the catalogue at all (`AuthGuard.tsx` redirects any
non-`/account/*` route when `blockReason === 'NEEDS_SETUP'`), which is correct and
out of scope here.

### Fix

**Primary path — client-side gate.** Every "Play" entry point for full content
(content-detail page's primary CTA, catalogue `MovieCard`/`ContentCard` CTAs) already
has access to the current `useSubscription()` status in memory. Before navigating to
`/watch/[slug]`, check `hasAccess`/`blockReason`: if `blockReason` is one of
`PAST_DUE`/`EXPIRED`, open `PaymentHoldModal` in place instead of
navigating — no extra network call, no page transition.

**Fallback path — server-side safety net.** `watch/[slug]/page.tsx` keeps its existing
access check (for direct/bookmarked links that skip the client-side gate), but for
these three states its redirect target changes from `/account/billing-gate` /
`/subscription-billing` to the referring content-detail page with a `?paymentHold=1`
query flag, which the detail page reads on load to open the same modal. `NEEDS_SETUP`
keeps its current redirect to `/account/billing-gate` unchanged.

`resolveWatchCTA` (`src/utils/content.ts`) is updated so its `PAST_DUE`/`EXPIRED` branches trigger the modal-open handler instead of returning a direct
link to `/subscription-billing`.

**`PaymentHoldModal` component** — new component built on the existing `AlertDialog`
primitives (`src/components/ui/alert-dialog.tsx`), the same pattern already used for
the "turn off auto-renew?" confirmation. Content per CLAUDE.md's suggested copy:

- Heading: "Your account is on hold. Retry your payment?"
- Message: explains the payment failure and that catalogue browsing/trailers remain
  available.
- **Retry Payment** (primary action) → reuses the existing `useRetryPayment` mutation
  (`POST /subscriptions/payment/retry`, already wired in `BillingGate.tsx`); shows a
  success or failure result in the modal without closing it on failure.
- **Update Payment Info** (secondary action) → navigates to `/account?action=add-card`
  (the Part A fix), opening the dedicated card-setup UI.
- **Dismiss/close** → closes the modal, returns to the current page; does not grant
  playback access.

Copy is shared across both trigger states (`PAST_DUE`/`EXPIRED`) —
no per-state copy variants, per decision during brainstorming.

## Data flow summary

```
Signup (no card) ──> UserSubscription(FREE, ACTIVE) + UserProfile(cardSetupRequired=true default)
                           │
                           ▼
          getSubscriptionStatus(): cardSetupRequired && !paymentSetup && no stripeCustomerId
                           │
                           ▼
                   blockReason = NEEDS_SETUP ──> AuthGuard redirects app-wide to billing-gate

Existing user, payment fails ──> renewal job sets status=PAST_DUE
                           │
                           ▼
          getSubscriptionStatus(): status==PAST_DUE ──> blockReason=PAST_DUE, hasAccess=false
                           │
                           ▼
     Catalogue/detail pages: no change (already ungated) ── Trailers: no change (already ungated)
                           │
                 user clicks "Play" on a full movie/episode
                           │
                           ▼
        client-side check (useSubscription) ──> PaymentHoldModal (in place, no navigation)
                           │                                │
                 (direct /watch link, no client check)      ├─ Retry Payment → POST /subscriptions/payment/retry
                           ▼                                └─ Update Payment Info → /account?action=add-card
        /watch server check (fallback) ──> redirect to detail page with ?paymentHold=1
```

## Error handling & edge cases

- **Retry Payment fails inside the modal**: show the failure reason in place (reusing
  `useRetryPayment`'s existing error surface); modal stays open, user can try
  "Update Payment Info" instead or dismiss.
- **Modal triggered via the `?paymentHold=1` fallback flag**: once shown, the flag is
  stripped from the URL (`router.replace`) so a page refresh doesn't re-open it
  unprompted.
- **`action=add-card` with no existing subscription row at all** (theoretical
  admin/data-integrity edge case, per Oct 26 spec's own note that `sub == null` is
  otherwise unreachable through normal signup): falls back to the existing
  `sub == null` branch in `getSubscriptionStatus`, which already returns
  `blockReason=NEEDS_SETUP` — routes correctly to the first-time setup UI regardless.
- **CardSetupForm "Try Again"**: must regenerate the SetupIntent client secret rather
  than reusing a stale one, since the original may have expired or belonged to a
  failed Elements mount.

## Testing

**Backend** (`SubscriptionServiceTest.java`, extending the existing free-plan
carve-out test style):
- New signup (`cardSetupRequired=true`), FREE plan, no card → `hasAccess=false`,
  `blockReason=NEEDS_SETUP` (currently fails before this fix — the regression test).
- Legacy account (`cardSetupRequired=false`), FREE plan, no card → `hasAccess=true`,
  `blockReason=null` (unchanged from today).
- Existing `PAST_DUE`/`EXPIRED`/non-free-plan tests continue to pass unchanged.

**Frontend** (no test framework in either repo — type-check + lint + manual
walkthrough, per existing project convention):
- New signup walkthrough: confirm blocked from any route until card added; confirm
  onboarding shows both plan cards with "$0.00 due today" + correct post-trial price
  per plan.
- Nudge-banner walkthrough (grandfathered account, no card): click "Add card" →
  confirm the dedicated Add-Card form opens directly, submitting it adds a card
  without changing the plan.
- Card-form load-error walkthrough: simulate a Stripe.js load failure (e.g. block the
  Stripe script in devtools) → confirm "Try Again" appears and recovers on retry.
- Payment-hold walkthrough, for each of `PAST_DUE`/`EXPIRED`: confirm
  catalogue browsing and trailer playback are unaffected; confirm clicking "Play" on a
  full movie opens the modal in place (no navigation); confirm Retry Payment and
  Update Payment Info both work; confirm dismiss does not grant access; confirm a
  direct `/watch/[slug]` link falls back to the same modal via the detail page
  redirect.
