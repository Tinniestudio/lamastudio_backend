# Movie Upload Failure Fix (Server) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the three compounding media-worker defects that make full-length movie uploads fail permanently after one retryable error (duration-blind ffmpeg timeout, a retry mechanism that silently no-ops, and a latent unique-constraint crash on retry), and add a new `GET /uploads/constraints` endpoint on api-service so partner-web can show upload guidance and pre-validate client-side.

**Architecture:** media-worker changes thread a per-call, duration-scaled timeout through `ProcessRunner → FFmpegRunner → VideoProcessingService`, and change how `VideoProcessingService`/`VideoProcessingConsumer` cooperate on failure bookkeeping so a retryable attempt no longer writes a terminal state that blocks its own retry. api-service changes add a read-only endpoint over the MIME/size constants `UploadConfig` already enforces, plus a new duration constant that must be kept in sync with media-worker's `worker.processing.max-duration-seconds`.

**Tech Stack:** Spring Boot 3.3 / Java 21, JUnit 5 + Mockito + AssertJ, Spring Data JPA, RabbitMQ (`spring-rabbit`), `@DataJpaTest` (H2) for the one transactional-boundary test.

**Spec:** `docs/superpowers/specs/2026-10-18-movie-upload-failure-and-format-guidance-design.md`

---

## Task 1: Duration-scaled transcode timeout

**Files:**
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/ProcessRunner.java`
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/SystemProcessRunner.java`
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFmpegRunner.java`
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFprobeRunner.java`
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/config/StartupValidator.java`
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java`
- Test: `media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/SystemProcessRunnerTest.java`
- Test: `media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/FFmpegRunnerTest.java`
- Test: `media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/FFprobeRunnerTest.java`
- Test: `media-worker/src/test/java/com/tinniestudio/worker/processor/VideoProcessingServiceTest.java`

Today `worker.ffmpeg.timeout-seconds` (default 1800s/30min) is baked into `SystemProcessRunner` via constructor injection and applies to every ffmpeg invocation, including each resolution tier of a feature-length movie. This task makes the timeout a per-call parameter so `VideoProcessingService` can scale it to the source's actual duration (`max(default, durationSeconds * 3)`), while `FFprobeRunner` keeps using the flat default (probing is always fast regardless of source length).

- [ ] **Step 1: Update `SystemProcessRunnerTest` for the new `run(command, timeoutSeconds)` signature**

Replace the whole file:

```java
package com.tinniestudio.worker.ffmpeg;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemProcessRunnerTest {

    private final SystemProcessRunner runner = new SystemProcessRunner();

    @Test
    void returnsOutputWhenCommandCompletesWithinTimeout() throws Exception {
        String output = runner.run(List.of("sh", "-c", "echo hello-world"), 30);

        assertThat(output).contains("hello-world");
    }

    @Test
    void throwsRuntimeExceptionWhenExitCodeNonZero() {
        assertThatThrownBy(() -> runner.run(List.of("sh", "-c", "exit 3"), 30))
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(ProcessTimeoutException.class)
            .hasMessageContaining("exit=3");
    }

    @Test
    void killsHungProcessAndThrowsProcessTimeoutExceptionInsteadOfBlockingForever() {
        // 1-second timeout against a command that would otherwise run for 30s.
        long start = System.currentTimeMillis();

        assertThatThrownBy(() -> runner.run(List.of("sh", "-c", "sleep 30"), 1))
            .isInstanceOf(ProcessTimeoutException.class)
            .hasMessageContaining("timed out");

        long elapsedMs = System.currentTimeMillis() - start;
        // Must return shortly after the configured timeout, proving it did not
        // block for the full 30s sleep (the pre-fix no-arg waitFor() behavior).
        assertThat(elapsedMs).isLessThan(15_000);
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails to compile**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.ffmpeg.SystemProcessRunnerTest"`
Expected: FAIL — `run(List, long)` and the no-arg `SystemProcessRunner()` constructor don't exist yet.

- [ ] **Step 3: Update `ProcessRunner` interface**

```java
package com.tinniestudio.worker.ffmpeg;

import java.io.IOException;
import java.util.List;

public interface ProcessRunner {
    /**
     * Executes a command, waits up to timeoutSeconds for completion, returns stdout.
     * Throws RuntimeException if exit code != 0, or ProcessTimeoutException if the
     * process doesn't finish within timeoutSeconds.
     */
    String run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException;
}
```

- [ ] **Step 4: Update `SystemProcessRunner` to take the timeout as a `run()` parameter**

```java
package com.tinniestudio.worker.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class SystemProcessRunner implements ProcessRunner {

    @Override
    public String run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException {
        // Redirect stdout/stderr to a temp file instead of reading via a pipe
        // (InputStream#readAllBytes() blocks until the pipe closes, which would
        // hang forever alongside a stuck process even with a waitFor timeout).
        Path outputFile = Files.createTempFile("worker-proc-", ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(outputFile.toFile());
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                String message = "Command timed out after " + timeoutSeconds + "s and was killed: "
                    + String.join(" ", command);
                log.error(message);
                throw new ProcessTimeoutException(message);
            }

            String output = Files.readString(outputFile);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Command failed (exit=" + exitCode + "): " + String.join(" ", command)
                    + "\nOutput: " + output);
            }
            return output;
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }
}
```

- [ ] **Step 5: Run `SystemProcessRunnerTest` again to confirm it passes**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.ffmpeg.SystemProcessRunnerTest"`
Expected: PASS (3 tests)

- [ ] **Step 6: Update `StartupValidator`'s now-broken call site**

`WorkerProperties workerProperties` is already a field on this class. Edit `validateCommand`:

```java
    private void validateCommand(String binaryPath, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(binaryPath);
            cmd.addAll(List.of(args));
            processRunner.run(cmd, workerProperties.getFfmpeg().getTimeoutSeconds());
        } catch (Exception e) {
            throw new IllegalStateException(
                "Required tool not found: " + binaryPath + " — " + e.getMessage(), e);
        }
    }
```

- [ ] **Step 7: Update `FFmpegRunnerTest` for the new `transcode`/`generateThumbnail` signatures**

Replace the whole file:

```java
package com.tinniestudio.worker.ffmpeg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FFmpegRunnerTest {

    @Mock ProcessRunner processRunner;

    private FFmpegRunner runner;

    @BeforeEach
    void setUp() {
        runner = new FFmpegRunner(processRunner, "/usr/bin/ffmpeg", 6);
    }

    @Nested
    class transcode {
        @Test
        void buildsCorrectHlsCommandFor1080p() throws Exception {
            when(processRunner.run(anyList(), anyLong())).thenReturn("");

            runner.transcode("/tmp/input.mp4", "/tmp/jobs/abc/1080p",
                1920, 1080, "5000k", "192k", 1800);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processRunner).run(captor.capture(), eq(1800L));
            List<String> cmd = captor.getValue();

            assertThat(cmd).contains("/usr/bin/ffmpeg");
            assertThat(cmd).contains("-i", "/tmp/input.mp4");
            assertThat(cmd).contains("-b:v", "5000k");
            assertThat(cmd).contains("-b:a", "192k");
            assertThat(cmd).contains("-hls_time", "6");
            assertThat(cmd).contains("-hls_playlist_type", "vod");
            assertThat(cmd.get(cmd.size() - 1)).endsWith("playlist.m3u8");
        }

        @Test
        void buildsCorrectScaleFilterFor720p() throws Exception {
            when(processRunner.run(anyList(), anyLong())).thenReturn("");

            runner.transcode("/tmp/input.mp4", "/tmp/jobs/abc/720p",
                1280, 720, "2800k", "128k", 1800);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processRunner).run(captor.capture(), anyLong());
            List<String> cmd = captor.getValue();

            assertThat(cmd).contains("-vf", "scale=1280:720");
        }

        @Test
        void passesTheGivenTimeoutThroughToProcessRunner() throws Exception {
            when(processRunner.run(anyList(), anyLong())).thenReturn("");

            runner.transcode("/tmp/input.mp4", "/tmp/jobs/abc/1080p",
                1920, 1080, "5000k", "192k", 5400);

            verify(processRunner).run(anyList(), eq(5400L));
        }
    }

    @Nested
    class generateThumbnail {
        @Test
        void buildsCorrectThumbnailCommand() throws Exception {
            when(processRunner.run(anyList(), anyLong())).thenReturn("");

            runner.generateThumbnail("/tmp/input.mp4", "/tmp/jobs/abc/thumbnail.jpg", 1800);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(processRunner).run(captor.capture(), eq(1800L));
            List<String> cmd = captor.getValue();

            assertThat(cmd).contains("/usr/bin/ffmpeg");
            assertThat(cmd).contains("-ss", "00:00:05");
            assertThat(cmd).contains("-vframes", "1");
            assertThat(cmd.get(cmd.size() - 1)).endsWith("thumbnail.jpg");
        }
    }
}
```

Note the added `import static org.mockito.ArgumentMatchers.eq;` is covered by the existing `import static org.mockito.Mockito.*;` only for `verify`/`when`; add `eq` explicitly:

```java
import static org.mockito.ArgumentMatchers.eq;
```

(place it alongside the other `ArgumentMatchers` static imports at the top of the file)

- [ ] **Step 8: Run `FFmpegRunnerTest` to confirm it fails to compile**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.ffmpeg.FFmpegRunnerTest"`
Expected: FAIL — `transcode(..., long)` / `generateThumbnail(..., long)` don't exist yet.

- [ ] **Step 9: Update `FFmpegRunner` to accept and forward `timeoutSeconds`**

```java
package com.tinniestudio.worker.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class FFmpegRunner {

    private final ProcessRunner processRunner;
    private final String ffmpegPath;
    private final int hlsSegmentDuration;

    public FFmpegRunner(ProcessRunner processRunner,
                        @Value("${worker.ffmpeg.path:/usr/bin/ffmpeg}") String ffmpegPath,
                        @Value("${worker.ffmpeg.hls-segment-duration:6}") int hlsSegmentDuration) {
        this.processRunner = processRunner;
        this.ffmpegPath = ffmpegPath;
        this.hlsSegmentDuration = hlsSegmentDuration;
    }

    public void transcode(String inputPath, String outputDir,
                          int width, int height,
                          String videoBitrate, String audioBitrate,
                          long timeoutSeconds) throws Exception {
        String segmentPattern = outputDir + "/segment_%04d.ts";
        String playlistPath   = outputDir + "/playlist.m3u8";

        List<String> cmd = new ArrayList<>(List.of(
            ffmpegPath,
            "-i", inputPath,
            "-vf", "scale=" + width + ":" + height,
            "-c:v", "libx264",
            "-b:v", videoBitrate,
            "-maxrate", videoBitrate,
            "-bufsize", String.valueOf(parseBitrateKbps(videoBitrate) * 2) + "k",
            "-c:a", "aac",
            "-b:a", audioBitrate,
            "-hls_time", String.valueOf(hlsSegmentDuration),
            "-hls_playlist_type", "vod",
            "-hls_segment_filename", segmentPattern,
            playlistPath
        ));
        log.info("Transcoding → {} at {}x{} {} (timeout {}s)", outputDir, width, height, videoBitrate, timeoutSeconds);
        processRunner.run(cmd, timeoutSeconds);
    }

    public void generateThumbnail(String inputPath, String outputPath, long timeoutSeconds) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
            ffmpegPath,
            "-i", inputPath,
            "-ss", "00:00:05",
            "-vframes", "1",
            "-q:v", "2",
            outputPath
        ));
        log.info("Generating thumbnail → {}", outputPath);
        processRunner.run(cmd, timeoutSeconds);
    }

    private int parseBitrateKbps(String bitrate) {
        return Integer.parseInt(bitrate.replace("k", ""));
    }
}
```

- [ ] **Step 10: Run `FFmpegRunnerTest` to confirm it passes**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.ffmpeg.FFmpegRunnerTest"`
Expected: PASS (4 tests)

- [ ] **Step 11: Update `FFprobeRunnerTest` for the new 3-arg constructor**

In `FFprobeRunnerTest.java`:

1. Change the `setUp()` method:

```java
    @BeforeEach
    void setUp() {
        runner = new FFprobeRunner(processRunner, "/usr/bin/ffprobe", 1800L);
    }
```

2. Add this import alongside the existing static imports:

```java
import static org.mockito.ArgumentMatchers.anyLong;
```

3. Replace every occurrence of `when(processRunner.run(anyList())).thenReturn(json);` (there are 6, one per test) with:

```java
            when(processRunner.run(anyList(), anyLong())).thenReturn(json);
```

- [ ] **Step 12: Run `FFprobeRunnerTest` to confirm it fails to compile**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.ffmpeg.FFprobeRunnerTest"`
Expected: FAIL — 3-arg constructor and `run(List, long)` don't exist yet.

- [ ] **Step 13: Update `FFprobeRunner` to carry and use a flat default timeout**

```java
package com.tinniestudio.worker.ffmpeg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class FFprobeRunner {

    private final ProcessRunner processRunner;
    private final String ffprobePath;
    private final long timeoutSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FFprobeRunner(ProcessRunner processRunner,
                         @Value("${worker.ffmpeg.ffprobe-path:/usr/bin/ffprobe}") String ffprobePath,
                         @Value("${worker.ffmpeg.timeout-seconds:1800}") long timeoutSeconds) {
        this.processRunner = processRunner;
        this.ffprobePath = ffprobePath;
        this.timeoutSeconds = timeoutSeconds;
    }

    public VideoMetadata probe(String inputPath, boolean requireAudio) throws Exception {
        List<String> command = List.of(
            ffprobePath, "-v", "quiet",
            "-print_format", "json",
            "-show_streams", "-show_format",
            inputPath
        );
        // Probing reads container metadata only — it's fast regardless of source
        // length, so it always uses the flat default, never the duration-scaled
        // timeout VideoProcessingService computes for actual transcoding.
        String output = processRunner.run(command, timeoutSeconds);
        return parseOutput(output, requireAudio);
    }

    private VideoMetadata parseOutput(String json, boolean requireAudio) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode streams = root.get("streams");
        JsonNode format = root.get("format");

        if (streams == null || streams.isNull() || !streams.isArray()) {
            throw new ValidationException("Probe failed: no streams array in ffprobe output");
        }

        JsonNode videoStream = null;
        boolean hasAudio = false;

        for (JsonNode stream : streams) {
            String codecType = stream.path("codec_type").asText();
            if ("video".equals(codecType) && videoStream == null) {
                videoStream = stream;
            } else if ("audio".equals(codecType)) {
                hasAudio = true;
            }
        }

        if (videoStream == null) {
            throw new ValidationException("Probe failed: no video stream found in " + json);
        }
        if (requireAudio && !hasAudio) {
            throw new ValidationException("Probe failed: no audio stream found in " + json);
        }

        int durationSeconds = (int) Double.parseDouble(format.path("duration").asText("0"));
        int width = videoStream.path("width").asInt();
        int height = videoStream.path("height").asInt();
        String codec = videoStream.path("codec_name").asText();
        long bitrate = videoStream.path("bit_rate").asLong(0);
        if (bitrate == 0) {
            bitrate = format.path("bit_rate").asLong(0);
        }

        return new VideoMetadata(durationSeconds, width, height, codec, bitrate, hasAudio);
    }

    public record VideoMetadata(
        int durationSeconds,
        int width,
        int height,
        String codec,
        long bitrate,
        boolean hasAudio
    ) {}

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }
}
```

- [ ] **Step 14: Run `FFprobeRunnerTest` to confirm it passes**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.ffmpeg.FFprobeRunnerTest"`
Expected: PASS (6 tests)

- [ ] **Step 15: Update the two broken `verify()` calls in `VideoProcessingServiceTest`**

`FFmpegRunner.transcode()` now takes 7 args, not 6 — the mock's `@Mock FFmpegRunner ffmpegRunner` is unaffected by the real class's compile, but these two `verify()` calls statically reference the old 6-arg overload, which no longer exists. In `VideoProcessingServiceTest.java`, in the `process` nested class:

Change (inside `stopsBeforeTranscodingWhenCancelledDuringEarlierStages`):
```java
            verify(ffmpegRunner, never()).transcode(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString());
```
to:
```java
            verify(ffmpegRunner, never()).transcode(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString(), anyLong());
```

Change (inside `stopsBeforeUploadingWhenCancelledAfterTranscoding`):
```java
            verify(ffmpegRunner, times(1)).transcode(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString());
```
to:
```java
            verify(ffmpegRunner, times(1)).transcode(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString(), anyLong());
```

(`anyLong()` is already available via the file's existing `import static org.mockito.ArgumentMatchers.*;`)

- [ ] **Step 16: Add two new tests for the duration-scaled timeout calculation**

Add inside the `process` nested class in `VideoProcessingServiceTest.java` (e.g. after `createsVideoVariantRecordsForEachResolution`):

```java
        @Test
        void scalesTranscodeTimeoutToTripleSourceDurationWhenLongerThanDefault() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);

            // 20-minute (1200s) source: 1200*3=3600s exceeds the 1800s default timeout.
            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                1200, 640, 360, "h264", 800_000L, true);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(meta);

            service.process(payload);

            ArgumentCaptor<Long> timeoutCaptor = ArgumentCaptor.forClass(Long.class);
            verify(ffmpegRunner).transcode(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString(), timeoutCaptor.capture());
            assertThat(timeoutCaptor.getValue()).isEqualTo(3600L);
        }

        @Test
        void keepsDefaultTranscodeTimeoutWhenSourceIsShort() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));
            when(processingJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);

            // 30s trailer: 30*3=90s is well under the 1800s default, so the default wins.
            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                30, 640, 360, "h264", 800_000L, true);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(meta);

            service.process(payload);

            ArgumentCaptor<Long> timeoutCaptor = ArgumentCaptor.forClass(Long.class);
            verify(ffmpegRunner).transcode(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyString(), timeoutCaptor.capture());
            assertThat(timeoutCaptor.getValue()).isEqualTo(1800L);
        }
```

- [ ] **Step 17: Run `VideoProcessingServiceTest` to confirm it fails to compile**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.processor.VideoProcessingServiceTest"`
Expected: FAIL — `ffmpegRunner.transcode(...)` call sites in `VideoProcessingService` still use the old 6-arg/2-arg signatures, so the two new tests' captured-argument assertions can't pass (and the production code itself won't compile against the real `FFmpegRunner`).

- [ ] **Step 18: Update `VideoProcessingService` to compute and pass the duration-scaled timeout**

In `VideoProcessingService.java`, in the `process()` method, right after the duration-max-exceeded check and `applyMetadataToAsset(asset, meta)` call:

```java
            applyMetadataToAsset(asset, meta);

            // Duration-scaled transcode timeout: the flat worker.ffmpeg.timeout-seconds default
            // is enough headroom for a short trailer but can easily be exceeded by just the
            // first resolution tier of a feature-length movie on modest hardware. Scale up to
            // 3x the source's own duration when that's larger than the default, never below it.
            long transcodeTimeoutSeconds = Math.max(
                workerProperties.getFfmpeg().getTimeoutSeconds(),
                meta.durationSeconds() * 3L);

            // 4. RESOLUTION LADDER PLANNING
```

Then update the transcode call inside the tier loop:

```java
                ffmpegRunner.transcode(inputFile.toString(), tierDir.toString(),
                    tier.width(), tier.height(), tier.videoBitrate(), tier.audioBitrate(),
                    transcodeTimeoutSeconds);
```

And the thumbnail call:

```java
            ffmpegRunner.generateThumbnail(inputFile.toString(), thumbnailPath.toString(), transcodeTimeoutSeconds);
```

- [ ] **Step 19: Run the full `VideoProcessingServiceTest` suite to confirm everything passes**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.processor.VideoProcessingServiceTest"`
Expected: PASS (all tests, including the two new ones)

- [ ] **Step 20: Run the full media-worker test suite to catch any other breakage**

Run: `./gradlew :media-worker:test`
Expected: PASS. If `VideoProcessingServiceTransactionBoundaryTest` fails, stop — that's expected and is fixed in Task 3, not here; confirm the *only* failure is that one test before proceeding.

- [ ] **Step 21: Commit**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/ProcessRunner.java \
        media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/SystemProcessRunner.java \
        media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFmpegRunner.java \
        media-worker/src/main/java/com/tinniestudio/worker/ffmpeg/FFprobeRunner.java \
        media-worker/src/main/java/com/tinniestudio/worker/config/StartupValidator.java \
        media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java \
        media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/SystemProcessRunnerTest.java \
        media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/FFmpegRunnerTest.java \
        media-worker/src/test/java/com/tinniestudio/worker/ffmpeg/FFprobeRunnerTest.java \
        media-worker/src/test/java/com/tinniestudio/worker/processor/VideoProcessingServiceTest.java
git commit -m "fix(media-worker): scale transcode timeout to source duration instead of a flat 30min cap"
```

---

## Task 2: `getOrCreateJob()` — reuse the existing `ProcessingJob` row on retry

**Files:**
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java`
- Test: `media-worker/src/test/java/com/tinniestudio/worker/processor/VideoProcessingServiceTest.java`

`ProcessingJob.jobId` has a DB-level unique constraint, but `createJob()` always does `new ProcessingJob()` + `save()`. `RetryPublisher` reuses the same `jobId` across every retry of the same logical asset — once a retryable failure stops writing a terminal `FAILED` (Task 3), the very next retry attempt would otherwise crash on the unique-constraint violation. This task alone (in isolation, using a mocked repository) makes `process()` look up the existing row by `jobId` first and update it in place.

- [ ] **Step 1: Write the failing test**

Add inside the `process` nested class in `VideoProcessingServiceTest.java`:

```java
        @Test
        void reusesExistingProcessingJobRowOnASecondCallWithTheSameJobIdInsteadOfInsertingADuplicate() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            // Deliberately stub the terminal-skip guard to false regardless of what gets
            // written internally — this isolates getOrCreateJob()'s reuse behavior from the
            // separate "don't write FAILED prematurely" fix (Task 3), which this test doesn't
            // depend on.
            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "FAILED")).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));

            java.util.concurrent.atomic.AtomicReference<ProcessingJob> savedJob =
                new java.util.concurrent.atomic.AtomicReference<>();
            when(processingJobRepo.findByJobId(payload.getJobId()))
                .thenAnswer(inv -> Optional.ofNullable(savedJob.get()));
            when(processingJobRepo.save(any())).thenAnswer(inv -> {
                ProcessingJob j = inv.getArgument(0);
                savedJob.set(j);
                return j;
            });
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);
            when(ffprobeRunner.probe(anyString(), anyBoolean())).thenReturn(
                new FFprobeRunner.VideoMetadata(30, 640, 360, "h264", 800_000L, true));

            service.process(payload);
            ProcessingJob jobAfterFirstCall = savedJob.get();
            assertThat(jobAfterFirstCall).isNotNull();

            service.process(payload);
            ProcessingJob jobAfterSecondCall = savedJob.get();

            assertThat(jobAfterSecondCall)
                .as("the second call must reuse the same ProcessingJob entity, not construct a new one "
                    + "(which would violate the DB-level unique constraint on jobId)")
                .isSameAs(jobAfterFirstCall);
            assertThat(jobAfterSecondCall.getAttempt()).isEqualTo(2);
        }
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.processor.VideoProcessingServiceTest"`
Expected: FAIL — `createJob()` always builds `new ProcessingJob()`, so `jobAfterSecondCall` is a different instance from `jobAfterFirstCall`.

- [ ] **Step 3: Replace `createJob()` with `getOrCreateJob()`**

In `VideoProcessingService.java`, change the call site in `process()`:

```java
        ProcessingJob job = getOrCreateJob(jobId, videoAssetId, asset.getProcessingAttempts() + 1);
```

Replace the `createJob` method:

```java
    private ProcessingJob getOrCreateJob(String jobId, UUID videoAssetId, int attempt) {
        ProcessingJob job = processingJobRepo.findByJobId(jobId).orElseGet(ProcessingJob::new);
        job.setJobId(jobId);
        job.setVideoAssetId(videoAssetId);
        job.setStatus("VALIDATING");
        job.setAttempt(attempt);
        job.setStageStartedAt(Instant.now());
        job.setErrorMessage(null);
        job.setCompletedAt(null);
        return processingJobRepo.save(job);
    }
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.processor.VideoProcessingServiceTest"`
Expected: PASS (all tests, including the new one)

- [ ] **Step 5: Run the full media-worker suite**

Run: `./gradlew :media-worker:test`
Expected: Same single pre-existing failure as Task 1 (`VideoProcessingServiceTransactionBoundaryTest`), nothing new broken.

- [ ] **Step 6: Commit**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java \
        media-worker/src/test/java/com/tinniestudio/worker/processor/VideoProcessingServiceTest.java
git commit -m "fix(media-worker): reuse the existing ProcessingJob row by jobId instead of inserting a duplicate on retry"
```

---

## Task 3: Stop marking the asset terminally FAILED on a retryable attempt

**Files:**
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java`
- Modify: `media-worker/src/main/java/com/tinniestudio/worker/consumer/VideoProcessingConsumer.java`
- Test: `media-worker/src/test/java/com/tinniestudio/worker/processor/VideoProcessingServiceTest.java`
- Test: `media-worker/src/test/java/com/tinniestudio/worker/processor/VideoProcessingServiceTransactionBoundaryTest.java`
- Create: `media-worker/src/test/java/com/tinniestudio/worker/consumer/VideoProcessingConsumerTest.java`

Today, `process()`'s retryable catch branch calls `markFailed()`, which sets both `asset.processingStatus` and `job.status` to `"FAILED"` — immediately before throwing `RetryableProcessingException`. Because `process()`'s own first lines skip execution once `existsByJobIdAndStatus(jobId, "FAILED")` is true, every subsequent retry of that job is silently skipped forever, even though `RetryPublisher` genuinely schedules it. This task splits that responsibility: a retryable failure now only records attempt-scoped error info on the `ProcessingJob` row (leaving `asset.processingStatus` at `PROCESSING` and the job's `status` field untouched, so the terminal-skip guard stays `false`); the asset is only driven to terminal `FAILED` when `RetryPublisher` reports attempts exhausted, which `VideoProcessingConsumer` now acts on.

- [ ] **Step 1: Update the transactional-boundary regression test's expectations**

`VideoProcessingServiceTransactionBoundaryTest.failedStatusWriteSurvivesExceptionThrownLaterInThePipeline` currently asserts that a *retryable* error (a generic `RuntimeException` from `ffprobe`, not `FFprobeRunner.ValidationException`) leaves the asset `FAILED`. That assertion is for the OLD, buggy behavior this task removes. Rename the test and update its body:

```java
    @Test
    void jobAttemptFailureWriteSurvivesExceptionThrownLaterInThePipeline() throws Exception {
        UUID assetId = UUID.randomUUID();

        VideoAsset asset = new VideoAsset();
        asset.setId(assetId);
        asset.setRawStorageKey("uploads/" + assetId + "/raw.mp4");
        asset.setOriginalFilename("raw.mp4");
        asset.setProcessingStatus("PENDING");
        asset.setProcessingAttempts(0);
        videoAssetRepo.saveAndFlush(asset);

        MediaProcessingJobPayload payload = new MediaProcessingJobPayload();
        payload.setJobId("job-" + assetId);
        payload.setVideoAssetId(assetId);
        payload.setStorageKey(asset.getRawStorageKey());

        // download() must actually create a file — process() reads its size (Files.size) for
        // byte-tracking immediately afterwards, before ever reaching ffprobe. A no-op mock would
        // make the pipeline fail at the size-check instead of at the intended ffprobe step.
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.write(target, new byte[]{1, 2, 3, 4});
            return null;
        }).when(storageService).download(anyString(), any(Path.class));
        when(ffprobeRunner.probe(anyString(), anyBoolean()))
            .thenThrow(new RuntimeException("ffprobe crashed mid-pipeline"));

        boolean threw = false;
        try {
            service.process(payload);
        } catch (VideoProcessingService.RetryableProcessingException expected) {
            threw = true;
        }
        assertThat(threw)
            .as("process() must rethrow so the consumer/RetryPublisher can decide retry vs DLQ")
            .isTrue();

        VideoAsset persistedAsset = videoAssetRepo.findById(assetId).orElseThrow();
        assertThat(persistedAsset.getProcessingStatus())
            .as("a retryable failure must leave the asset PROCESSING (not FAILED, not reverted to "
                + "PENDING) so a stale terminal write can't block the next retry attempt")
            .isEqualTo("PROCESSING");

        ProcessingJob job = processingJobRepo.findByJobId(payload.getJobId()).orElseThrow();
        assertThat(job.getErrorMessage())
            .as("the attempt-scoped failure write in the catch block must survive the exception "
                + "thrown later in the same method, not roll back with it")
            .contains("ffprobe crashed mid-pipeline");
        assertThat(job.getStatus())
            .as("job status must not become FAILED on a retryable attempt — that would make the "
                + "terminal-skip check at the top of the next process() call falsely skip it")
            .isNotEqualTo("FAILED");
    }
```

Add this import alongside the others at the top of the file:

```java
import com.tinniestudio.worker.entity.ProcessingJob;
```

- [ ] **Step 2: Run the test to confirm it fails against the current code**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.processor.VideoProcessingServiceTransactionBoundaryTest"`
Expected: FAIL — `persistedAsset.getProcessingStatus()` is currently `"FAILED"`, not `"PROCESSING"`.

- [ ] **Step 3: Split `markFailed()` in `VideoProcessingService`**

Rename the existing `markFailed` to `markAssetAndJobFailed` (same body, used for the non-retryable `ValidationException` path) and add a new `recordRetryableFailure`:

```java
    private void markAssetAndJobFailed(VideoAsset asset, ProcessingJob job, String error) {
        asset.setProcessingStatus("FAILED");
        asset.setProcessingError(error);
        videoAssetRepo.save(asset);

        job.setStatus("FAILED");
        job.setErrorMessage(error);
        job.setCompletedAt(Instant.now());
        processingJobRepo.save(job);
    }

    /**
     * A retryable failure records attempt-scoped error info on the ProcessingJob row only —
     * the asset stays PROCESSING and the job's status field is left as whatever stage it failed
     * at. getOrCreateJob() clears errorMessage/completedAt on the next attempt. The asset/job
     * are only driven to terminal FAILED by markTerminallyFailed(), called from
     * VideoProcessingConsumer once RetryPublisher reports attempts exhausted.
     */
    private void recordRetryableFailure(ProcessingJob job, String error) {
        job.setErrorMessage(error);
        job.setCompletedAt(Instant.now());
        processingJobRepo.save(job);
    }

    /**
     * Called by VideoProcessingConsumer when RetryPublisher.retryOrFail() throws
     * MaxAttemptsExceededException — the terminal FAILED write for a retryable error lives here,
     * not in process() itself, since process() must leave the asset PROCESSING after every
     * individual retryable attempt (see recordRetryableFailure). Loads fresh entities since this
     * runs in a separate method invocation from the process() call that originally failed.
     */
    @Transactional
    public void markTerminallyFailed(UUID videoAssetId, String jobId, String errorMessage) {
        videoAssetRepo.findById(videoAssetId).ifPresent(asset -> {
            asset.setProcessingStatus("FAILED");
            asset.setProcessingError(errorMessage);
            videoAssetRepo.save(asset);
        });
        processingJobRepo.findByJobId(jobId).ifPresent(job -> {
            job.setStatus("FAILED");
            job.setErrorMessage(errorMessage);
            job.setCompletedAt(Instant.now());
            processingJobRepo.save(job);
        });
    }
```

Update the two catch blocks in `process()`:

```java
        } catch (FFprobeRunner.ValidationException e) {
            log.error("Non-retryable validation failure for asset {}: {}", videoAssetId, e.getMessage());
            markAssetAndJobFailed(asset, job, e.getMessage());
        } catch (Exception e) {
            log.error("Retryable error processing asset {}: {}", videoAssetId, e.getMessage());
            recordRetryableFailure(job, e.getMessage());
            throw new RetryableProcessingException(e.getMessage(), e);
        } finally {
            cleanup(jobDir);
        }
```

- [ ] **Step 4: Run the transactional-boundary test to confirm it passes**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.processor.VideoProcessingServiceTransactionBoundaryTest"`
Expected: PASS

- [ ] **Step 5: Run the rest of `VideoProcessingServiceTest` to confirm nothing else broke**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.processor.VideoProcessingServiceTest"`
Expected: PASS — `setsFailedStatusWhenDurationExceedsMax` and `setsFailedStatusOnNonRetryableError` go through the unchanged `ValidationException` branch (now calling the renamed `markAssetAndJobFailed`) and should still pass unmodified.

- [ ] **Step 6: Write the failing test for `VideoProcessingConsumer`'s new terminal-write call**

Create `media-worker/src/test/java/com/tinniestudio/worker/consumer/VideoProcessingConsumerTest.java`:

```java
package com.tinniestudio.worker.consumer;

import com.tinniestudio.worker.dto.MediaProcessingJobPayload;
import com.tinniestudio.worker.dto.ProcessingJobEnvelope;
import com.tinniestudio.worker.processor.VideoProcessingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoProcessingConsumerTest {

    @Mock VideoProcessingService processingService;
    @Mock RetryPublisher retryPublisher;

    private VideoProcessingConsumer consumer;

    private ProcessingJobEnvelope envelopeFor(UUID assetId, String jobId, int attempt) {
        MediaProcessingJobPayload payload = new MediaProcessingJobPayload();
        payload.setJobId(jobId);
        payload.setVideoAssetId(assetId);
        payload.setStorageKey("uploads/" + assetId + "/raw.mp4");
        ProcessingJobEnvelope envelope = new ProcessingJobEnvelope();
        envelope.setPayload(payload);
        envelope.setAttempt(attempt);
        return envelope;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        consumer = new VideoProcessingConsumer(processingService, retryPublisher);
    }

    @Test
    void schedulesRetryAndDoesNotMarkTerminallyFailedWhenAttemptsRemain() throws Exception {
        UUID assetId = UUID.randomUUID();
        ProcessingJobEnvelope envelope = envelopeFor(assetId, "job-" + assetId, 1);

        doThrow(new VideoProcessingService.RetryableProcessingException("boom", new RuntimeException("boom")))
            .when(processingService).process(envelope.getPayload());

        consumer.consume(envelope);

        verify(retryPublisher).retryOrFail(envelope, 1);
        verify(processingService, never()).markTerminallyFailed(any(), any(), any());
    }

    @Test
    void marksTerminallyFailedAndRethrowsWhenRetriesExhausted() throws Exception {
        UUID assetId = UUID.randomUUID();
        String jobId = "job-" + assetId;
        ProcessingJobEnvelope envelope = envelopeFor(assetId, jobId, RetryPublisher.MAX_ATTEMPTS);

        doThrow(new VideoProcessingService.RetryableProcessingException("boom", new RuntimeException("boom")))
            .when(processingService).process(envelope.getPayload());
        doThrow(new RetryPublisher.MaxAttemptsExceededException("exhausted"))
            .when(retryPublisher).retryOrFail(envelope, RetryPublisher.MAX_ATTEMPTS);

        assertThatThrownBy(() -> consumer.consume(envelope))
            .isInstanceOf(RetryPublisher.MaxAttemptsExceededException.class);

        verify(processingService).markTerminallyFailed(eq(assetId), eq(jobId), eq("boom"));
    }
}
```

- [ ] **Step 7: Run the new test to confirm it fails**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.consumer.VideoProcessingConsumerTest"`
Expected: FAIL — `VideoProcessingService.markTerminallyFailed(...)` is never called from the consumer yet.

- [ ] **Step 8: Update `VideoProcessingConsumer` to call `markTerminallyFailed` on exhausted retries**

```java
package com.tinniestudio.worker.consumer;

import com.tinniestudio.worker.config.RabbitConfig;
import com.tinniestudio.worker.dto.ProcessingJobEnvelope;
import com.tinniestudio.worker.processor.VideoProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoProcessingConsumer {

    private final VideoProcessingService processingService;
    private final RetryPublisher retryPublisher;

    @RabbitListener(queues = RabbitConfig.QUEUE_VIDEO_PROCESS, concurrency = "1-2")
    public void consume(ProcessingJobEnvelope envelope) {
        if (envelope.getPayload() == null) {
            log.error("Received malformed message with null payload, discarding");
            return;
        }
        String jobId = envelope.getPayload().getJobId();
        int attempt = Math.max(envelope.getAttempt(), 1);
        log.info("Consuming job={} attempt={}", jobId, attempt);

        try {
            processingService.process(envelope.getPayload());
        } catch (VideoProcessingService.RetryableProcessingException e) {
            log.warn("Retryable failure for job={} attempt={}: {}", jobId, attempt, e.getMessage());
            try {
                retryPublisher.retryOrFail(envelope, attempt);
            } catch (RetryPublisher.MaxAttemptsExceededException ex) {
                log.error("Job={} exhausted retries, marking terminally failed and routing to failed queue", jobId);
                processingService.markTerminallyFailed(
                    envelope.getPayload().getVideoAssetId(), jobId, e.getMessage());
                throw ex;
            }
        }
    }
}
```

- [ ] **Step 9: Run the consumer test to confirm it passes**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.consumer.VideoProcessingConsumerTest"`
Expected: PASS (2 tests)

- [ ] **Step 10: Write the combined regression test — retryable failure followed by a successful retry**

This is the integration-style test the spec calls for: it exercises `getOrCreateJob()`'s row-reuse (Task 2) and the no-false-terminal-skip fix (this task) together, end to end, through two sequential `process()` calls with the same `jobId`. Add inside the `process` nested class in `VideoProcessingServiceTest.java`:

```java
        @Test
        void retryableFailureFollowedBySuccessfulRetryActuallyReExecutesInsteadOfBeingSkipped() throws Exception {
            UUID assetId = UUID.randomUUID();
            VideoAsset asset = buildAsset(assetId);
            MediaProcessingJobPayload payload = buildPayload(assetId);

            // With the fix, a retryable failure never writes job.status="FAILED", so this guard
            // must keep returning false across both attempts — reproducing the original bug would
            // require it to flip true after attempt 1, which is exactly what the fix prevents.
            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "DONE")).thenReturn(false);
            when(processingJobRepo.existsByJobIdAndStatus(payload.getJobId(), "FAILED")).thenReturn(false);
            when(videoAssetRepo.findById(assetId)).thenReturn(Optional.of(asset));

            java.util.concurrent.atomic.AtomicReference<ProcessingJob> savedJob =
                new java.util.concurrent.atomic.AtomicReference<>();
            when(processingJobRepo.findByJobId(payload.getJobId()))
                .thenAnswer(inv -> Optional.ofNullable(savedJob.get()));
            when(processingJobRepo.save(any())).thenAnswer(inv -> {
                ProcessingJob j = inv.getArgument(0);
                savedJob.set(j);
                return j;
            });
            when(videoAssetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            stubDownloadCreatesFile(1_000L);

            FFprobeRunner.VideoMetadata meta = new FFprobeRunner.VideoMetadata(
                60, 1280, 720, "h264", 2_800_000L, true);
            // Attempt 1: ffprobe throws a retryable error. Attempt 2 (same jobId): succeeds.
            when(ffprobeRunner.probe(anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("transient ffprobe failure"))
                .thenReturn(meta);

            // Attempt 1
            assertThatThrownBy(() -> service.process(payload))
                .isInstanceOf(VideoProcessingService.RetryableProcessingException.class);

            assertThat(savedJob.get().getStatus())
                .as("a retryable failure must not write a terminal job status")
                .isNotEqualTo("FAILED");
            assertThat(savedJob.get().getErrorMessage()).contains("transient ffprobe failure");
            verify(videoAssetRepo, never()).save(argThat(a -> "FAILED".equals(a.getProcessingStatus())));
            ProcessingJob jobAfterAttempt1 = savedJob.get();

            // Attempt 2 — must actually re-run ffprobe/transcode, not be silently skipped.
            service.process(payload);

            verify(ffprobeRunner, times(2)).probe(anyString(), anyBoolean());
            assertThat(savedJob.get())
                .as("attempt 2 must reuse the same ProcessingJob row, not insert a duplicate")
                .isSameAs(jobAfterAttempt1);
            assertThat(savedJob.get().getStatus()).isEqualTo("DONE");
            assertThat(savedJob.get().getAttempt()).isEqualTo(2);
            verify(videoAssetRepo, atLeastOnce()).save(argThat(a -> "READY".equals(a.getProcessingStatus())));
        }
```

Add this import alongside the others at the top of the file (it's new to this file):

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 11: Run the test to confirm it passes**

Run: `./gradlew :media-worker:test --tests "com.tinniestudio.worker.processor.VideoProcessingServiceTest"`
Expected: PASS (all tests)

- [ ] **Step 12: Run the full media-worker suite**

Run: `./gradlew :media-worker:test`
Expected: PASS — every test green, including `VideoProcessingServiceTransactionBoundaryTest`.

- [ ] **Step 13: Commit**

```bash
git add media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java \
        media-worker/src/main/java/com/tinniestudio/worker/consumer/VideoProcessingConsumer.java \
        media-worker/src/test/java/com/tinniestudio/worker/processor/VideoProcessingServiceTest.java \
        media-worker/src/test/java/com/tinniestudio/worker/processor/VideoProcessingServiceTransactionBoundaryTest.java \
        media-worker/src/test/java/com/tinniestudio/worker/consumer/VideoProcessingConsumerTest.java
git commit -m "fix(media-worker): move terminal FAILED write off the retryable path so retries actually run"
```

---

## Task 4: `UploadConfig` — expose MIME/audio/duration constraints programmatically

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/upload/config/UploadConfig.java`

Adds the lookups the new `/uploads/constraints` endpoint (Task 5) needs, reusing the existing `ALLOWED_MIME_TYPES`/`MAX_BYTES` maps so the endpoint can never drift from what's actually enforced. Also duplicates the max-duration value, which currently exists only in media-worker's `WorkerProperties`.

- [ ] **Step 1: Add the new constant and two new methods**

In `UploadConfig.java`, add after the existing `MULTIPART_SESSION_TTL` constant:

```java
    // Must stay in sync with worker.processing.max-duration-seconds (media-worker's
    // WorkerProperties.Processing.maxDurationSeconds) — media-worker is the sole actual
    // enforcer (checked post-upload via ffprobe, during processing). This copy only powers
    // the /uploads/constraints endpoint so partner-web can warn before a multi-GB upload
    // that would otherwise only fail hours later.
    public static final int MAX_DURATION_SECONDS = 14_400; // 4 hours
```

And add after the existing `getMaxBytes` method:

```java
    public Set<String> getAllowedMimeTypes(UploadType type) {
        return ALLOWED_MIME_TYPES.getOrDefault(type, Set.of());
    }

    public boolean isAudioRequired(UploadType type) {
        return type == UploadType.RAW_VIDEO;
    }
```

- [ ] **Step 2: Compile to confirm there are no errors**

Run: `./gradlew :api-service:compileJava`
Expected: BUILD SUCCESSFUL (no test yet — Task 5 exercises these methods through the controller)

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/upload/config/UploadConfig.java
git commit -m "feat(api-service): expose UploadConfig's MIME/audio/duration constraints programmatically"
```

---

## Task 5: `GET /uploads/constraints` endpoint

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/UploadConstraintsResponse.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/upload/controller/UploadController.java`
- Create: `api-service/src/test/java/com/tinniestudio/api/modules/upload/controller/UploadControllerTest.java`

New authenticated (same as every other `/uploads/**` route — no `SecurityConfig.PUBLIC_ENDPOINTS` entry needed) endpoint returning the constraints `UploadConfig` already enforces, so partner-web never hardcodes numbers that can drift from what the server actually accepts.

- [ ] **Step 1: Write the failing test**

Create `api-service/src/test/java/com/tinniestudio/api/modules/upload/controller/UploadControllerTest.java`:

```java
package com.tinniestudio.api.modules.upload.controller;

import com.tinniestudio.api.modules.upload.config.UploadConfig;
import com.tinniestudio.api.modules.upload.dto.UploadConstraintsResponse;
import com.tinniestudio.api.modules.upload.service.UploadService;
import com.tinniestudio.api.shared.entity.DomainEnums.UploadType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class UploadControllerTest {

    @Mock UploadService uploadService;
    private final UploadConfig uploadConfig = new UploadConfig();
    private UploadController controller;

    @BeforeEach
    void setUp() {
        controller = new UploadController(uploadService, uploadConfig);
    }

    @Test
    void returnsRawVideoConstraintsWithAudioRequired() {
        ResponseEntity<UploadConstraintsResponse> resp = controller.getConstraints(UploadType.RAW_VIDEO);

        UploadConstraintsResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.allowedMimeTypes()).contains("video/mp4", "video/quicktime", "video/x-matroska");
        assertThat(body.maxFileSizeBytes()).isEqualTo(10L * 1024 * 1024 * 1024);
        assertThat(body.maxDurationSeconds()).isEqualTo(UploadConfig.MAX_DURATION_SECONDS);
        assertThat(body.audioRequired()).isTrue();
    }

    @Test
    void returnsTrailerConstraintsWithAudioNotRequired() {
        ResponseEntity<UploadConstraintsResponse> resp = controller.getConstraints(UploadType.TRAILER);

        UploadConstraintsResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.allowedMimeTypes()).containsExactlyInAnyOrder("video/mp4", "video/quicktime");
        assertThat(body.maxFileSizeBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(body.maxDurationSeconds()).isEqualTo(UploadConfig.MAX_DURATION_SECONDS);
        assertThat(body.audioRequired()).isFalse();
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails to compile**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.upload.controller.UploadControllerTest"`
Expected: FAIL — `UploadConstraintsResponse` doesn't exist yet, and `UploadController`'s constructor doesn't take a `UploadConfig`.

- [ ] **Step 3: Create the response DTO**

```java
package com.tinniestudio.api.modules.upload.dto;

import java.util.Set;

public record UploadConstraintsResponse(
    Set<String> allowedMimeTypes,
    long maxFileSizeBytes,
    int maxDurationSeconds,
    boolean audioRequired
) {}
```

- [ ] **Step 4: Add the endpoint to `UploadController`**

In `UploadController.java`, add the import and field, and the new endpoint method:

```java
import com.tinniestudio.api.modules.upload.config.UploadConfig;
import com.tinniestudio.api.modules.upload.dto.UploadConstraintsResponse;
```

```java
    private final UploadService uploadService;
    private final UploadConfig uploadConfig;
```

(the `@RequiredArgsConstructor` already on the class generates the matching 2-arg constructor the test uses)

```java
    @Operation(summary = "Get the server-enforced upload constraints for a given asset type, for client-side pre-upload guidance and validation")
    @GetMapping("/constraints")
    public ResponseEntity<UploadConstraintsResponse> getConstraints(@RequestParam UploadType assetType) {
        return ResponseEntity.ok(new UploadConstraintsResponse(
            uploadConfig.getAllowedMimeTypes(assetType),
            uploadConfig.getMaxBytes(assetType),
            UploadConfig.MAX_DURATION_SECONDS,
            uploadConfig.isAudioRequired(assetType)
        ));
    }
```

- [ ] **Step 5: Run the test to confirm it passes**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.upload.controller.UploadControllerTest"`
Expected: PASS (2 tests)

- [ ] **Step 6: Run the full api-service upload module tests**

Run: `./gradlew :api-service:test --tests "com.tinniestudio.api.modules.upload.*"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/UploadConstraintsResponse.java \
        api-service/src/main/java/com/tinniestudio/api/modules/upload/controller/UploadController.java \
        api-service/src/test/java/com/tinniestudio/api/modules/upload/controller/UploadControllerTest.java
git commit -m "feat(api-service): add GET /uploads/constraints endpoint for client-side upload guidance"
```

---

## Self-Review Notes

- **Spec coverage:** All three media-worker defects (duration-blind timeout, retry no-op, unique-constraint crash) map to Tasks 1–3. The backend half of format guidance (new endpoint + duplicated duration constant) maps to Tasks 4–5. Testing section's three media-worker asks (getOrCreateJob reuse, timeout-scaling calculation, retry-then-succeed integration test) are covered by Task 2 Step 1, Task 1 Step 16, and Task 3 Step 10 respectively. api-service's "unit test for the constraints endpoint" is Task 5.
- **Known pre-existing regression test correction:** `VideoProcessingServiceTransactionBoundaryTest`'s original assertion (asset ends up `FAILED` after a retryable error) encoded the *old, buggy* behavior this plan removes — Task 3 Step 1 corrects it to assert the new, correct behavior (asset stays `PROCESSING`; the `ProcessingJob` row carries the error) while preserving its original transactional-boundary intent (the write must survive the exception, not roll back).
- **Type/signature consistency:** `ProcessRunner.run(List<String>, long)` is consistent across `SystemProcessRunner`, `FFmpegRunner`, `FFprobeRunner`, and `StartupValidator` from Task 1 onward. `FFmpegRunner.transcode(...)`/`generateThumbnail(...)` consistently take a trailing `long timeoutSeconds` from Task 1 onward, matching every call site including the two new tests. `VideoProcessingService.markTerminallyFailed(UUID, String, String)` matches exactly between its Task 3 definition and `VideoProcessingConsumer`'s call site and `VideoProcessingConsumerTest`'s verification.
