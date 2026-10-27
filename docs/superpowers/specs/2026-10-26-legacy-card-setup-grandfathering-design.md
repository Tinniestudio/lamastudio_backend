# Legacy Card-Setup Grandfathering — Design

## Problem

The recurring-billing model shipped in `2026-10-18-recurring-subscription-billing-design.md`
requires a payment method before granting access, enforced by
`SubscriptionServiceImpl.getSubscriptionStatus()`. That rule was meant to apply to
*new* signups only, but it has no way to distinguish a new user from a pre-existing
paying subscriber who was never asked for a card before recurring billing existed.
Result: legacy active subscribers get `blockReason=NEEDS_SETUP` and are redirected
to billing-gate on every route by `AuthGuard`, exactly like a brand-new user — the
reported bug ("existing user with active plan keeps getting asked to set up card").

A second, related bug: `AuthGuard` treats *any* `hasAccess=false` (including
`EXPIRED`/`PAST_DUE`) as a reason to block the entire app. CLAUDE.md's intent is
narrower: an expired/past-due user can still use the platform, just not watch
content — that should be enforced only at the point of playback, not app-wide.

## Goals

- Pre-existing accounts (as of this change shipping) are never hard-blocked for
  missing a card, regardless of their current plan or card state.
- New signups going forward still require a card before any access, exactly as the
  Oct 18 design specifies — this change must not weaken that.
- Pre-existing accounts on an active, unexpired, non-free plan with no card on file
  get a non-blocking nudge to add one (the "migration" to the new process), not a
  redirect — dismissible, doesn't block navigation.
- A user (new or legacy) whose plan is `EXPIRED`/`PAST_DUE` can use the rest of the
  platform; only attempting to watch content redirects them to billing.

## Out of scope

- Any change to the signup/checkout flow itself (how new users add a card) — that's
  already correct per the Oct 18 design.
- A backend request-level enforcement filter for `NEEDS_SETUP`. The hard block for
  genuinely new users stays a client-side routing concern, same as today.
- Changing `EXPIRED`/`PAST_DUE` detection logic — only how the client reacts to it.

## Data model & migration

Add `UserProfile.cardSetupRequired: boolean`, column `card_setup_required`,
`DEFAULT TRUE`.

New migration `V60__add_card_setup_required.sql`:
1. Insert a `user_profiles` row with `card_setup_required = FALSE` for every
   existing `users` row lacking one (profiles are created lazily today — see
   `UserProfileServiceImpl.createEmptyProfile`, `UserProfileServiceImpl.java:183-186`
   — so many legacy users have none).
2. `UPDATE user_profiles SET card_setup_required = FALSE` unconditionally, for every
   row that already exists at migration time.

This is a one-time, permanent exemption for every account that existed before this
ships — not a runtime date comparison. Any row created after the migration runs
inherits `TRUE` from the column default with no code change needed; the lazy
`createEmptyProfile` path already doesn't set this field explicitly, so it falls
through to the default correctly for real new signups.

Exemption is unconditional (not limited to users with an active paid plan) because
the hard-block policy itself is new — nobody who signed up before it existed should
ever be subject to it, including free-plan or already-expired legacy accounts. The
narrower "active, unexpired, non-free plan" condition only governs the *nudge*
(below), not this exemption.

## Backend logic — `SubscriptionServiceImpl.getSubscriptionStatus`

Current (buggy) condition, `SubscriptionServiceImpl.java:272`:

```java
if (!isFreePlan && !paymentSetup && sub.getStripeCustomerId() == null) {
    blockReason = "NEEDS_SETUP";
    hasAccess = false;
}
```

New condition — hard-block only when the account is still subject to the new-signup
policy:

```java
boolean cardSetupRequired = userProfileRepository.findById(userId)
    .map(UserProfile::isCardSetupRequired).orElse(true);

if (cardSetupRequired && !isFreePlan && !paymentSetup && sub.getStripeCustomerId() == null) {
    blockReason = "NEEDS_SETUP";
    hasAccess = false;
}
```

(`orElse(true)` only matters for the never-reached-in-practice case where no
`UserProfile` row exists at all — see confirmation below — defaulting to the safer,
stricter branch.)

For a grandfathered account (`cardSetupRequired=false`) on an active, unexpired,
non-free plan with no card: falls through to `hasAccess=true`, `blockReason=null`.
`paymentSetup` remains `false` in the response — that's the field the client uses
to render the non-blocking nudge. No DTO changes needed.

`EXPIRED`/`PAST_DUE` branches (`SubscriptionServiceImpl.java:275-283`) are unchanged.

Confirmed via code research: every normal signup path (`AuthService.java:103`,
`OAuth2Service.java:164`) creates an `ACTIVE` `FREE` `UserSubscription` unconditionally,
so `sub == null` in this method is unreachable for any account created through the
current registration/OAuth flows — it only guards a theoretical admin-created or
data-integrity edge case.

## Frontend (client-web)

**Narrow the app-wide block.** `AuthGuard.tsx:35-41,66-72` currently redirects to
`/account/billing-gate` for any `hasAccess=false`. Change the guard condition to only
redirect app-wide when `blockReason === 'NEEDS_SETUP'`. Remove the app-wide redirect
for `EXPIRED`/`PAST_DUE` — the existing per-content gate at
`watch/[slug]/page.tsx:67-78` (calling `GET /playback/access/{content.id}`) already
redirects to billing-gate specifically when the user tries to watch something, which
is the correct scope for "can use the platform, can't watch content."

**Add a non-blocking nudge.** New dismissible banner component, rendered in the
protected root layout (`app/(root)/(protected)/layout.tsx`), shown when
`hasAccess === true && paymentSetup === false && blockReason === null`. Copy: prompt
to add a card to keep the plan active; links to `/account?action=billing`;
dismissible — closing it doesn't block navigation and doesn't persist dismissal
server-side (CLAUDE.md: "they can close the page").

`BillingGate.tsx`'s existing `NEEDS_SETUP` copy/redirect (`:20-32`, `:60-63`) is
unchanged — it still applies to the genuinely-new-user hard-block case.

## Testing

**Backend:** unit tests on `SubscriptionServiceImpl`, same style as the existing
free-plan carve-out test (`SubscriptionServiceTest.java`):
- Grandfathered (`cardSetupRequired=false`) active non-free plan, no card →
  `hasAccess=true`, `blockReason=null`, `paymentSetup=false`.
- Non-grandfathered (`cardSetupRequired=true`) same data shape → `hasAccess=false`,
  `blockReason="NEEDS_SETUP"`.
- Existing `EXPIRED`/`PAST_DUE`/free-plan tests continue to pass unchanged.

**Migration:** a repeatable-migration or integration test asserting that after
`V60` runs, every pre-existing user has a `user_profiles` row with
`card_setup_required = FALSE`, and a freshly inserted row after that still defaults
to `TRUE`.

**Frontend:** no test framework in this repo — verify by type-check + lint, plus a
manual walkthrough of three account states in a browser: grandfathered-no-card
(expect nudge banner, full navigation), expired (expect platform access, billing
redirect only on watch attempt), and a fresh signup forced into `NEEDS_SETUP` data
shape (expect app-wide redirect).
