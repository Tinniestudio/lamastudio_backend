# Partner Content Upload UX — Media Preview — Design

## Scope

The original backlog item had three parts:

1. Media preview on the partner page, so partners can preview their video before
   submitting it, to avoid submitting the wrong content.
2. Content upload format guidance.
3. Showing accepted upload formats on every upload UI.

Items 2 and 3 are already fully covered by
`2026-10-18-movie-upload-failure-and-format-guidance-design.md` (the
`UploadFormatHint` info panel + client-side duration pre-check with autofill). This
spec covers only item 1.

## Current-state findings

- **No "publish" action exists in partner-web.** The only partner-initiated status
  change is "Submit for review" (`ContentForm.tsx`'s `handleResubmit`, calling
  `useSubmitContent()` → `POST /partners/contents/{id}/submit`), moving status
  `DRAFT`/`REJECTED` → `REVIEW`. Actual publishing happens on the admin side, outside
  this repo. So "preview before publish" in practice means preview before submitting
  for review — that's the point at which a partner could currently commit to the
  wrong file without realizing it.
- **No video playback capability exists in partner-web at all** — zero `<video>`,
  hls.js, or iframe usage anywhere in the codebase. `ContentVideoCard`,
  `SeasonVideoCard`, and `VideoHistoryList` only render upload UI, a status badge,
  and "Set as main"/"Cancel"/"Delete" actions.
- **No manifest URL is exposed to partner-web today.** `PartnerVideoAssetResponse`
  only has `processingStatus`/`isActive` — no URL field.
- **The playback backend gate requires `PUBLISHED` content.** `PlaybackController`'s
  manifest endpoints route through `PlaybackServiceImpl`, which rejects anything not
  `ContentStatus.PUBLISHED`. Draft/in-review content has no existing route that
  returns a manifest at all.
- **`processed/main/*` is under CloudFront edge-level signing, not just app-layer
  auth.** `CloudFrontSigningService` confirms the bucket sits behind CloudFront with
  Origin Access Control (private bucket) and a trusted-key-pair signing requirement
  scoped to `processed/main/*`. This is enforced by CloudFront itself at the edge —
  an unsigned request to that path is rejected with a 403 regardless of which backend
  app decided to authorize it. `processed/trailers/*` is deliberately excluded from
  this (trailers must stay reachable with no cookie, per
  `VideoProcessingService`'s `assetPrefix` comment), so it has no such restriction.

This last point rules out a naive "just add a partner-ownership-checked endpoint and
return the plain CDN URL" approach for main-video previews — CloudFront would still
reject the unsigned request. It works fine for trailers, which aren't signed at all.

## Design

### Backend

New endpoint: `GET /partners/video-assets/{id}/preview-manifest`. Authorization
reuses the same partner-owns-this-content check already applied to the existing
cancel/delete video-asset endpoints. Returns a result only once
`processingStatus == READY`.

- **`assetType == TRAILER`:** returns the plain CDN URL directly —
  `processed/trailers/*` has no edge-level restriction to work around.
- **`assetType == RAW_VIDEO`:** generates a short-lived (10 minute) CloudFront
  **signed URL** (not signed cookies — those are scoped to client-web's cookie
  domain) for the specific manifest, using the same trusted key pair
  `CloudFrontSigningService` already holds. This is the one case where the ownership
  check alone isn't sufficient — the returned URL itself must carry the signature
  CloudFront requires at the edge.

No permanent `manifestUrl` field is added to `PartnerVideoAssetResponse` or any other
list/detail response — the URL is only ever produced by this one on-demand,
short-lived call, never embedded in regular asset data.

### Partner-web UI

A "Preview" button appears on `ContentVideoCard` and `SeasonVideoCard` once
`processingStatus == READY`, opening a modal dialog containing a new, minimal video
player component. Partner-web has no playback capability today, so this requires
adding `hls.js` as a new dependency (already used the same way in
`tinniestudio-client-web`'s `HLSPlayer`). Unlike that player, this one needs no
autoplay-muted handling, subtitles, quality menu, or episode navigation — it's
play/pause/seek/volume only, existing solely to let the partner confirm it's the
right file before submitting for review. On modal open, it calls
`/preview-manifest` to get the URL, then loads it into hls.js the same way
client-web does.

## Testing

**Backend:** unit test for the new endpoint covering ownership rejection (a different
partner's content), the `READY`-only gate, and that `RAW_VIDEO` responses carry a
validly-signed URL while `TRAILER` responses don't need one.

**Frontend:** no test framework exists in `tinniestudio-partner-web` — verification
is type-check + lint + manual walkthrough: opening the preview modal for both a main
video and a trailer, confirming playback works, and confirming the "Preview" button
doesn't appear before `processingStatus` reaches `READY`.
