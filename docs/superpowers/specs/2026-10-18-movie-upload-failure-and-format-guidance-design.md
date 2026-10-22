# Movie Upload Failure Fix & Partner Format Guidance — Design

## Problem

Partners reported that full-length movie uploads fail after processing runs for a
while, while trailer uploads succeed. Investigation (via code/config analysis — the
captured `server.log` only covers `api-service`, not `media-worker`'s own output)
found three compounding defects in `media-worker`, not the audio-track issue fixed
separately in `e13a645` (that fix was scoped to trailers, which aren't the ones
failing here).

## Root cause

1. **Duration-blind transcode timeout.** `worker.ffmpeg.timeout-seconds` (default
   1800s / 30 min) caps every single ffmpeg invocation — including each resolution
   tier (1080p/720p/480p/360p) transcoded sequentially via software `libx264` in
   `FFmpegRunner`/`SystemProcessRunner`. This is ample headroom for a short trailer
   but can easily be exceeded by just the first tier of a feature-length movie on
   modest hardware, triggering `ProcessTimeoutException`.

2. **The retry mechanism silently no-ops.** `VideoProcessingService.process()`'s
   catch-all branch calls `markFailed()` — setting both `asset.processingStatus` and
   the `ProcessingJob.status` to `"FAILED"` — *before* throwing
   `RetryableProcessingException` to trigger a genuine retry via `RetryPublisher`.
   But `process()`'s very first lines skip execution if
   `existsByJobIdAndStatus(jobId, "FAILED")` is already true for that `jobId` — which
   it now is, from the attempt that just "failed." Every subsequent retry is silently
   skipped ("already terminal, skipping") without ever re-running ffmpeg, even though
   the logs show "Scheduling retry attempt N" right before it.

3. **A latent unique-constraint crash.** `ProcessingJob.jobId` has a DB-level unique
   constraint, but `createJob()` always inserts a brand-new row via
   `new ProcessingJob()` + save, while `RetryPublisher` reuses the same `jobId`
   across every retry of the same asset. If defect #2 were fixed in isolation, the
   very next retry attempt would crash on the unique-constraint violation the moment
   `createJob()` tried to insert.

**Why movies fail and trailers don't:** movies are far more likely to hit timeout #1
on their first attempt given their length, and once any asset hits a retryable
failure, defect #2 means it never gets the second or third attempt it's supposed to
— it's stuck permanently `FAILED` after one try. Trailers rarely trigger a retryable
failure in the first place, so #2 and #3 go unnoticed for them.

## Fix design (media-worker)

1. **Duration-scaled timeout.** Once `ffprobeRunner.probe()` returns
   `meta.durationSeconds()`, compute
   `transcodeTimeoutSeconds = max(workerProperties.ffmpeg.timeoutSeconds, meta.durationSeconds() * 3)`
   and pass it into `FFmpegRunner.transcode()`/`generateThumbnail()`, which pass it
   through to `ProcessRunner.run()`. `SystemProcessRunner` changes from a
   constructor-injected fixed timeout to accepting it as a parameter on `run()`.
   `ffprobeRunner.probe()` itself keeps the flat default — probing is always fast
   regardless of source length.

2. **Stop marking the asset terminally FAILED on a retryable attempt.** Split
   `markFailed()`'s responsibilities: a retryable failure records attempt-scoped
   failure info on the `ProcessingJob` row only — `asset.processingStatus` stays
   `PROCESSING`. The asset is only set to terminal `FAILED` when `RetryPublisher`
   throws `MaxAttemptsExceededException`, caught in `VideoProcessingConsumer` — that's
   where the terminal write moves to.

3. **Update the existing `ProcessingJob` row on retry instead of inserting a new
   one.** `createJob()` becomes `getOrCreateJob()`: look up `findByJobId(jobId)`
   first; if found, update its `attempt`/`status`/`stageStartedAt` in place (clearing
   `errorMessage`/`completedAt`) rather than inserting a duplicate row that would
   violate the unique constraint.

4. **No separate change needed for the terminal guard.** Once #2 stops writing
   `FAILED` prematurely, the existing `existsByJobIdAndStatus(jobId, "FAILED")` check
   at the top of `process()` naturally only matches genuinely-exhausted jobs.

## Format guidance for partners

Separately, partners currently get no indication of what the server actually
accepts, and a too-long movie is only rejected *after* a full multi-GB upload
completes (duration is checked in media-worker, post-upload — api-service has no
duration/resolution awareness at all today). Current server-enforced constraints,
confirmed in code (`UploadConfig.java`):

- **RAW_VIDEO**: MP4, MOV, MKV, MPEG-TS/M2TS, AVI — max 10GB, audio track required.
- **TRAILER**: MP4, MOV only — max 2GB, audio optional.
- **Duration**: 4 hours max (`worker.processing.max-duration-seconds`), enforced only
  in media-worker today.

### Backend

New `GET /upload/constraints?assetType=RAW_VIDEO|TRAILER` endpoint on
`UploadController`, reading from `UploadConfig`'s existing MIME/size constants, so
the UI never hardcodes numbers that can drift from what's actually enforced.
`maxDurationSeconds` gets duplicated into `UploadConfig` (it currently exists only in
media-worker's config) with a comment flagging it must stay in sync with
`worker.processing.max-duration-seconds`. Response shape:
`{ allowedMimeTypes[], maxFileSizeBytes, maxDurationSeconds, audioRequired }`.

### Partner-web UI

**Static info panel (`UploadFormatHint`):** rendered above `VideoUploadField`,
fetches `/upload/constraints` and shows human-readable guidance — accepted
containers, max size, max duration, and "audio track required" for main-video
uploads only.

**Client-side duration pre-check:** before `VideoUploadField` starts the upload
session, it loads the selected file into a hidden `<video>` element and reads
`duration` from the `loadedmetadata` event. If it exceeds `maxDurationSeconds`, the
upload is rejected immediately with a clear message — avoiding a wasted multi-GB
upload for a file that would otherwise only fail hours later in media-worker. If the
browser can't determine duration (a known quirk with some MKV/AVI metadata), the
check fails open and lets the upload proceed, relying on media-worker's existing
post-hoc duration check as the backstop.

**Duration autofill:** `ContentDetailsFields`' `durationMinutes` field (in
`ContentForm`) and `VideoUploadField` (in the separate `features/uploads` module)
currently have no connection — video upload only becomes available after the content
record already exists (edit mode), while duration is an optional manual field filled
in at creation time. On successful client-side duration detection, `VideoUploadField`
calls a new `onDurationDetected(seconds)` callback, forwarded through
`ContentVideoCard` into `ContentForm`, which calls
`form.setValue('durationMinutes', ...)` — reusing the seconds↔minutes conversion
already in `ContentForm.tsx`. The field stays editable; this pre-fills it rather than
replacing manual entry entirely, since the partner may still want to correct it
before saving.

## Testing

**media-worker:** unit tests for `getOrCreateJob()` (reuses existing row, no
duplicate-insert), for the duration-scaled timeout calculation, and an integration
test simulating a retryable failure followed by a successful retry (verifying the
asset is NOT marked `FAILED` after attempt 1, and the second attempt actually
executes rather than being skipped).

**api-service:** unit test for the new `/upload/constraints` endpoint returning the
correct values per asset type.

**partner-web:** no test framework exists here — verification is type-check + lint +
manual walkthrough: selecting an oversized-duration file and confirming immediate
rejection without an upload attempt, and selecting a valid file and confirming
`durationMinutes` gets pre-filled correctly.
