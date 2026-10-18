# Recurring Subscription Billing — Design

## Goals

Replace the current one-time-checkout-only payment model with a card-on-file,
auto-renewing subscription:

- New users must add a payment method before accessing any content.
- A 14-day free trial starts the moment the card is saved; no charge during the trial.
- At trial end, and every 30 days thereafter, the saved card is charged automatically.
- Users can disable their card (stops auto-renewal, but current access rides out the
  remaining paid period) and re-enable it later.
- Users whose renewal charge failed can retry with the existing card or add a new one,
  instead of being stuck on an indefinite loading state (the reported bug).
- Users can switch plans; the new price/plan takes effect at the next renewal only.

## Out of scope

- Multiple saved cards per user (only one card on file at a time; adding a new one
  replaces the old).
- Proration for mid-cycle plan switches.
- Grace periods / automatic retries after a failed renewal charge (failure = immediate
  access restriction; recovery is user-initiated).
- Live, same-session access revocation (access changes take effect on next navigation,
  not via a push mechanism to an already-open page).

## Billing mechanism

Two Stripe integration approaches were considered:

- **Stripe Subscriptions (native):** Stripe tracks trial/renewal dates and fires
  lifecycle webhooks, but its default failed-payment retry/dunning behavior is
  configured at the Stripe account level (Dashboard), not cleanly overridable per
  subscription via API — in tension with the "immediate restriction, no retries"
  requirement.
- **SetupIntent + custom scheduled job (chosen):** Stripe is used only for PCI-safe
  card tokenization (`SetupIntent` → saved `PaymentMethod` on a Stripe `Customer`).
  Trial/renewal dates are tracked on our own `UserSubscription` row, and a scheduled
  job (same pattern as the existing `PendingPaymentExpiryJob`) fires off-session
  `PaymentIntent`s when renewals are due. This gives full control over trial,
  renewal, and failure semantics with no hidden Stripe account-level dependency.

## Data model

**`UserProfile`** gains:
- `paymentSetup: boolean` — the access gate. `false` for new users until they complete
  card setup; also what "disable card" flips to `false`.

**`UserSubscription`** (already has `status`, `startDate`, `endDate`, `autoRenew`,
`cancelledAt`) gains:
- `stripeCustomerId: String` — created once, reused across trial/renewals/card changes.
- `stripePaymentMethodId: String` — the single saved card; replaced wholesale on
  "add new card."
- `trialEndsAt: Instant` — set at signup (`now + 14 days`); cleared after the first
  successful charge.
- `nextRenewalAt: Instant` — date the next charge is due. Initialized to
  `trialEndsAt`, then pushed forward 30 days on each successful charge.
- `pendingPlanId: Long` (nullable) — if the user switches plans mid-cycle, the new
  plan id waits here and swaps into the active plan at the next renewal.
- `status` gains a `PAST_DUE` value, set when a renewal charge fails.

No new `Payment` table changes — each renewal charge produces a `Payment` row the
same way one-time checkout does today (type `RENEWAL`), just triggered by the job
instead of user-initiated checkout.

## Backend flows

**Signup / initial card setup:** new user has `paymentSetup=false`, blocked by the
client gate. They submit a card via Stripe Elements; the client confirms a
`SetupIntent` with Stripe.js, then POSTs the resulting `setupIntentId` to the backend.
The backend verifies the SetupIntent server-side (never trusts client-reported state),
creates/reuses the Stripe `Customer`, stores `stripePaymentMethodId`, sets
`paymentSetup=true`, `trialEndsAt = now + 14d`, `nextRenewalAt = trialEndsAt`. No
charge yet — full access starts immediately (trial).

**Renewal job** (daily cron): selects subscriptions where `autoRenew=true` and
`nextRenewalAt <= now`. For each, under a row lock (to avoid racing a concurrent
disable):
1. If `pendingPlanId` is set, swap it into the active plan now (apply-at-renewal,
   no proration).
2. Fire an off-session `PaymentIntent` against `stripePaymentMethodId` for the active
   plan's price, using an idempotency key derived from `(userId, nextRenewalAt)` so a
   job rerun never double-charges.
3. **Success:** write a `Payment` row (type `RENEWAL`), advance `endDate`/
   `nextRenewalAt` by 30 days, clear `trialEndsAt`.
4. **Failure** (declined, or `requires_action` — treated as failure since the user
   isn't present off-session to complete 3DS): `status` flips to `PAST_DUE`, blocking
   access immediately. No retries by the job itself.

**Disable card:** `paymentSetup=false`, `autoRenew=false`. Card stays attached to the
Stripe Customer (not detached) so re-enabling is instant. Access continues until
`endDate` naturally passes.

**Re-enable card:** if `endDate` hasn't passed yet, it's a free toggle
(`paymentSetup=true`, `autoRenew=true`). If `endDate` has already passed, re-enabling
behaves like a retry: immediate off-session charge attempt with the existing card;
success sets `ACTIVE` and schedules the next renewal 30 days out.

**Retry after `PAST_DUE`:** one endpoint retries the existing card; another accepts a
new `SetupIntent` and retries with the new card. Either path, on success, clears
`PAST_DUE` and restores access.

**Plan switch:** sets `pendingPlanId` only; applied during the next renewal job run.

## API endpoints

New additions to `SubscriptionController`:

| Endpoint | Purpose |
|---|---|
| `POST /subscription/payment-setup/intent` | Creates/reuses the Stripe `Customer`, returns a `SetupIntent` client secret. Used for initial signup **and** "add a new card" during retry. |
| `POST /subscription/payment-setup/confirm` | Body: `setupIntentId`. Verifies with Stripe, saves `stripePaymentMethodId`. First-ever setup: starts the trial. Retry-with-new-card: immediately attempts the off-session charge and clears `PAST_DUE` on success. Verifies the SetupIntent's Customer matches the authenticated user's `stripeCustomerId` (prevents cross-user replay). |
| `POST /subscription/card/disable` | `paymentSetup=false`, `autoRenew=false`. |
| `POST /subscription/card/enable` | Free toggle if `endDate` hasn't passed; otherwise behaves like a retry. |
| `POST /subscription/payment/retry` | Retries the off-session charge against the existing saved card. |
| `PATCH /subscription/plan` | Sets `pendingPlanId`, applied at next renewal. |
| `GET /subscription/status` | Returns `{ paymentSetup, hasAccess, blockReason, trialEndsAt, endDate, nextRenewalAt, pendingPlanId }` — computed server-side, the single source of truth for client gating. `blockReason` is one of `NEEDS_SETUP`, `PAST_DUE`, `EXPIRED`, or `null`. |

`hasAccess`/`blockReason` are derived on every call, not read directly off the stored
`status` enum (a disabled-but-not-yet-expired subscription still has `status=ACTIVE`
in the database): `NEEDS_SETUP` when `paymentSetup` was never completed; `PAST_DUE`
when `status==PAST_DUE`; `EXPIRED` when `paymentSetup=false` and `endDate` has passed;
otherwise `hasAccess=true`.

**Webhooks:** the existing `payment_intent.succeeded`/`payment_intent.payment_failed`
handlers in `StripeWebhookController` are extended to also progress `UserSubscription`
state when `Payment.type == RENEWAL` (in addition to the existing one-time-checkout
path). One new handler, `setup_intent.succeeded`, is a safety net in case a client
disconnects after completing a 3DS challenge but before calling
`/payment-setup/confirm`.

## Client-side gating & UI (tinniestudio-client-web)

**`AuthGuard.tsx` extension:** after the existing `isAuthenticated`/`roles` check
passes, it calls a new `useSubscriptionStatus` hook hitting `GET /subscription/status`.
When `hasAccess` is false, it redirects to one new route (e.g. `/account/billing-gate`)
— a single screen component whose copy and CTAs branch on `blockReason`:

- `NEEDS_SETUP` — brand-new user, never completed card setup.
- `PAST_DUE` — a renewal charge failed. **This directly replaces the "stuck on movie
  loading screen" bug** with a clear retry screen.
- `EXPIRED` — card was disabled and the paid-through period has elapsed.

For `NEEDS_SETUP` and "add a new card" under `PAST_DUE`, the screen embeds Stripe's
`PaymentElement` directly in our own UI (not a hosted Checkout redirect, since the
`SetupIntent` must be confirmed client-side), then POSTs to `/payment-setup/confirm`.
`PAST_DUE` retry-with-existing-card is a single button hitting `/payment/retry`.
`EXPIRED` is a single button hitting `/card/enable`.

**Account settings:** `ProfilePanelSubscription.tsx` gets a new panel showing current
plan, next renewal date, a disable/enable card toggle, and a change-plan selector that
calls `PATCH /subscription/plan` and shows "takes effect on {nextRenewalAt}".

## Error handling & edge cases

- **Race condition:** the renewal job re-checks `autoRenew=true` under a row lock
  immediately before charging, so a user who disables their card mid-job-run doesn't
  get charged.
- **Idempotency:** off-session `PaymentIntent` creation uses an idempotency key
  derived from `(userId, nextRenewalAt)`.
- **Cross-user replay:** `/payment-setup/confirm` verifies Customer ownership before
  saving a payment method.
- **Mid-session access revocation:** not solved live; the next `AuthGuard` check on
  navigation enforces the new state. No websocket/live-revocation mechanism.

## Testing

**Backend:** unit tests for the renewal job (success, decline, idempotent rerun,
race-condition lock) and for each new endpoint with a mocked `StripeService`,
following the mocking pattern used in the existing payment-session-expiry tests.
Manual verification against Stripe test-mode cards (standard decline codes, 3DS-required
test cards) for the full signup → trial → renewal → disable → retry sequence.

**Client:** no test framework exists in `tinniestudio-client-web` — verification is
type-check + lint + manual walkthrough of the billing-gate screen for each
`blockReason` state in a browser, using Stripe test cards.
