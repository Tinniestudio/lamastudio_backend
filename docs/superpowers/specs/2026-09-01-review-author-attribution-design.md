# Review Author Attribution — Design

**Date:** 2026-09-01
**Status:** Approved, ready for planning
**Repo:** `server` (api-service)
**Depends on:** none
**Blocks:** client-web Reviews integration spec

## Context

Surfaced while brainstorming client-web's Reviews spec: `ReviewResponse`
exposes only a raw `userId` for each review, with no way to display who
wrote it. A reviews list is unusable without at least a name — "Anonymous"
for every entry defeats the point of the feature.

## Goal

Show each review's author (display name + avatar) without exposing full
user profiles or raw user IDs publicly.

## Design

- `ReviewResponse` gains a nested `author: { displayName, avatarUrl }` —
  deliberately minimal (no id, no email, nothing else from `User`).
- `ReviewServiceImpl.list()` batch-fetches the `User` rows for the current
  page's reviews (`userRepository.findAllById(userIds)`, standard
  `JpaRepository` method, no new query needed) and maps each review's author
  from that batch — same pattern `PlaybackServiceImpl.getContinueWatching()`
  already uses for batch-resolving content/episode titles.
- `displayName` falls back to `firstName` (or a generic "Member" label) if
  null, matching whatever fallback convention the rest of the app already
  uses for a missing display name — check `UserProfileController`'s
  existing response mapping before introducing a new one.

## Non-goals

- No author info added to `create`/`update`/`delete` responses — those
  operations are always performed by the caller about themselves, so the
  caller already knows who they are. Only `list()` needs this.
- No exposure of `userId` itself — if a future feature needs it (e.g.
  linking to a public profile page), that's a separate decision.
