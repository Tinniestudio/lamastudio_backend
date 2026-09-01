# Continue Watching Thumbnail Enrichment — Design

**Date:** 2026-09-01
**Status:** Approved, ready for planning
**Repo:** `server` (api-service)
**Depends on:** none
**Blocks:** client-web My List & History integration spec

## Context

Surfaced while brainstorming client-web's My List & History spec:
`PlaybackServiceImpl.getContinueWatching()` hardcodes `thumbnailUrl: null`
for every item, with the comment "enriched in Batch 12" — that enrichment
was never done. Every Continue Watching card (on Home and on the My List
page) needs a thumbnail image to render meaningfully; without this, the
client would have to fall back to a placeholder for every single item.

## Goal

Populate `ContinueWatchingItem.thumbnailUrl` from data already loaded in the
same method — this is not a new query, just using a field that's already
being fetched and discarded.

## Design

`getContinueWatching()` already loads `contentMap`/`episodeMap` via
`contentRepo.findAllById(contentIds)` / `episodeRepo.findAllById(episodeIds)`
to resolve each item's `title`. Both `Content` and `Episode` already have a
`thumbnailUrl` field. Same branch that resolves `title`, resolve
`thumbnailUrl`:

```java
String thumbnailUrl = p.getEpisodeId() != null
    ? Optional.ofNullable(episodeMap.get(p.getEpisodeId())).map(Episode::getThumbnailUrl).orElse(null)
    : Optional.ofNullable(contentMap.get(p.getContentId())).map(Content::getThumbnailUrl).orElse(null);
```

Passed into `ContinueWatchingItem` instead of the hardcoded `null`. No new
repository query, no migration, no DTO shape change (the field already
exists on `ContinueWatchingItem`, just always `null` today).

## Non-goals

- No fallback chain (e.g. episode → season → content poster) — if an
  episode has no thumbnail of its own, the client shows its placeholder,
  same as it already does for a missing `thumbnailUrl` anywhere else.
