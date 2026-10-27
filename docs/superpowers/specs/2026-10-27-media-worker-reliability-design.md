# Media Worker Reliability for Large Content — Design

**Date:** 2026-10-27
**Status:** §1 and §2 implemented; §3 instrumented (not fixed, per design below); §4 and §5 remain external follow-ups
**Repos:** `server` (media-worker, api-service), RabbitMQ infra (`docker-compose.prod.yml`)
**Depends on:** none
**Blocks:** none — but §3 should land before §1 is trusted at scale (see Verification plan)

## Context

Investigation started from `server.log`/`worker.log`: main-video processing jobs were dying with
`SystemProcessRunner : Command timed out after 1800s and was killed` during the 360p/720p ffmpeg
transcode step. Moving the worker to a dedicated 6 vCPU Xeon Gold 6150 / 16GB server did not fix
large-content processing, which was the signal that pointed at something other than raw compute.

Four distinct, independently-confirmed problems came out of that investigation, from four
different pieces of evidence (log timestamps, Dockerfile contents, docker-compose files, and the
worker's own source comments):

1. **Two unrelated timeouts, not one.** `worker.ffmpeg.timeout-seconds` (our code,
   [VideoProcessingService.java:101-103](../../media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java#L101-L103))
   correctly scales to `sourceDuration × 3` — a 2.5-hour movie got a 26,802s (7.4h) budget on the
   new server, confirmed via `(timeout 26802s)` in the production log. But RabbitMQ's own
   `consumer_timeout`, a broker-side setting nobody has ever configured (no `rabbitmq.conf`
   anywhere in the repo), silently sits at its 30-minute default and force-closes the channel
   before our own, much larger timeout ever gets a chance to matter. This is why the bigger server
   didn't help — the ceiling being hit was never compute.
2. **No hardware acceleration + default preset.** `mwader/static-ffmpeg`
   ([Dockerfile:25](../../Dockerfile#L25)) is software-only libx264, and `FFmpegRunner` never sets
   `-preset`, defaulting to `medium`. Confirmed in the dev-log run: even on the new hardware, a
   short-trailer 360p encode took 23 minutes — abnormally slow for that tier.
3. **The retry mechanism is defeated in practice.** `RetryPublisher.MAX_ATTEMPTS = 3` is the
   intended design, but every observed failure across two separate log captures and two
   environments shows the real attempt-2 redelivery hitting
   `"Job {} already terminal, skipping"` ([VideoProcessingService.java:63](../../media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java#L63))
   within milliseconds — meaning every failing main-video job gets exactly one real attempt, not
   three.
4. **RabbitMQ's AMQP port is open to all interfaces** in `docker-compose.prod.yml` (`5672:5672`,
   no IP prefix) — needed because the worker now lives on a separate server, but currently
   unrestricted beyond the broker username/password, over plain `amqp://`.

## Design

### 1. Fix the RabbitMQ `consumer_timeout` mismatch

Mount a broker config that raises `consumer_timeout` past the worst case our own code already
allows for: `max-duration-seconds: 14400` (4h,
[application.yml:41](../../media-worker/src/main/resources/application.yml#L41)) × 3 = 12h.
Round up for margin.

**New file `rabbitmq/rabbitmq.conf`:**
```
consumer_timeout = 46800000
```
(13 hours, in ms)

**`docker-compose.prod.yml` — add one volume line to the existing `rabbitmq` service:**
```yaml
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq
      - ./rabbitmq/rabbitmq.conf:/etc/rabbitmq/rabbitmq.conf:ro
```
Requires `docker compose up -d rabbitmq` (recreate, not just restart — the mount is fixed at
container creation).

Applied globally rather than scoped to `media.video.process` via a RabbitMQ policy: the only other
queues in the system (`notifications.send`, `media.video.failed`) have no long-running consumers,
so the 30-minute protection isn't doing useful work for them today. Revisit with a scoped policy if
that stops being true.

### 2. Fix the ffmpeg preset

`FFmpegRunner.transcode()` ([FFmpegRunner.java:34-50](../../media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFmpegRunner.java#L34-L50))
adds `-preset veryfast` to the built command (not `-preset medium`, the implicit default today).
Expected 2-4x faster encodes for a modest bitrate/size tradeoff — free, no new infra, and shrinks
how often §1's generous timeout is even needed.

```java
"-c:v", "libx264",
"-preset", "veryfast",
"-b:v", videoBitrate,
```

`FFmpegRunnerTest.java`'s command-list assertions need the new argument added at the matching
position once this lands.

### 3. The retry-skip bug — root cause and required verification before a fix

**What's proven:** `getOrCreateJob`'s own comment
([VideoProcessingService.java:194-196](../../media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java#L194-L196))
states the design assumption directly: *"No optimistic lock on this find-then-write: relies on
RetryPublisher only ever scheduling one delayed republish per attempt, so concurrent delivery of
the same jobId isn't expected in practice."* Every failure in both log captures shows exactly that
assumption violated: the broker's forced channel closure (§1) redelivers the still-unacked
original message *while* `RetryPublisher`'s own scheduled retry is in flight for the same `jobId`
— two concurrent `process()` calls for one job, sharing one `jobDir` and one `ProcessingJob` row,
with no lock between them.

**What's not yet proven:** which specific write — the shared `cleanup(jobDir)` deleting files out
from under the other thread, a lost-update race on the `ProcessingJob` row, or something in a
worker build that doesn't match this source tree — is the one actually flipping the row to a
terminal status fast enough to produce the exact "already terminal, skipping" log line.
`VideoProcessingConsumerTest.java` mocks every collaborator away and asserts the *intended*
behavior (`verify(processingService, never()).markTerminallyFailed(...)` when attempts remain) —
it doesn't exercise the real concurrent-DB-write path, so it can't have caught this.

**Required before writing a fix (Phase 1 of this investigation):**
- ~~Query `processing_jobs` for one real failed `jobId`~~ — not possible from the dev machine this
  spec was written on (no access to the production DB). **Done instead:** the skip log at
  [VideoProcessingService.java:61-69](../../media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java#L61-L69)
  now includes `status`, `attempt`, and `updatedAt` on the terminal row, not just the jobId. This
  is instrumentation only — same condition, same early-return, no behavior change — so the next
  real occurrence in production will show directly which status got set and when, closing the gap
  the old one-line message left. Still waiting on that occurrence before a fix is written.
- Rule out the cheap explanation first: confirm the deployed worker image was actually rebuilt from
  this exact source after the last refactor to this method, before assuming a logic bug.

**Most likely fix, pending that confirmation:** replace the find-then-write in `getOrCreateJob`
with an atomic claim — either a `@Version` column on `ProcessingJob` (optimistic locking, so a
losing concurrent writer gets an `OptimisticLockException` instead of silently corrupting the
row) or a conditional atomic `UPDATE ... WHERE status NOT IN ('DONE','FAILED')` (same pattern
`StaleVideoAssetJob` already uses for its own concurrent-write safety). The losing side should
no-op cleanly, the same outcome the "already terminal" check was trying to provide — just
triggered by an actual claim instead of a status string that isn't supposed to be set yet.

Fixing §1 reduces *how often* this race fires (fewer broker-forced redeliveries), but doesn't
eliminate the underlying gap — duplicate delivery can still happen from consumer crashes or
network blips. This needs its own fix regardless of §1 landing.

### 4. Worker resource isolation

Per `docs/superpowers/specs/2026-10-04-production-docker-deployment-design.md`, `media-worker` is
a standalone Dokploy application, not part of `docker-compose.prod.yml` — so this isn't a compose
change. Action: set an explicit CPU/memory reservation on the `media-worker` Dokploy app (not just
"whatever's free on the box") so it isn't competing unpredictably with other Dokploy-managed
services on the same host for the cores §2's `-preset veryfast` is trying to use efficiently.

### 5. Restrict RabbitMQ's AMQP port

`5672:5672` with no IP prefix is reachable from the public internet, protected only by
username/password over plain `amqp://`. Out of scope for this spec's code changes, but flagged as
a required follow-up: either firewall/security-group `5672` down to the worker server's specific
IP, or put both servers on a private network/VPN so `5672` never needs to face the public internet.

## Non-goals

- **Not decoupling RabbitMQ ack from job completion in this round.** The architecturally cleaner
  fix (ack immediately, track real progress via the `processing_jobs` DB rows already being
  written at every stage) removes the need for any multi-hour `consumer_timeout` at all, but it's
  a bigger change than this spec's scope. §1's fix is the pragmatic unblock; revisit this once §1
  and §3 are stable in production.
- **Not switching encoders** (NVENC, AV1/SVT-AV1, HEVC) in this round — `-preset veryfast` is the
  free lever; hardware encoding is a hardware-provisioning decision for later if §2 isn't enough.
- **Not implementing the firewall/VPN change itself** (§5) — flagged as required, not designed
  here; it's an infra/ops task outside this repo's code.
- **Not touching the partner-facing cancel/delete UX** from
  `docs/superpowers/specs/2026-09-21-video-processing-actions-design.md` — unrelated surface.

## Verification plan

- §1: `docker compose -f docker-compose.prod.yml up -d rabbitmq`, confirm healthcheck passes, then
  `rabbitmqctl environment` (or the management UI) shows `consumer_timeout` at the configured
  value, not the 30-minute default.
- §2: `FFmpegRunnerTest` passes with the updated expected command list; manually diff encode time
  for a known sample file, preset `medium` vs `veryfast`.
- §3: **Do not implement a fix until the DB/log verification step above has actually identified
  the write path** — this is the one item in this spec that is diagnosis, not yet a design.
- §4: confirm via Dokploy's own resource/usage view that `media-worker` is capped at its
  reservation and not silently sharing cores with another app during a transcode.
- End-to-end: re-run a known large (~2.5h) main video through the full pipeline after §1+§2 land,
  confirm it completes without the broker closing the channel and without a spurious "already
  terminal, skipping" on a genuine first attempt.
