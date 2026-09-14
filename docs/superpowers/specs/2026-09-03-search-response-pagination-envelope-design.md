# Search Response Pagination Envelope — Design

**Date:** 2026-09-03
**Status:** Approved, ready for planning
**Repo:** `server` (api-service)
**Depends on:** none
**Blocks:** client-web's `feat/search-integration` branch needs a small follow-up once this ships (see Downstream below)

## Context

Surfaced during client-web's search integration: `GET /search` returns a hand-built `SearchResponse` record (`{results, total, page, limit, totalPages}`) instead of a raw `Page<ContentSummaryResponse>`. Every other list endpoint in this API (`GET /contents`, `GET /favorites`, `GET /history`, `GET /contents/{id}/reviews`, etc.) returns `ResponseEntity<Page<T>>`, which `SuccessResponseWrapper` automatically unwraps into the standard `{data: page.getContent(), meta: {total, page, size, totalPages}}` envelope — `SuccessResponseWrapper` only special-cases `instanceof Page<?>`, so `SearchResponse` falls through to the generic branch and gets wrapped as-is (`data: <the whole SearchResponse object>`, no `meta`).

This inconsistency isn't just cosmetic — it already caused a real, silent bug: a client-web implementation plan assumed `/search` followed the standard shape (since every other list endpoint does), shipped code reading `.data`/`.meta.total`, and would have shown "0 results" for every search until caught in review. The underlying data itself is already properly paginated (`SearchRequest.limit` is capped at `@Max(50)`, default 20) — this isn't about an unbounded/expensive query, it's about the response shape not matching the one convention every other client-facing list follows, which is exactly the kind of mismatch that causes this class of bug to keep recurring for anyone who builds against this endpoint without re-reading its one-off shape first.

## Goal

Make `GET /search` return `ResponseEntity<Page<ContentSummaryResponse>>`, identical in shape to every other paginated list endpoint, so `SuccessResponseWrapper`'s existing unwrapping handles it automatically and no client needs to special-case search's response format.

## Design

- `SearchService.search(SearchRequest)`'s return type changes from `SearchResponse` to `Page<ContentSummaryResponse>`.
- `SearchServiceImpl.search()`'s body simplifies — it already computes a real `Page<Content>` from the repository; instead of manually flattening it into a custom record, just map and return it:

```java
Page<Content> page = switch (request.getSort()) {
    case LATEST  -> contentRepository.searchByLatest(q, typeStr, language, country, categorySlug, pageable);
    case POPULAR -> contentRepository.searchByPopular(q, typeStr, language, country, categorySlug, pageable);
    default      -> contentRepository.searchByRelevance(q, typeStr, language, country, categorySlug, pageable);
};

return page.map(ContentSummaryResponse::from);
```

- `SearchController.search()`'s return type changes to `ResponseEntity<Page<ContentSummaryResponse>>`.
- `SearchResponse` (the DTO record) is deleted — nothing else in the codebase constructs or consumes it once this lands.
- The `@Cacheable` annotation and its key expression are unchanged — Spring's cache abstraction caches whatever the method returns regardless of type, and the key already depends on every input that affects the result (query, filters, sort, page, limit).

## Non-goals

- No change to `SearchRequest`'s fields, validation, or the underlying `searchByLatest`/`searchByPopular`/`searchByRelevance` native queries — this is purely a return-shape change at the boundary.
- No change to the `@Max(50)` page-size cap — it already exists and already bounds the query; this spec doesn't newly introduce pagination, it makes the existing pagination's response shape consistent with the rest of the API.

## Downstream: client-web follow-up required

`feat/search-integration` (client-web, not yet merged into `dev`) was just fixed to read the *current* one-off shape (`searchResult.data.results` / `searchResult.data.total` / `searchResult.data.totalPages`, via the `SearchResponse` type in `src/types/search.type.ts`). Once this backend spec ships, that branch needs a small follow-up: read `searchResult.data` (the results array directly) and `searchResult.meta.total`/`searchResult.meta.totalPages` instead — the exact same pattern `MovieClient.tsx`/`bffFetchWithMeta` already use for `/contents`. This should happen before merging `feat/search-integration`, not after, to avoid shipping the one-off shape at all.
