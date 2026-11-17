# New-Content Notifications Broadcast — Design

## Background

Two CLAUDE.md items, both about telling users when new content becomes available:

- "when admin approve content notification should be send to all user to imform
  them about new content"
- "Notify users about new arrivals" (Notifications, Priority: Medium) — trigger a
  notification when new arrivals become available, identify the content clearly,
  deep-link to it where supported, and respect notification preferences/device
  permissions.

These are the same feature from two angles: broadcasting a "new content available"
notification to all opted-in users. The existing per-user notification pipeline
(`Notification`/`NotificationTemplate`/`NotificationPreference` entities,
`NotificationServiceImpl.sendNotification`) is fully built but has no
broadcast-to-all-users primitive, and nothing today calls it for content becoming
available — `CONTENT_APPROVED`/`CONTENT_REJECTED` exist in the event-type enum but
have zero call sites.

## Scope decisions made during brainstorming

- **Trigger point is publish, not approve.** The content-approval state machine has
  an intermediate `PROCESSING` state between `REVIEW` ("approve") and `PUBLISHED`
  ("publish") — content isn't actually visible/playable until `PUBLISHED`. CLAUDE.md's
  wording ("admin approve") is read as "the moment the content becomes available,"
  which is the `PUBLISHED` transition, not the literal `approve` action.
- **New episodes/seasons on an already-published show are included.** Today, adding
  a season/episode to an existing `PUBLISHED` title fires no event at all — this spec
  adds one.
- **Audience = every user who hasn't opted out**, consistent with the existing
  per-channel `NotificationPreference` suppression model (no preference row = not
  suppressed = included).
- **Channels: `IN_APP` and `EMAIL` only.** No push-notification infrastructure exists
  in this codebase (`NotificationChannel` enum has no `PUSH` value, no FCM/APNs/device
  token integration anywhere) — CLAUDE.md's "device permissions" line doesn't map to
  anything buildable today and is out of scope. Building push infrastructure is a
  separate, larger follow-up.
- **Real email sending, via the existing `ResendEmailService`**, decoupled from the
  triggering request by running through an async worker (RabbitMQ consumer), not
  inline. This is new: `EMAIL` is currently just a label on `NotificationTemplate`/
  `NotificationPreference` — `sendNotification` never actually dispatches an email
  for any event type today.
- **Superseded by a later decision in this same brainstorming session** (made while
  designing the separate device-login-limit feature, which also needs a single event
  to deliver via both channels): real email sending is generalized directly into
  `sendNotification()` itself, not kept as bespoke logic inside this broadcast
  consumer — see "Template schema change" below. Both this feature and the
  device-eviction-alert feature call the same, now-multi-channel-aware
  `sendNotification()`.

## Trigger points — two new event types

Add to `NotificationEventType` (`DomainEnums.java`):

- **`CONTENT_PUBLISHED`** — fired from `ContentService.transitionStatus()` only when
  the target status is `PUBLISHED` (not on other transitions).
- **`EPISODE_PUBLISHED`** — fired from `SeasonService.create()`/`EpisodeService.create()`
  only when the parent `Content.status == PUBLISHED` (a season/episode added to an
  already-live show). Adding a season/episode to a title that isn't published yet
  does not fire this — the whole-title `CONTENT_PUBLISHED` event will cover it once
  the title itself publishes.

`CONTENT_APPROVED`/`CONTENT_REJECTED` are untouched — those remain reserved for a
possible future single-recipient notification to the uploading partner, a different
concern from this all-viewers broadcast.

## Delivery: async via existing RabbitMQ worker pattern

Rather than looping over every user inline during the admin's `publish`/`create` HTTP
request (which would block the response on a potentially large audience, especially
once real email sending is involved), the trigger point publishes one lightweight
message to a queue:

```
{ eventType: "CONTENT_PUBLISHED" | "EPISODE_PUBLISHED",
  contentTitle: "<title, captured at publish time>",
  referenceType: "CONTENT",
  referenceId: "<content id>" }
```

(`referenceType` is always `CONTENT` — see "Episode deep-link" below; `EPISODE_PUBLISHED`
still points at the parent content's id, not the episode's.)

A new consumer method, alongside the existing `NotificationConsumer.handleNotificationEvent`
(same `@RabbitListener` shape used today for `CONTENT_PROCESSED`), picks up the message
and does the fan-out in the background. The admin's HTTP response returns immediately
regardless of audience size.

**Idempotency**: RabbitMQ's at-least-once delivery means a message could be redelivered
after a consumer crash mid-processing. Guard with the same cache-lock pattern already
used in `SubscriptionExpirationJob` (`cacheService.setIfAbsent`), keyed by
`(eventType, referenceId)` — a redelivery becomes a no-op instead of re-notifying
everyone.

## Template schema change: one template per (event_type, channel)

`notification_templates` currently has a unique constraint on `event_type` alone, so
each event type has exactly one template row with exactly one `channel` — there is no
way to represent "deliver this event via both IN_APP and EMAIL, each with its own
copy." Both this feature and the device-login-limit feature's eviction alert need
exactly that, so this spec changes the constraint to `(event_type, channel)`: an
event type may now have up to two active template rows (one `IN_APP`, one `EMAIL`),
each with independent title/body copy (email copy can be longer/more formal than an
in-app blurb). Existing single-channel event types are unaffected — one row, same
behavior as today.

`NotificationServiceImpl.sendNotification()` changes its template lookup from
`findByEventTypeAndIsActiveTrue` (single) to a list-returning
`findAllByEventTypeAndIsActiveTrue`, and loops: for each active template row found for
the event type, check that user's `NotificationPreference` for `(eventType,
template.channel)` — default enabled if no row exists — and if not suppressed,
deliver via that row's channel (`IN_APP` → create a `Notification` row as today;
`EMAIL` → send via `ResendEmailService`, generalized into this shared method rather
than being bespoke to any one caller). This is the single place both the broadcast
consumer below and the device-eviction alert (separate spec) call.

**Pre-flight check**: no `notification_templates` rows are seeded via migration —
they're created live through the admin UI. Before shipping, audit any existing
`EMAIL`-channel template rows in production; generalizing real sending means any of
them would start actually emailing for the first time.

## Audience resolution & per-user channel preferences

For each registered user and each active template row for the event type, the
consumer calls `sendNotification()` (see above) — which itself resolves that user's
`(eventType, channel)` preference and delivers or skips accordingly. A user may have
IN_APP, EMAIL, both, or neither enabled for the same event; default (no preference
row) is enabled, matching the existing suppression check.

User IDs are fetched via a lightweight projection query, not full `User` entities,
following the existing `saveAll`-after-loop pattern used elsewhere in the codebase
(`SubscriptionExpirationJob`, `SessionServiceImpl.revokeAllUserSessions`) rather than
introducing a new batch-SQL mechanism.

## Dynamic content naming

`NotificationServiceImpl` currently copies `template.getTitleTemplate()`/
`getBodyTemplate()` verbatim with no placeholder substitution — every notification for
an event type is identical static text, which can't satisfy "the notification
identifies the new content clearly" (CLAUDE.md's acceptance criterion). Add simple
`{title}` placeholder substitution: the broadcast path replaces `{title}` in both
title and body templates with the actual `contentTitle` from the queue message before
saving the `Notification` row / rendering the email. Additive and backward-compatible
— templates without the placeholder behave exactly as before; other event types are
unaffected.

## Episode deep-link

The client's `/watch` route always keys by the parent content's slug, with episode
selection via a `?episode={id}` query param (`/watch/{slug}?episode={episodeId}`) —
the `Notification` entity has no second id slot to carry both a content slug and an
episode id. Per decision during brainstorming: `EPISODE_PUBLISHED` notifications use
`referenceType=CONTENT`, `referenceId=<parent content id>` — tapping lands on the
show's content-detail page (satisfies "opens the relevant content"), not directly on
the specific episode. No schema change, no extra lookup round-trip on tap.

On the client, `NotificationBell.tsx`'s `resolveHref()` already has a `CONTENT` case
(`/watch/${referenceId}`) and a documented extension point (`default: return null`,
comment: "Extend this map as reference types that carry a real client route are
identified") — no client change is needed for this, since both new event types use
the existing `CONTENT` reference type.

## Addendum: episode-select trailer swap + scroll visibility

Raised during this brainstorming session as a closely related, small UX fix on the
same content-detail/episode surface (not yet in CLAUDE.md as its own item).

**Problem, two distinct bugs in two components:**

- **`SeasonContentDetail.tsx`** (shows/sermons route): clicking an episode *does*
  correctly swap the hero trailer (`useTrailerManifest` is keyed on
  `episodeId`/`seasonId`/`contentId`, `SeasonContentDetail.tsx:71-75`) — but
  `handleEpisodeSelect`'s `router.push(..., { scroll: false })`
  (`SeasonContentDetail.tsx:77-84`) deliberately suppresses Next.js's scroll-to-top,
  so a user who has scrolled down to browse the episode list never sees the hero
  player update and assumes nothing happened.
- **`ContentDetail.tsx`** (movies/kids/lives route): `useTrailerManifest({ contentId:
  data.id })` (`ContentDetail.tsx:53`) is **not** parameterized by episode at all, so
  `handleEpisodeSelect` (`ContentDetail.tsx:55-57`) only updates local `activeEpisode`
  state for the episode-list UI's active styling — the hero trailer never actually
  changes here, independent of scroll. Per decision during brainstorming, this is
  fixed too (a movie/kids/lives-categorized title can still structurally have
  seasons/episodes), not left as a route-specific limitation.

**Fix:**

1. `ContentDetail.tsx`: parameterize `useTrailerManifest` by `activeEpisode?.id` (and
   season id, if resolvable in this component), mirroring
   `SeasonContentDetail.tsx`'s already-correct pattern — so episode selection actually
   swaps the hero trailer here too.
2. Both components: add a `ref` on the hero/player wrapper div (the topmost element
   in both components' JSX today — `ContentDetail.tsx:70`, `SeasonContentDetail.tsx:99`)
   and call `ref.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })`
   inside `handleEpisodeSelect`, in both components, so the trailer change is always
   visible regardless of scroll position. Keep `SeasonContentDetail.tsx`'s existing
   `scroll: false` on `router.push` as-is (avoids double-handling/jank between Next.js's
   own scroll behavior and the explicit smooth scroll) — the explicit `scrollIntoView`
   call is the sole scroll mechanism in both components.

## Error handling & edge cases

- No active template configured for `CONTENT_PUBLISHED`/`EPISODE_PUBLISHED` → skip
  silently, same as existing `sendNotification` behavior.
- Partial failure during fan-out (e.g. `ResendEmailService` fails for one user — bad
  address, transient API error): log and continue to the next user. One failed email
  must not abort the whole broadcast or roll back in-app rows already written for
  other users.
- Content title is captured in the queue message at publish time, not re-fetched by
  the consumer — avoids a null/stale title if the content is edited or deleted before
  the message is processed.

## Testing

**Backend:**
- `ContentService.transitionStatus` publishes the broadcast message only on
  transition to `PUBLISHED`, not on other transitions.
- `SeasonService.create`/`EpisodeService.create` publish `EPISODE_PUBLISHED` only
  when the parent `Content` is already `PUBLISHED`.
- `sendNotification()` multi-channel test: an event type with both an `IN_APP` and
  an `EMAIL` active template delivers both independently per that user's own
  preference combination (IN_APP-only, EMAIL-only, both, neither, no preference row).
  An event type with only one template row behaves exactly as before (regression
  check against existing single-channel event types).
- `AdminNotificationTemplateController` CRUD test: creating a second template for an
  event type that already has one is now allowed if the channel differs, still
  rejected if it matches an existing active row's channel (constraint moved from
  `event_type` to `(event_type, channel)`).
- Idempotency test: redelivering the same message produces no duplicate
  rows/emails.
- Template placeholder test: `{title}` substituted correctly in title and body, for
  each channel's template row independently.

**Frontend** (no test framework in this repo — type-check + lint + manual):
- Trigger a real publish in a dev environment; confirm the in-app notification
  appears for a test user and an email arrives via Resend's sandbox/test mode.
- Episode-select walkthrough on both `/movies/[slug]`-style and `/shows/[slug]`-style
  pages: clicking an episode while scrolled down visibly swaps the trailer and
  scrolls the hero back into view in both cases.
