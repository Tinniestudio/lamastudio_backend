# Trailer Resilience — Season/Episode Manifests, 500 Fix, Player Fallback — Design

**Date:** 2026-10-02
**Status:** Approved, ready for planning
**Repos:** `server` (api-service), `tinniestudio-client-web`, `tinniestudio-partner-web`
**Supersedes (partially):** [Trailer Playback Manifest](2026-09-01-trailer-playback-manifest-design.md) — that spec's "no episode-trailer variant" non-goal no longer holds; the data model has since grown a `Season.trailers` relation and partner-web already uploads episode-level trailers (`EpisodeVideoCard`), so this spec extends trailer manifests to season and episode level.

## Context

`tinniestudio-client-web/CLAUDE.md` listed a cluster of related trailer problems:

1. `GET /playback/manifest/content/{contentId}/trailer` intermittently returns **500**
   instead of a clean 404/manifest.
2. Seasons and episodes have no trailer manifest endpoint at all, even though
   the upload pipeline already supports uploading trailers at those levels.
3. `HLSPlayer`/the detail pages block the entire hero section (video, action
   buttons, CTAs) whenever a trailer is loading, missing, or fails — instead
   of degrading gracefully to a thumbnail while staying interactive.
4. Partner-web has no season-level trailer upload UI, and series don't get an
   "overall" trailer slot at the content level.

This spec covers all four, scoped to the trailer pipeline only (the client-web
404 page and "improve sermons and shows" tasks are tracked in a separate spec).

## 1. Backend — Fix the 500

### Root cause

`VideoAsset.content` is denormalized onto every asset regardless of its most
specific target (see `VideoAssetRepository`'s javadoc on
`deactivateOtherAssets*`) — a season-scoped or episode-scoped trailer still
carries the parent content's id. `PlaybackServiceImpl.getTrailerManifest`
(`api-service/src/main/java/com/tinniestudio/api/modules/playback/service/PlaybackServiceImpl.java:121-131`)
calls:

```java
videoAssetRepo.findByContent_IdAndAssetTypeAndIsActiveTrue(contentId, VideoAssetType.TRAILER)
```

This Spring Data derived query matches on `content_id` alone — it does **not**
check that `season` and `episode` are null. If a content-level trailer and a
season-level trailer are both `isActive=true` and share the same `content_id`,
this query matches two rows. Because the repository method returns
`Optional<VideoAsset>` (single-result contract), Spring Data throws
`IncorrectResultSizeDataAccessException`, which `GlobalExceptionHandler` has no
specific mapping for — it falls through to a generic 500.

### Fix

**Scope every trailer lookup query by level**, so a season/episode trailer
can never be mistaken for a content-level one:

- Add to `VideoAssetRepository`:

  ```java
  Optional<VideoAsset> findByContent_IdAndSeasonIsNullAndEpisodeIsNullAndAssetTypeAndIsActiveTrue(
      UUID contentId, VideoAssetType assetType);

  Optional<VideoAsset> findBySeason_IdAndAssetTypeAndIsActiveTrue(
      UUID seasonId, VideoAssetType assetType);
  ```

  (`findByEpisode_IdAndAssetTypeAndIsActiveTrue` already exists and is already
  correctly scoped — episode is always the most specific level, so no
  cross-level collision is possible there.)

- `getTrailerManifest(contentId)` switches to the new
  `findByContent_IdAndSeasonIsNullAndEpisodeIsNullAndAssetTypeAndIsActiveTrue`.
- No change to `VideoActivationService.activateAndRetireSiblings` — a content
  trailer, a season trailer, and an episode trailer are each independently
  meaningful and allowed to be active simultaneously. The bug was purely in
  the read-side query conflating levels, not in activation.
- **Defense in depth:** add a catch for `IncorrectResultSizeDataAccessException`
  in the three trailer manifest methods, logging a warning and returning the
  same "no trailer available" 404 rather than letting any future data drift
  surface as a 500.

## 2. Backend — Season & Episode Trailer Manifest Endpoints

Add two endpoints to `PlaybackController`
(`api-service/src/main/java/com/tinniestudio/api/modules/playback/controller/PlaybackController.java`),
mirroring the existing content trailer endpoint (public, no auth, no
`checkAccess()`):

```java
@GetMapping("/manifest/season/{seasonId}/trailer")
public ResponseEntity<PlaybackManifestResponse> getSeasonTrailerManifest(@PathVariable UUID seasonId)

@GetMapping("/manifest/episode/{episodeId}/trailer")
public ResponseEntity<PlaybackManifestResponse> getEpisodeTrailerManifest(@PathVariable UUID episodeId)
```

Corresponding `PlaybackServiceImpl` methods:

- `getSeasonTrailerManifest(seasonId)`: load the `Season`, verify its parent
  `Content.status == PUBLISHED` (404 otherwise, same leak-prevention rule the
  content trailer endpoint already applies), then
  `findBySeason_IdAndAssetTypeAndIsActiveTrue(seasonId, TRAILER)` → 404 "No
  trailer available" if absent, else `buildManifestResponse(asset, null)`.
- `getEpisodeTrailerManifest(episodeId)`: load the `Episode`, resolve
  `episode.getSeason().getContent()` for the same PUBLISHED check, then
  `findByEpisode_IdAndAssetTypeAndIsActiveTrue(episodeId, TRAILER)` → same 404
  / response pattern.

Both reuse `buildManifestResponse()` exactly as the content trailer endpoint
does — no new DTO. No view-count/analytics publish (trailers aren't a
"watch," consistent with the existing content trailer endpoint's behavior).

No server-side fallback across levels (e.g. season endpoint does not
internally fall back to the content trailer) — the fallback chain is resolved
client-side (see Section 4), keeping each endpoint single-purpose.

## 3. Client-web — `useTrailerManifest` Hook

Replace `useHeroTrailer` (`src/hooks/useHeroTrailer.ts`) with a generalized
hook:

```ts
function useTrailerManifest(ids: {
  episodeId?: string;
  seasonId?: string;
  contentId: string;
}): {
  status: "loading" | "ready" | "unavailable";
  manifestUrl: string | null;
  asset: PlayerAsset | null;
};
```

**Resolution order:** try whichever ids are present, most specific first —
episode → season → content — stopping at the first 200 response. A 404 at any
level falls through to the next (same "missing trailer is expected, not an
error" semantics `useHeroTrailer` already has). Any other error (network
failure, unexpected non-404 status) is treated the same as a miss for UI
purposes — fall through / end at `unavailable` — but logged via the existing
console/error-tracking path for debugging, never surfaced to the viewer.

**Callers**, all three pointing at the same hook:

- `ContentDetail.tsx`: `useTrailerManifest({ contentId: data.id })`
- `SeasonContentDetail.tsx`: currently calls `useHeroTrailer(data.id)`
  unconditionally (`src/components/content/SeasonContentDetail.tsx:76`) — this
  means today's "hero trailer" on an episode page is actually always the
  _content_-level trailer, never the season's or episode's own. The component
  already tracks `activeSeason`/`activeEpisode` state
  (`SeasonContentDetail.tsx:62-67`) for season-picker/episode-picker UI, so the
  fix is to pass those same ids through:
  `useTrailerManifest({ episodeId: activeEpisode?.id, seasonId: activeSeason?.id, contentId: data.id })`.
  No separate "episode detail" component exists — `SeasonContentDetail` is
  reused for series, season, and episode pages via `initialSeason`/
  `initialEpisode` props, so this one change covers all three levels,
  including making the hero trailer reactively follow episode selection
  (`handleEpisodeSelect`) which it does not do today.

## 4. Client-web — `HLSPlayer` Resilience

### Current gap

`HLSPlayer` (`src/components/content/HLSPlayer.tsx`) requires a non-nullable
`asset: PlayerAsset` prop (line 43) and bakes the trailer-mode action strip
(My List button, Watch CTA, title, badges — lines 307-363) _inside_ its own
render tree. Both `SeasonContentDetail.tsx` and `ContentDetail.tsx` only
mount `<HLSPlayer>` at all when `heroAsset` is truthy (`SeasonContentDetail.tsx:105`);
on loading or error/missing they render an unrelated placeholder `<div>`
(`SeasonContentDetail.tsx:99-128`) with no action buttons at all. The
metadata section below (synopsis, cast, etc.) is unaffected either way — the
actual blocked UI is specifically the My List / Watch CTA strip that lives
inside `HLSPlayer`.

### Fix

1. **Widen `HLSPlayer`'s `asset` prop to `PlayerAsset | null`.** When `asset`
   is `null`, skip the HLS.js/`<video>` setup entirely and render
   `thumbnailUrl` as the background — but still render the full bottom action
   strip (badges, title, My List, Watch CTA) unconditionally in trailer mode,
   since none of that markup actually depends on `asset` today except reading
   `asset.durationSeconds` for the badge text (guard with
   `asset?.durationSeconds`).
2. **Loading state:** thumbnail renders immediately as the background (no
   spinner, no blocking); the `<video>`/HLS pipeline only attaches once
   `asset` transitions from `null` to populated, then cross-fades in once
   `MANIFEST_PARSED`/`loadedmetadata` fires — matching the existing `onReady`
   callback, just gated on `asset` being present rather than assumed.
3. **Error state (fatal HLS.js error, at load or mid-playback):** on
   `Hls.Events.ERROR` with `data.fatal` true, `hls.destroy()` and revert to
   the thumbnail background — same visual end state as "no asset," no retry,
   no visible error message to the viewer (silent fallback). Today's handler
   (`HLSPlayer.tsx:143`, `hls.on(Hls.Events.ERROR, () => setIsLoading(false))`)
   doesn't check `data.fatal` or distinguish recoverable from fatal errors;
   this becomes the trigger for the thumbnail-fallback transition.
4. **Caller simplification:** `SeasonContentDetail.tsx` and `ContentDetail.tsx`
   collapse their three-way branch (loading spinner / `HLSPlayer` / error
   placeholder, lines 99-129) into a single unconditional
   `<HLSPlayer mode='trailer' asset={heroAsset} ... />` call, passing
   `useTrailerManifest`'s `asset` straight through (`null` is now a valid,
   handled value). The separate error-message placeholder block is removed.

## 5. Thumbnail Source

The `thumbnailUrl` passed to `HLSPlayer` already comes from the caller
(`playerThumbnail = data.thumbnailUrl` in both detail components) — this
stays level-matched by construction once Section 3's hook change is in place:
`SeasonContentDetail` already has `activeEpisode`/`activeSeason`/`data`
available, so it passes whichever level's own backdrop/thumbnail field
corresponds to what's currently selected, rather than always the top-level
content thumbnail.

## 6. Partner-web — Season Trailer Upload & Series Content-Level Trailer

### New `SeasonVideoCard` component

Mirrors `EpisodeVideoCard`/`ContentVideoCard`
(`src/features/episodes/components/EpisodeVideoCard.tsx`), but trailer-only —
a season itself has no "main video" concept, only episodes do:

```tsx
export function SeasonVideoCard({ season }: { season: SeasonResponse }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Video</CardTitle>
      </CardHeader>
      <CardContent>
        <VideoUploadField
          uploadType="TRAILER"
          targetEntityType="SEASON"
          targetEntityId={season.id}
          label="Trailer"
        />
        <VideoHistoryList
          targetEntityType="SEASON"
          targetEntityId={season.id}
          assetType="TRAILER"
        />
      </CardContent>
    </Card>
  );
}
```

`TargetEntityType` already includes `"SEASON"` (`src/types/upload.type.ts:8`)
and the backend upload pipeline already accepts it — this is UI-only, no new
upload API. Rendered in `SeasonDetailPageClient.tsx` right after `<SeasonForm>`
(`src/features/seasons/components/SeasonDetailPageClient.tsx:36`), conditional
on `season` being loaded (edit mode only, same as `ContentVideoCard`'s
`isEdit && content` gate).

### Re-enable `ContentVideoCard` for series — trailer-only

`ContentForm.tsx:126` currently gates `ContentVideoCard` on `!isSeries`. Series
need an "overall" content-level trailer (alongside per-season
`SeriesSeasonsCard` and per-episode `EpisodeVideoCard` trailers), but **not**
a content-level main video — series are watched via episodes, so a "Main
video" upload at the content level has no playback path that would ever serve
it (`PlaybackController` has no "main video manifest for a series content id"
caller). Rather than rendering the unused section, give `ContentVideoCard` a
`trailerOnly` flag:

```diff
- export function ContentVideoCard({ content }: { content: PartnerContentResponse }) {
+ export function ContentVideoCard({ content, trailerOnly = false }: { content: PartnerContentResponse; trailerOnly?: boolean }) {
    const [activeMainVideo, setActiveMainVideo] = useState<PartnerVideoAssetResponse | null>(null);

    return (
      <Card>
        <CardHeader><CardTitle>Video</CardTitle></CardHeader>
        <CardContent className="space-y-tls-lg">
+         {!trailerOnly && (
            <div className="space-y-tls-xs">
              <p className="mb-tls-xs text-sm font-medium text-tls-neutral">Main video</p>
              <VideoUploadField uploadType="RAW_VIDEO" targetEntityType="CONTENT" targetEntityId={content.id} label="Main video" />
              <VideoHistoryList targetEntityType="CONTENT" targetEntityId={content.id} assetType="MAIN_VIDEO" onActiveVideoChange={setActiveMainVideo} />
            </div>
+         )}
          <div className="space-y-tls-xs">
            <p className="mb-tls-xs text-sm font-medium text-tls-neutral">Trailer</p>
            <VideoUploadField uploadType="TRAILER" targetEntityType="CONTENT" targetEntityId={content.id} label="Trailer" />
            <VideoHistoryList targetEntityType="CONTENT" targetEntityId={content.id} assetType="TRAILER" />
          </div>
-         <SubtitlesSection activeVideo={activeMainVideo} />
+         {!trailerOnly && <SubtitlesSection activeVideo={activeMainVideo} />}
        </CardContent>
      </Card>
    );
  }
```

(Subtitles are only meaningful against a main video asset, so they're gated
alongside it.) `ContentForm.tsx` then renders it unconditionally, trailer-only
for series:

```diff
- {isEdit && content && !isSeries && <ContentVideoCard content={content} />}
+ {isEdit && content && <ContentVideoCard content={content} trailerOnly={isSeries} />}
  {isEdit && content && isSeries && <SeriesSeasonsCard contentId={content.id} seasons={seasons ?? []} />}
```

Update the stale comment at `ContentVideoCard.tsx:11-14` (which explained the
now-removed full exclusion for series) to describe the new `trailerOnly`
behavior instead.

## 7. Testing & Verification

**Backend:**

- Unit test: the new scoped queries don't cross-match (a season-level active
  trailer isn't returned by the content-level query and vice versa) —
  regression test directly covering the 500.
- Integration test: activate a content-level trailer and a season-level
  trailer on the same content; hit both manifest endpoints; each returns its
  own trailer, no exception.
- Integration test: season/episode trailer endpoints return 404 (not 500)
  when no active trailer exists at that level, and 404 when the parent
  content isn't `PUBLISHED`.

**Client-web:**

- `useTrailerManifest`: unit tests for fallback order (episode 404 → season
  200 returns season; all 404 → `unavailable`; a non-404 error is treated as
  a fallthrough, not surfaced).
- `HLSPlayer`: tests for `asset=null` rendering the thumbnail + full action
  strip; a simulated fatal HLS.js error reverting to the thumbnail without
  unmounting the action strip.
- Manual browser check: a season with no trailer falls back to the series
  trailer; a season with neither shows the thumbnail with clickable My
  List/Watch CTA buttons; selecting a different episode updates the hero
  trailer.

**Partner-web:**

- Manual check: upload a season trailer via `SeasonVideoCard`, confirm it's
  playable via the new season manifest endpoint; confirm `ContentVideoCard`
  now appears for series and its uploads route with
  `targetEntityType=CONTENT`.

## Non-goals

- No change to `getContentManifest`/`getEpisodeManifest` (MAIN_VIDEO) query
  scoping — the same latent cross-level ambiguity technically exists there,
  but no duplicate-active-row scenario has been observed in practice for
  MAIN_VIDEO (a season has no main video concept to collide with), and fixing
  it is out of scope here.
- No server-side cross-level trailer fallback (covered in Section 2) — client
  owns the fallback chain.
- The client-web global 404 page and "improve sermons and shows" tasks are
  tracked in a separate spec.
