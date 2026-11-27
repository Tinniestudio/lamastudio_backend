# Watch Duration Tracking — Design

**Date:** 2026-11-27
**Scope:** backend (`server`) + partner dashboard (`tinniestudio-partner-web`). Admin dashboard is spec-only in this document — not implemented this round (admin work hasn't started yet).
**Explicitly out of scope:** partner payment/payout calculation from watch duration. The source notes (`tinniestudio-partner-web/CLAUDE.md`) frame watch duration as the eventual basis for partner payment, but that calculation (rates, payout periods, ledger) is a separate, financially-sensitive feature to be spec'd on its own later. This spec only makes watch duration real, accurate, and visible.

## Why

`tinniestudio-partner-web/CLAUDE.md` describes a watch-duration feature: every user's watch duration should be recorded per content, surfaced on the partner dashboard (per-content duration, a polled total, a day/hour-filterable bar chart, top-5-by-views), and eventually aggregated on an admin dashboard. Investigating the current codebase surfaced that this isn't an extension of existing functionality — it's greenfield:

- `content_analytics_daily.watch_time_seconds` / `content_analytics_weekly.watch_time_seconds` columns exist but are **dead** — every upsert query hardcodes `0`. Nothing has ever written a real value to them.
- No hourly rollup exists at all (only daily/weekly), so the notes' "filter by hour/hours/day/week" can't be served today.
- The partner dashboard (`PartnerDashboardResponse`) has no watch-duration stat, and no per-content watch-duration field exists on `PartnerContentResponse`.

## Section 1 — Data model & tracking pipeline (backend)

**New column:** `contents.watch_time_seconds BIGINT NOT NULL DEFAULT 0` — lifetime running total, mirroring the existing `view_count` column (same incremented-on-write pattern, same migration style as `V40`/`V46`).

**New table:** `content_analytics_hourly`
```sql
CREATE TABLE content_analytics_hourly (
    content_id         UUID NOT NULL,
    hour_start          TIMESTAMPTZ NOT NULL,  -- truncated to the hour, UTC
    watch_time_seconds  BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (content_id, hour_start)
);
```
Scoped to watch duration only — no `views`/`completions`/`unique_viewers` columns, since nothing in scope needs hour-level resolution for those.

**How a watch-duration event is measured:**

The client (`tinniestudio-client-web`, `usePlaybackProgress.ts`) reports absolute playback *position* every 15s (plus on pause/unmount), not elapsed watch time. This is not being changed as part of this spec (decided: backend-only fix, no client-web changes). Consequently:

- `PlaybackServiceImpl.recordProgress` already loads the existing `WatchProgress` row before overwriting it — capture its old `progressSeconds` as `oldProgress` before mutating.
- `rawDelta = newProgress - oldProgress` (0 for a brand-new `WatchProgress` row).
- Clamp: `delta = max(0, min(rawDelta, 30))`.
  - Floor at 0 discards backward seeks (client sends a lower position than last time — never subtract).
  - Cap at 30s (2× the 15s heartbeat interval) discards forward-seek jumps from being credited as watched time, while tolerating normal jitter from network delay, tab backgrounding, or the pause/unmount report landing close to a heartbeat.
  - Known limitation: a genuine 15-30s of real watching is indistinguishable from a 15-30s forward seek. Accepted tradeoff for a backend-only fix.

**Event flow change:**

- `PlaybackServiceImpl.recordProgress` adds `watchedDeltaSeconds` (the clamped delta above) to the existing `PROGRESS_TRACKED` RabbitMQ message.
- `AnalyticsConsumer.handleProgressTracked` reads it and — when `> 0` — calls a new `AnalyticsEventProcessor.processWatchTimeEvent(contentId, deltaSeconds)`.
- `processWatchTimeEvent` is `@Transactional` (same bean-boundary pattern as `processViewEvent`/`processCompletionEvent` — must go through the real Spring proxy, not self-invocation) and atomically:
  1. Increments `contents.watch_time_seconds` by `deltaSeconds`.
  2. Upserts `content_analytics_hourly` for `truncate(now(), HOUR)`, adding `deltaSeconds`.
  3. Upserts `content_analytics_daily.watch_time_seconds` for today, adding `deltaSeconds` (the existing insert's hardcoded `0` becomes a bound parameter).
- The existing completion check (`progress/duration >= 0.90` → `processCompletionEvent`) is unaffected and still runs from the same `PROGRESS_TRACKED` message.
- **No change needed to `WeeklyAnalyticsRollupJob`** — it already does `SUM(watch_time_seconds)` from daily nightly; once daily holds real numbers, weekly does too.

This keeps hourly/daily writes synchronous with the event (no separate hourly→daily rollup job), which matters because the dashboard polls every 3-5 minutes and expects numbers that are actually fresh, not waiting on a nightly job.

**Retention:** no cleanup job for `content_analytics_hourly` in this spec — out of scope; flagged as future work if hourly row growth becomes a concern.

## Section 2 — Backend API

**New endpoints** (`AnalyticsController`, same `@PreAuthorize("hasRole('ADMIN') or hasRole('PARTNER')")` + ownership-check pattern already used for the daily/weekly endpoints):
- `GET /analytics/contents/{id}/hourly?from&to` — hourly buckets for one content item.
- `GET /analytics/partners/me/hourly?from&to` — hourly buckets summed across the calling partner's whole catalog.

Both follow the existing `getContentAnalytics`/`getPartnerAnalytics` shape: default range if `from`/`to` omitted (suggest last 24-48h, since hourly data is for recent/near-term views — day/week views keep using the existing daily/weekly endpoints), 404-not-403 on content the caller doesn't own (enumeration-safe, matching existing convention).

**Extended existing DTOs** (no new DTOs needed for these):
- `AnalyticsSummaryResponse` / `WeeklyAnalyticsSummaryResponse`: add `totalWatchTimeSeconds` (a true sum of `watchTimeSeconds` across rows). They already compute `avgWatchTimeSeconds`; a sum is a one-line addition alongside it.
- `PartnerDashboardResponse`: add `totalWatchDurationSeconds`, sourced from a new `ContentRepository.sumWatchTimeSecondsByCreatedBy(partnerId)` query (mirrors the existing `sumViewCountByCreatedBy`). O(1) read off the running-total column — no scanning historical daily rows on every poll.
- `PartnerContentResponse` / `ContentResponse`: add `watchTimeSeconds` (lifetime, straight off `Content.watchTimeSeconds`) — this is what satisfies "each content watch duration shown on partner dashboard."

**Top-5-by-views** (all-time lifetime `view_count`, confirmed — not scoped to the chart's current date filter): no new endpoint. `PartnerService.listContents` already accepts a `Pageable`; add a thin `GET /partners/me/content/top?limit=5` that calls `listContents(partnerId, PUBLISHED, null, PageRequest.of(0, 5, Sort.by("viewCount").descending()))` and returns the existing `PartnerContentResponse` list (already has `title`/`viewCount`).

## Section 3 — Partner dashboard UI (`tinniestudio-partner-web`)

- **`DashboardContent.tsx`**: add a "Total watch duration" stat tile (human-formatted h/m, not raw seconds) to the existing 4-tile grid. Wire `useDashboard`'s query with `refetchInterval: 4 * 60 * 1000` (middle of the 3-5 min range) so the dashboard polls automatically — matches "duration is pool every 3-5 minutes."
- **New `WatchDurationBarChart.tsx`**: `recharts` `BarChart`, added alongside (not replacing) the existing `AnalyticsChart` line chart. A filter control (Hour / Day / Week) switches the backing endpoint: new `/partners/me/hourly`, existing daily, existing weekly. Each bar = total watch duration for that bucket ("each bar show all content watch duration of the day").
- **New `TopContentList.tsx`**: ranked list — content name + view count, top 5, calling the new `/partners/me/content/top` endpoint.
- **Content-level duration**: `ContentAnalyticsPageClient.tsx` (existing per-content analytics page) gets a watch-duration series added to its chart (reading the new content-level hourly endpoint plus the now-real daily data); content list/card views surface the new `watchTimeSeconds` field next to the existing view count.
- All of the above extends existing files/components rather than introducing new infrastructure. Verification is type-check + lint + manual click-through in a browser (no frontend test framework in any of the three web repos — see project memory).

## Section 4 — Admin (future phase, spec-only — not implemented this round)

The data model above (hourly/daily/weekly tables + the `contents.watch_time_seconds` running total) is partner-agnostic, so admin's future work is pure aggregation over the same tables — no schema changes anticipated when that phase starts:

- Content-level hourly/daily/weekly: same endpoints, admin-authorized, any content id — already works today via the existing `isAdmin` bypass in `AnalyticsServiceImpl`.
- **Top 100 content by views**: same pattern as the partner top-5, unscoped from any one partner, `Sort.by("viewCount").descending()`, limit 100.
- **Top 10 partners by content count**: `GROUP BY created_by` on `contents`, order by count desc, limit 10, joined to `partner_profiles` for display name.
- **Top 10 partners by watch duration (and by views)**: `GROUP BY created_by SUM(watch_time_seconds)` (equivalently joined through the per-content running total), limit 10; same for views.
- **Overall platform aggregation**: sums across all of the above with no partner filter, feeding `AdminDashboardResponse`.
- Charts: same bar-chart component pattern as the partner dashboard, fed admin-scoped data, once admin dashboard work actually begins.

## Testing

- Backend: unit tests for the delta-clamp logic (zero/negative delta, delta within cap, delta exceeding cap, brand-new `WatchProgress` row) in `AnalyticsEventProcessor`/`PlaybackServiceImpl`; integration test that one `PROGRESS_TRACKED` event updates all three targets (`contents.watch_time_seconds`, hourly, daily) atomically, and that a forced failure on one write rolls back all three (this transactional-boundary property was the exact bug class the existing Javadoc on `AnalyticsEventProcessor` warns about).
- Frontend: no test framework — verify via `tsc`/lint plus manual run-through (poll interval firing, bar chart switching hour/day/week, top-5 list, per-content duration display).
