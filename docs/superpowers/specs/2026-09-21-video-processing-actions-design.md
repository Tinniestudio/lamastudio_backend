# Video Processing Actions (Cancel/Delete) + Upload UX — Design

**Date:** 2026-09-21
**Status:** Approved, ready for planning
**Repos:** `server` (api-service + media-worker), `tinniestudio-partner-web`
**Depends on:** none
**Blocks:** partner-web's upload-UX fixes (needs the two new endpoints + `CANCELLED` status to exist first)

## Context

Partner-web's video upload flow polls aggressively during processing — `useUploadStatus` every 3s, `useListPartnerVideos` every 5s (`src/features/uploads/hook/query.ts`, `src/features/videos/hook/query.ts`) — for the entire duration of processing, which can exceed 15 minutes. `VideoUploadField.tsx`'s "Processing…" state gives no indication of expected duration or that the tab can be safely closed. `PartnerVideoController` (`server/api-service`) only supports `list` and `activate` — there's no way for a partner to cancel an in-flight upload they regret, or delete a finished/failed one.

**Investigated and confirmed during planning**: partner-web already has a fully-built notification system (bell, dropdown, preferences — `src/features/notifications/`), and the backend already fires a real `CONTENT_PROCESSED` notification event on **both** success and failure (`NotificationConsumer.java`, triggered from `UploadService.java`'s upload-completion linking). The "let the user know when it's done" problem is largely already solved by infrastructure that exists but isn't referenced anywhere in the upload UI's copy.

The processing pipeline itself (`media-worker`'s `VideoProcessingService.process()`) is a synchronous, multi-stage loop (download → transcode each resolution → upload) with no existing cancellation awareness — confirmed by reading the method directly. True mid-ffmpeg-process termination is out of scope (see Non-goals); cancellation is best-effort, stopping before the next stage rather than killing an in-flight transcode.

## Goal

Give partners the ability to cancel an in-flight video processing job and delete a finished/failed one, replace aggressive polling with on-demand checking (leaning on the existing notification system for the "it's done" signal), and give the upload UI honest messaging about expected duration.

## Design

### 1. `ProcessingStatus` gains `CANCELLED`

`DomainEnums.ProcessingStatus` (currently `PENDING | PROCESSING | READY | FAILED`) gains a fifth value, `CANCELLED`. This enum is duplicated between `api-service` and `media-worker` (confirmed — `media-worker` has its own copy, not a shared module) — both need the addition.

### 2. Two new endpoints on `PartnerVideoController`

- **`POST /partners/videos/{id}/cancel`** — allowed only while the asset's `processingStatus` is `PENDING` or `PROCESSING` (400 otherwise). Ownership-checked identically to the existing `activate()` (`asset.getUploadedBy()` match, 404-not-403 on mismatch — matches that method's existing IDOR-guard precedent). Sets `processingStatus = CANCELLED` immediately, so the UI reflects it instantly regardless of what the worker is doing.
- **`DELETE /partners/videos/{id}`** — allowed only in a terminal state (`READY`, `FAILED`, or the new `CANCELLED`); 400 while `PENDING`/`PROCESSING` (a partner must cancel first). Same ownership check. Deletes the `VideoAsset` row and its storage object(s). If the deleted asset was the active one for its target, no replacement is auto-selected — the target simply has no active video until the partner activates a different `READY` sibling, identical to the existing "nothing activated yet" state new content already has.

**No new status-lookup endpoint** — both existing mechanisms partner-web already polls (`GET /uploads/{sessionId}/status`, `GET /partners/videos?...`) already return full current status; only their client-side auto-poll behavior changes (see §4).

### 3. Worker: best-effort cancellation

`VideoProcessingService.process()` gains a cancellation check at each existing stage boundary — before starting the next resolution's transcode, and before the final upload stage. Each check re-reads the `VideoAsset`'s `processingStatus`; if it's `CANCELLED` (set synchronously by the new endpoint above, independent of the worker's own progress), the method stops, cleans up its temp job directory, and returns without transcoding further resolutions or uploading — without touching `processingStatus` again (it's already `CANCELLED`, set by the API layer, not the worker). A resolution already mid-transcode when cancellation is requested still finishes that one shell-out; only the *next* stage is skipped. This bounds wasted compute without requiring OS-process-level interruption.

### 4. Partner-web: on-demand instead of auto-polling

- `useUploadStatus` (`src/features/uploads/hook/query.ts`) and `useListPartnerVideos` (`src/features/videos/hook/query.ts`) both lose their `refetchInterval` entirely. Zero background requests while a video is processing.
- `VideoUploadField.tsx`'s `"processing"` phase copy changes to explain the real timeline and reference the notification system: *"Processing your video — this can take 15+ minutes. We'll notify you here when it's ready, so feel free to close this tab and come back later."* A "Check now" button calls the existing query's `refetch()` for an on-demand look, replacing the removed automatic interval.
- `VideoHistoryList.tsx` gains, per row: a "Cancel" button (visible while `PENDING`/`PROCESSING`, calls the new cancel endpoint) and a "Delete" button (visible only in a terminal state, calls the new delete endpoint, with a confirmation step since it's destructive) — both new mutation hooks following the existing `useActivateVideo` pattern (`mutate()` + query invalidation on success, no optimistic update).

## Non-goals

- **No true process-kill cancellation** — the worker never terminates an in-flight ffmpeg subprocess; cancellation only prevents starting the *next* stage. Explicitly chosen over the more invasive alternative during brainstorming.
- **No changes to image/poster uploads** (`ImageUploadField.tsx`) — they have no async processing pipeline; this is entirely about video (`RAW_VIDEO`/`TRAILER`).
- **No new notification event types or changes to the notification system itself** — `CONTENT_PROCESSED` already fires correctly for both outcomes; this spec only makes the upload UI's copy actually reference that existing mechanism.
- **No new status-lookup endpoint** — both existing endpoints partner-web already calls fully support on-demand refetching once the auto-poll interval is removed.
- **No reduced-but-nonzero polling fallback** — auto-polling is removed entirely, not slowed down, per the explicit choice made during brainstorming.
