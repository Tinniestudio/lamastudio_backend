# Trailer Playback Manifest — Design

**Date:** 2026-09-01
**Status:** Approved, ready for planning
**Repo:** `server` (api-service)
**Depends on:** none (independent of [Dynamic Content Type Management](2026-09-01-dynamic-content-type-design.md))
**Blocks:** client-web Content Model & Browse Integration spec (hero autoplay trailer)

## Context

client-web's home page (`/start`) hero section autoplays a trailer for a
featured piece of content. Surfaced while brainstorming that spec: neither
`ContentSummaryResponse` nor `ContentResponse` exposes a trailer URL, and
`PlaybackController` only serves the `MAIN_VIDEO` asset's manifest
(`/manifest/content/{id}`, `/manifest/episode/{id}`). `VideoAssetType.TRAILER`
already exists in the data model and partner upload flow — nothing currently
serves it to viewers.

Checking `PlaybackServiceImpl` surfaced a real correctness constraint for
this addition: `checkAccess()` — used by both existing manifest endpoints —
denies anyone without an `ACTIVE` subscription. Trailers are promotional and
must stay reachable by anonymous/free visitors, so the new endpoint must not
route through that check.

## Goal

Serve the active `TRAILER` video asset's manifest for a content item,
publicly, so the client can autoplay it without requiring auth or a
subscription.

## Design

New endpoint on `PlaybackController`:

```
GET /playback/manifest/content/{contentId}/trailer
```

- No `@AuthenticationPrincipal` requirement, no `checkAccess()` call — this
  is the one manifest endpoint that's intentionally public. It should still
  404 if `contentId` doesn't exist or isn't `PUBLISHED`, to avoid leaking
  unpublished content via a different path.
- Looks up the video asset via the **existing**
  `VideoAssetRepository.findByContent_IdAndAssetTypeAndIsActiveTrue(contentId, assetType)`
  method, called with `VideoAssetType.TRAILER` instead of `MAIN_VIDEO` — no
  new repository query needed.
- 404 (`"No trailer available"`) if the content has no active trailer asset.
  This is an expected, common case (not every piece of content will have a
  trailer) — the client is expected to fall back to a static poster/thumbnail
  when this 404s, not treat it as an error state.
- Reuses `PlaybackManifestResponse` (`manifestUrl`, `subtitles`, `resumeAt`,
  `duration`) via the existing `buildManifestResponse()` helper, with
  `resumeAt` always `null` — trailers don't have watch progress.
- No view-count/analytics publish on this endpoint — trailer previews aren't
  a "watch," and content view-count is already tracked via
  `POST /contents/id/{id}/view` and the main manifest endpoints.

## Non-goals

- No episode-trailer variant (`/manifest/episode/{id}/trailer`) — trailers
  are a content-level concept in the current upload model (`VideoAsset.content`
  is set for trailers, not `VideoAsset.episode`); revisit only if that
  changes.
- No admin/partner-facing changes — trailer upload already exists via the
  existing `UploadType.TRAILER` flow.
