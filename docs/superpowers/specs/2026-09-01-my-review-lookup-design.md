# My Review Lookup — Design

**Date:** 2026-09-01
**Status:** Approved, ready for planning
**Repo:** `server` (api-service)
**Depends on:** none
**Blocks:** client-web Reviews integration spec

## Context

Surfaced while brainstorming client-web's Reviews spec: there's no way for
a client to ask "does the current user already have a review on this
content, and what's its status?" `ReviewController` only has `list`
(public, `APPROVED`-only), `create`, `update`, `delete` — none of which
answer that question directly.

This matters because of a real interaction in `ReviewServiceImpl.update()`:
editing a review resets its `status` to `PENDING`, and `list()` only
returns `APPROVED` reviews. So immediately after a user edits their review,
it silently disappears from the public list until re-approved. Without a
reliable source of truth, the client can't tell "you have a pending
edit awaiting approval" from "you haven't reviewed this yet" — and would
either show a confusing empty state or let the user attempt to create a
second review, which 409s (`existsByUserIdAndContentId`).

## Goal

Let the client ask, for the current user and a given content item, whether
a review exists and what its current status is — regardless of that
status (unlike the public `list` endpoint).

## Design

New endpoint on `ReviewController`:

```
GET /contents/{contentId}/reviews/mine
```

- Auth required (`@AuthenticationPrincipal`).
- 200 with the `ReviewResponse` if one exists (any status —
  `PENDING`/`APPROVED`/`REJECTED`), 404 if the user has never reviewed this
  content.
- New repository method `ReviewRepository.findByUserIdAndContentId(UUID, UUID)`
  — a trivial derived query, single `Optional<ContentReview>` (not paged;
  `existsByUserIdAndContentId` already proves this is unique per
  user+content via the `uq_review_user_content` constraint).
- New `ReviewService.getMine(UUID userId, UUID contentId)`.

## Non-goals

- No change to `list()`'s `APPROVED`-only filtering — that stays correct
  for the public-facing list. This is a separate, caller-scoped lookup.
