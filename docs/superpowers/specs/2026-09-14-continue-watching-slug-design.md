# Continue Watching Content Slug — Design

**Date:** 2026-09-14
**Status:** Approved, ready for planning
**Repo:** `server` (api-service)
**Depends on:** none
**Blocks:** client-web `ContinueCard`/`StartClient.tsx` continue-watching deep links (currently non-functional)

## Context

Surfaced during client-web's My List & History integration: `ContinueWatchingItem` (`GET /playback/continue-watching`) has no way to build a working `/watch/{slug}` link. It carries `contentId` (a UUID) and `episodeId`, but the watch route needs the content's **slug**, not its id — confirmed as the same limitation already present on the already-merged Home page (`StartClient.tsx`), not something the My-List branch introduced. Every continue-watching card across the app is currently unable to deep-link correctly.

This is the same class of gap as the earlier Continue Watching thumbnail fix (`2026-09-01-continue-watching-thumbnail-design.md`) — `getContinueWatching()` already loads the `Content`/`Episode` entities needed to answer this, it just never carried the one additional field forward.

## Goal

Let a client build a working watch link directly from a `ContinueWatchingItem`, with no second lookup required.

## Design

Add `contentSlug` to `ContinueWatchingItem`, populated from the same `contentMap`/`episodeMap` entities `getContinueWatching()` already loads for title/thumbnail:

```java
public class ContinueWatchingItem {
    private final UUID contentId;
    private final UUID episodeId;
    private final String title;
    private final String thumbnailUrl;
    private final String contentSlug;
    private final int progressSeconds;
    private final int durationSeconds;
    private final BigDecimal completionPercentage;
    private final Instant lastWatchedAt;
}
```

In `getContinueWatching()`'s mapping loop:

```java
String contentSlug;
if (p.getEpisodeId() != null) {
    Episode ep = episodeMap.get(p.getEpisodeId());
    title = ep != null ? ep.getTitle() : "Unknown Episode";
    thumbnailUrl = ep != null ? ep.getThumbnailUrl() : null;
    contentSlug = ep != null ? ep.getSeason().getContent().getSlug() : null;
} else {
    Content c = contentMap.get(p.getContentId());
    title = c != null ? c.getTitle() : "Unknown Content";
    thumbnailUrl = c != null ? c.getThumbnailUrl() : null;
    contentSlug = c != null ? c.getSlug() : null;
}
```

For an episode item, the slug comes from the episode's parent content (`episode.getSeason().getContent()`) — matching how the watch route already works (`/watch/{contentSlug}?episode={episodeId}`, not a per-episode slug, since episodes don't have their own slugs in this data model).

## Non-goals

- No change to the response's existing fields or to `WatchProgress`/the underlying query — this is an additive field on an existing DTO.
- No eager-fetch/join optimization for `episode.getSeason().getContent()` — this traverses two lazy associations inside the method's existing `@Transactional(readOnly = true)` boundary (safe, no `LazyInitializationException` risk), and the continue-watching list is capped at 20 items, so the worst case is a small, bounded number of extra queries, not a real scalability concern worth added complexity to avoid.
