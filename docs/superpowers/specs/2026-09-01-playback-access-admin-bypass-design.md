# Playback Access Admin Bypass — Design

**Date:** 2026-09-01
**Status:** Approved, ready for planning
**Repo:** `server` (api-service)
**Depends on:** none
**Blocks:** client-web Watch & Playback integration spec

## Context

Surfaced while brainstorming client-web's Watch & Playback integration: the
current (mocked) watch page has a `TODO` admin-bypass branch that was never
implemented (`isAdmin = false`, hardcoded). Checking the real backend showed
this isn't just a frontend gap — `PlaybackServiceImpl.checkAccess()` has no
admin bypass either. It only checks `ContentStatus.PUBLISHED` and an active
subscription; an admin with no personal subscription cannot preview content
at all. This blocks routine QA/ops work (verifying a newly published title
actually plays) with no workaround.

## Goal

Let admins bypass the subscription requirement in playback access checks,
without weakening the publish-status check (an admin still can't play
DRAFT/REVIEW/unpublished content through the public playback path — that's
what the admin-only content endpoints are for).

## Design

`CurrentUser.isAdmin(UserDetails)` already exists and is used elsewhere for
exactly this kind of in-service role check — this is a wiring change, not a
new capability.

- `PlaybackService.checkAccess(UUID userId, UUID contentId)` becomes
  `checkAccess(UserDetails principal, UUID contentId)`. The three call sites
  (`PlaybackController.checkAccess`, and internally within
  `getContentManifest`/`getEpisodeManifest`) already have `principal`
  available — they currently extract `CurrentUser.id(principal)` before
  calling; they pass `principal` through instead.
- Inside `checkAccess`: unchanged `ContentStatus.PUBLISHED` check first. Then:
  `if (CurrentUser.isAdmin(principal)) return AccessCheckResponse.granted();`
  before the subscription lookup. Everyone else falls through to the
  existing subscription check, unchanged.

## Non-goals

- No `PARTNER`-role bypass — partners already have their own content
  management/preview surface (`PartnerVideoController` etc.); this is
  specifically about admin QA access through the same path regular viewers
  use.
- No change to the publish-status gate — admins previewing unpublished
  content use the existing admin content endpoints, not this public
  playback path.
