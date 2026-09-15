# Favorite Exists Lookup — Design

**Date:** 2026-09-14
**Status:** Approved, ready for planning
**Repo:** `server` (api-service)
**Depends on:** none
**Blocks:** client-web My List & History integration (the detail-page "Add to Watchlist" button)

## Context

Surfaced while planning client-web's My List & History integration: the content-detail pages' "Add to Watchlist" button (`ContentDetail.tsx` and `SeasonContentDetail.tsx`, both with an identical `TODO`) always initializes its `isInList` state to `false`, with no way to check whether the content is already favorited. `FavoriteServiceImpl.add()` throws `409 CONFLICT` ("Content is already in favorites") when the caller favorites something already in their list — so without a real initial-state check, re-clicking "Add to Watchlist" on an already-favorited item surfaces an error the button gave no indication was coming.

`FavoriteRepository.existsByUserIdAndContentId` already exists and is already used internally by `add()` — there's just no controller endpoint exposing that check for a single content item. The only way a client could answer "is this favorited" today is fetching the entire paginated favorites list and searching it client-side, which silently gives the wrong answer once a user has more favorites than fit on one page.

## Goal

Let a client ask, for the current user and a single content item, whether it's already favorited — without fetching the whole list.

## Design

New endpoint on `FavoriteController`:

```
GET /favorites/{contentId}/exists
```

- Auth required (`@AuthenticationPrincipal`), matching every other endpoint on this controller.
- Returns `{ "isFavorite": boolean }` — always 200, never 404 (unlike the reviews "mine" lookup, there's no distinct "not found" state to signal here; false is a completely normal, common answer, not an edge case).
- Backed directly by the existing `FavoriteRepository.existsByUserIdAndContentId(userId, contentId)` — no new repository method needed.

```java
public record FavoriteExistsResponse(boolean isFavorite) {}
```

```java
@GetMapping("/{contentId}/exists")
public ResponseEntity<FavoriteExistsResponse> exists(
        @AuthenticationPrincipal UserDetails principal,
        @PathVariable UUID contentId) {
    return ResponseEntity.ok(new FavoriteExistsResponse(
        favoriteRepo.existsByUserIdAndContentId(CurrentUser.id(principal), contentId)));
}
```

(Exact placement — directly on `FavoriteService`/`FavoriteServiceImpl` as a new method, or inline in the controller reading the repository directly — is an implementation-time call; every other read on this controller goes through `FavoriteService`, so add it there for consistency rather than reaching around the service layer from the controller.)

## Non-goals

- No change to `add()`'s existing 409-on-duplicate behavior — that stays as the authoritative guard; this endpoint is for the client to avoid *hitting* it in the common case, not a replacement for it (a race between this check and a concurrent add from another tab still correctly 409s, which is fine).
- No batch/bulk "check many content ids at once" variant — the only known caller today is a single detail page checking a single content id.
