# CDN-Fronted Upload & Playback Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the production upload CORS failure, make the backend the single source of truth for CDN URLs, and protect subscription-gated HLS video behind CloudFront signed cookies — while keeping image URLs and the upload mechanism itself unchanged.

**Architecture:** Three independent phases, each shippable on its own: (1) an ops-only fix (S3 bucket CORS + CloudFront OAC bucket policy — no application code), (2) a backend+frontend cleanup that removes three duplicated CDN-URL configs in favor of one backend-returned `cdnUrl`, (3) new CloudFront signed-cookie issuance wired into the two subscription-gated playback endpoints, consumed by the client-web HLS player.

**Tech Stack:** Spring Boot 3.3 / Java 21 (api-service), AWS SDK v2 (`software.amazon.awssdk:s3`, adding `:cloudfront`), JUnit 5 + Mockito + AssertJ, Next.js/TypeScript (tinniestudio-partner-web, tinniestudio-client-web), hls.js.

**Spec:** `docs/superpowers/specs/2026-10-10-cdn-upload-architecture-design.md`

---

## Task 1: [Ops] Apply S3 bucket CORS + CloudFront read-access bucket policy

No application code in this task — this unblocks production uploads immediately and must ship before any other task, independent of the rest of the plan.

**Files:** none (AWS CLI against the real `tls-prd-bucket`, and the MinIO dev equivalent).

- [ ] **Step 1: Write the bucket CORS policy**

Create `cors.json` (anywhere locally, not committed):

```json
{
  "CORSRules": [
    {
      "AllowedOrigins": ["https://tinniestudio.com", "https://*.tinniestudio.com"],
      "AllowedMethods": ["PUT", "GET", "HEAD"],
      "AllowedHeaders": ["Content-Type", "x-amz-*"],
      "ExposeHeaders": ["ETag"],
      "MaxAgeSeconds": 3600
    }
  ]
}
```

- [ ] **Step 2: Apply it to the production bucket**

```bash
aws s3api put-bucket-cors \
  --bucket tls-prd-bucket \
  --region eu-north-1 \
  --cors-configuration file://cors.json
```

- [ ] **Step 3: Verify the CORS fix**

```bash
curl -i -X OPTIONS "https://tls-prd-bucket.s3.eu-north-1.amazonaws.com/thumbnails/test/test.jpg" \
  -H "Origin: https://tinniestudio.com" \
  -H "Access-Control-Request-Method: PUT" \
  -H "Access-Control-Request-Headers: content-type"
```

Expected: `HTTP/1.1 200 OK` with an `Access-Control-Allow-Origin: https://tinniestudio.com` response header. If you instead see no `Access-Control-Allow-Origin` header, the CORS policy didn't apply — re-check the bucket name/region.

- [ ] **Step 4: Grant CloudFront (OAC) read access via the bucket policy**

Find your distribution's ARN first:

```bash
aws cloudfront list-distributions --query "DistributionList.Items[].{Id:Id,Domain:DomainName,Comment:Comment}"
```

Create `bucket-policy.json`, substituting your account id and the distribution id from the command above:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowCloudFrontServicePrincipal",
      "Effect": "Allow",
      "Principal": { "Service": "cloudfront.amazonaws.com" },
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::tls-prd-bucket/*",
      "Condition": {
        "StringEquals": { "AWS:SourceArn": "arn:aws:cloudfront::<ACCOUNT_ID>:distribution/<DISTRIBUTION_ID>" }
      }
    }
  ]
}
```

```bash
aws s3api put-bucket-policy --bucket tls-prd-bucket --policy file://bucket-policy.json
```

- [ ] **Step 5: Verify reads work through the CDN**

```bash
curl -i https://cdn.tinniestudio.com/thumbnails/<a-known-existing-key>.jpg
```

Expected: `HTTP/1.1 200 OK`, `Content-Type: image/jpeg`. A `403` here means either the bucket policy's `SourceArn` doesn't match your real distribution id, or the distribution's origin isn't actually configured with Origin Access Control pointed at this bucket — re-check the CloudFront console's origin settings before moving on; Task 8 later depends on this already working.

- [ ] **Step 6: Apply the equivalent CORS rule to MinIO (dev parity)**

```bash
mc cors set local/tinniestudio cors-dev.json
```

where `cors-dev.json` is the same shape as `cors.json` in Step 1, with `AllowedOrigins` swapped to `["http://localhost:3000"]` (or whatever `LOCAL_DEV_URL` resolves to in `.env`).

No commit for this task — it's pure AWS/MinIO-side config, not a code change.

---

## Task 2: [Backend] `CompleteUploadResponse` returns a CDN URL for every upload type

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/CompleteUploadResponse.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/upload/service/UploadService.java:120-232,240-270`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/upload/service/UploadServiceTest.java`

- [x] **Step 1: Fix the test fixture so a universal `appProperties.getCdn()` stub doesn't break existing tests**

Every `completeSession()` test except the SUBTITLE one currently never stubs `appProperties.getCdn()`. Once `completeSession()` computes a CDN URL unconditionally (Step 3), those tests would NPE on `null.getBaseUrl()`. Add a shared, lenient stub and remove the now-redundant local one.

In `api-service/src/test/java/com/tinniestudio/api/modules/upload/service/UploadServiceTest.java`, add a `@BeforeEach` right after the field declarations (before the `pendingSession` helper, so after line 63):

```java
    @BeforeEach
    void setUp() {
        AppProperties.Cdn cdn = new AppProperties.Cdn();
        cdn.setBaseUrl("https://cdn.test");
        lenient().when(appProperties.getCdn()).thenReturn(cdn);
    }
```

(`lenient()` is required here — without it, Mockito's strict-stubbing check fails every `createSession()` test in the same class, since those never call `appProperties.getCdn()` at all.)

Add the `@BeforeEach` import if not already present — check line 26 (`import org.junit.jupiter.api.*;`) already covers it via wildcard.

Then remove the now-duplicate local stub inside `completesSubtitleSession_createsSubtitleRow()` (currently lines 472-474):

```diff
-            AppProperties.Cdn cdn = new AppProperties.Cdn();
-            cdn.setBaseUrl("https://cdn.test");
-            when(appProperties.getCdn()).thenReturn(cdn);
             ArgumentCaptor<Subtitle> captor = ArgumentCaptor.forClass(Subtitle.class);
```

- [x] **Step 2: Run the full test class to confirm it's still green before changing any production code**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.upload.service.UploadServiceTest"
```

Expected: `BUILD SUCCESSFUL`, all existing tests pass (this step is pure fixture cleanup, no behavior change yet).

- [x] **Step 3: Write the failing test for the new `cdnUrl` field**

Add to the `CompleteSessionTests` nested class (near `completesThumbnailSession`):

```java
        @Test @DisplayName("returns a non-null cdnUrl for THUMBNAIL completion")
        void completesThumbnailSession_returnsCdnUrl() {
            UploadSession session = pendingSession(UploadType.THUMBNAIL, "thumbnails/uuid/thumb.jpg");
            when(uploadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(storageService.objectExists("thumbnails/uuid/thumb.jpg")).thenReturn(true);
            when(mediaFileRepository.save(any())).thenAnswer(inv -> {
                MediaFile f = inv.getArgument(0);
                f.setId(UUID.randomUUID());
                return f;
            });
            when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = uploadService.completeSession(userId, session.getId(), null);

            assertThat(result.cdnUrl()).isEqualTo("https://cdn.test/thumbnails/uuid/thumb.jpg");
        }
```

- [x] **Step 4: Run it to verify it fails**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.upload.service.UploadServiceTest" 2>&1 | grep -A5 "completesThumbnailSession_returnsCdnUrl"
```

Expected: compile error (`cdnUrl()` doesn't exist on `CompleteUploadResponse` yet) or, once it compiles against the 3-arg record, a test failure — either way, confirm it's red before implementing.

- [x] **Step 5: Add `cdnUrl` to the DTO**

`api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/CompleteUploadResponse.java`:

```diff
 package com.tinniestudio.api.modules.upload.dto;

 import java.util.UUID;

-public record CompleteUploadResponse(UUID mediaFileId, UUID videoAssetId, UUID subtitleId) {}
+public record CompleteUploadResponse(UUID mediaFileId, UUID videoAssetId, UUID subtitleId, String cdnUrl) {}
```

- [x] **Step 6: Compute `cdnUrl` once in `completeSession` and thread it through**

`api-service/src/main/java/com/tinniestudio/api/modules/upload/service/UploadService.java`, inside `completeSession` (around line 184, right after `MediaFile savedFile = mediaFileRepository.save(mediaFile);`):

```diff
         MediaFile savedFile = mediaFileRepository.save(mediaFile);

+        String cdnUrl = appProperties.getCdn().getBaseUrl() + "/" + session.getStorageKey();
+
         session.setUploadStatus(UploadStatus.COMPLETED);
```

And at the bottom of the same method (lines 227-231):

```diff
         } else if (type == UploadType.SUBTITLE) {
-            subtitleId = attachSubtitle(userId, session, body);
+            subtitleId = attachSubtitle(userId, session, body, cdnUrl);
         }

-        return new CompleteUploadResponse(savedFile.getId(), videoAssetId, subtitleId);
+        return new CompleteUploadResponse(savedFile.getId(), videoAssetId, subtitleId, cdnUrl);
     }
```

Update `attachSubtitle`'s signature and body (lines 240, 266) to take the already-computed URL instead of recomputing it:

```diff
-    private UUID attachSubtitle(UUID userId, UploadSession session, CompleteUploadRequest body) {
+    private UUID attachSubtitle(UUID userId, UploadSession session, CompleteUploadRequest body, String cdnUrl) {
```

```diff
-        subtitle.setFileUrl(appProperties.getCdn().getBaseUrl() + "/" + session.getStorageKey());
+        subtitle.setFileUrl(cdnUrl);
```

- [x] **Step 7: Run the test class again to verify everything passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.upload.service.UploadServiceTest"
```

Expected: `BUILD SUCCESSFUL` — the new test passes, and all 20+ pre-existing tests in this class (RAW_VIDEO, TRAILER, multipart, SUBTITLE, error paths) still pass unchanged thanks to Step 1's fixture fix.

- [x] **Step 8: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/CompleteUploadResponse.java \
        api-service/src/main/java/com/tinniestudio/api/modules/upload/service/UploadService.java \
        api-service/src/test/java/com/tinniestudio/api/modules/upload/service/UploadServiceTest.java
git commit -m "feat(upload): return cdnUrl in CompleteUploadResponse for every upload type"
```

---

## Task 3: [Backend] Remove the duplicate `stripe.cdn-base-url` config

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/config/StripeProperties.java`
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/user/service/UserProfileServiceImpl.java:144`
- Modify: `api-service/src/main/resources/application.yml:175`
- Test: `api-service/src/test/java/com/tinniestudio/api/user/service/UserProfileServiceTest.java`

- [x] **Step 1: Swap the test fixture's mock — `confirmAvatarUpload()` has no existing test to update**

Checked: there is no `confirmAvatarUpload` test anywhere in `UserProfileServiceTest.java` today — `stripeProperties` (line 47) is declared only so `@InjectMocks` can construct the service, never actually stubbed or verified. Swap the field for an `AppProperties` mock:

```diff
-import com.tinniestudio.api.shared.config.StripeProperties;
+import com.tinniestudio.api.shared.config.AppProperties;
```

```diff
-    @Mock private StripeProperties stripeProperties;
+    @Mock private AppProperties appProperties;
```

- [x] **Step 2: Write the new failing test for `confirmAvatarUpload()`**

Add this new nested class after `AvatarUrl` (after line 211, before the next `@Nested` block):

```java
    @Nested
    @DisplayName("confirmAvatarUpload()")
    class ConfirmAvatarUpload {

        @Test
        @DisplayName("builds the avatar URL from app.cdn.base-url and persists it")
        void buildsUrlFromAppCdnBaseUrl_andPersists() {
            AvatarConfirmRequest request = new AvatarConfirmRequest();
            request.setStorageKey("avatars/" + userId + "/photo.jpg");
            when(storageService.objectExists(request.getStorageKey())).thenReturn(true);
            AppProperties.Cdn cdn = new AppProperties.Cdn();
            cdn.setBaseUrl("https://cdn.test");
            when(appProperties.getCdn()).thenReturn(cdn);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            String result = service.confirmAvatarUpload(userId, request);

            assertThat(result).isEqualTo("https://cdn.test/avatars/" + userId + "/photo.jpg");
            assertThat(user.getAvatarUrl()).isEqualTo(result);
        }

        @Test
        @DisplayName("throws BadRequestException when the object isn't in storage yet")
        void objectMissing_throws() {
            AvatarConfirmRequest request = new AvatarConfirmRequest();
            request.setStorageKey("avatars/" + userId + "/photo.jpg");
            when(storageService.objectExists(request.getStorageKey())).thenReturn(false);

            assertThatThrownBy(() -> service.confirmAvatarUpload(userId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Please upload the file before confirming");
        }
    }
```

Add the `AvatarConfirmRequest` import if not already covered — line 7's `import com.tinniestudio.api.modules.user.dto.*;` wildcard already covers it.

- [x] **Step 3: Run the tests to verify they fail**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.user.service.UserProfileServiceTest"
```

Expected: compile error — `UserProfileServiceImpl` still depends on `StripeProperties`, not `AppProperties`, so `@InjectMocks` can't fully satisfy the constructor with only the new mock in place (confirms red before implementing).

- [x] **Step 4: Swap `StripeProperties` for `AppProperties` in `UserProfileServiceImpl`**

`api-service/src/main/java/com/tinniestudio/api/modules/user/service/UserProfileServiceImpl.java`:

```diff
-import com.tinniestudio.api.shared.config.StripeProperties;
+import com.tinniestudio.api.shared.config.AppProperties;
```

Find the `StripeProperties` field declaration (same block as the other `private final` fields, lines 48-50 area) and swap it:

```diff
-    private final StripeProperties stripeProperties;
+    private final AppProperties appProperties;
```

Update `confirmAvatarUpload` (line 144):

```diff
-        String cdnUrl = stripeProperties.getCdnBaseUrl() + "/" + request.getStorageKey();
+        String cdnUrl = appProperties.getCdn().getBaseUrl() + "/" + request.getStorageKey();
```

(`@RequiredArgsConstructor` regenerates the constructor automatically — no manual constructor edit needed.)

- [x] **Step 5: Remove the now-unused `cdnBaseUrl` field from `StripeProperties`**

`api-service/src/main/java/com/tinniestudio/api/shared/config/StripeProperties.java`:

```diff
     private String secretKey;
     private String webhookSecret;
-    private String cdnBaseUrl;
```

- [x] **Step 6: Remove the duplicate config line**

`api-service/src/main/resources/application.yml` (line 175):

```diff
 stripe:
   secret-key: ${STRIPE_SECRET_KEY:}
   webhook-secret: ${STRIPE_WEBHOOK_SECRET:}
-  cdn-base-url: ${CDN_BASE_URL:http://localhost:9000}
```

- [x] **Step 7: Run the full test class to verify it passes**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.user.service.UserProfileServiceTest"
```

Expected: `BUILD SUCCESSFUL`, including the two new `confirmAvatarUpload()` tests.

- [x] **Step 8: Run the full api-service test suite to catch any other `StripeProperties.cdnBaseUrl`/`stripeProperties` reference**

```bash
./gradlew :api-service:test
```

Expected: `BUILD SUCCESSFUL`. If anything else referenced `StripeProperties.getCdnBaseUrl()`, this fails loudly with a compile error naming the file — fix it the same way (swap to `appProperties.getCdn().getBaseUrl()`) before proceeding.

- [x] **Step 9: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/config/StripeProperties.java \
        api-service/src/main/java/com/tinniestudio/api/modules/user/service/UserProfileServiceImpl.java \
        api-service/src/main/resources/application.yml \
        api-service/src/test/java/com/tinniestudio/api/user/service/UserProfileServiceTest.java
git commit -m "refactor(user): use app.cdn.base-url for avatar URLs, drop duplicate stripe.cdn-base-url"
```

---

## Task 4: [Frontend: partner-web] Consume the backend's `cdnUrl`, delete the frontend's own `CDN_BASE_URL`

**Files:**
- Modify: `tinniestudio-partner-web/src/types/upload.type.ts:64-75`
- Modify: `tinniestudio-partner-web/src/app/api/uploads/[sessionId]/complete/route.ts`
- Modify: `tinniestudio-partner-web/src/schema/env.schema.ts`
- Modify: `tinniestudio-partner-web/src/config/env.config.ts`
- Modify: `tinniestudio-partner-web/.env.example`

- [x] **Step 1: Add `cdnUrl` to the backend-response type**

`tinniestudio-partner-web/src/types/upload.type.ts`:

```diff
 // What the backend actually returns from POST /uploads/{id}/complete — no URL,
 // by design (see docs/BACKEND-API-REFERENCE.md).
 export interface CompleteUploadResponse {
   mediaFileId: string | null;
   videoAssetId: string | null;
   subtitleId: string | null;
+  cdnUrl: string | null;
 }

-// What our own BFF route returns instead — CompleteUploadResponse plus the
-// url it derives server-side from CDN_BASE_URL + storageKey. Not a backend
-// DTO; exists only on this side of the BFF boundary.
+// What our own BFF route returns — CompleteUploadResponse's cdnUrl, renamed
+// to `url` for the client-side upload hook's existing contract.
 export type CompleteUploadResult = CompleteUploadResponse & { url: string };
```

- [x] **Step 2: Use the backend's `cdnUrl` in the BFF route instead of reconstructing it**

`tinniestudio-partner-web/src/app/api/uploads/[sessionId]/complete/route.ts`:

```diff
-import { completeUploadSessionDal } from "@/features/uploads/dal/complete-session";
-import { getCdnBaseUrl } from "@/config/env.config";
-import { getCookieHeader } from "@/lib/server/bff";
+import { completeUploadSessionDal } from "@/features/uploads/dal/complete-session";
+import { getCookieHeader } from "@/lib/server/bff";
```

```diff
   const { storageKey, ...backendPayload } = parsed.data;
   const resp = await completeUploadSessionDal(getCookieHeader(req), sessionId, backendPayload);

   if (!resp.success) {
     return Response.json(resp, { status: resp.status ?? 500 });
   }

-  // The backend's CompleteUploadResponse carries no URL (see
-  // docs/BACKEND-API-REFERENCE.md) — derive it here, mirroring the backend's
-  // own UserProfileServiceImpl.confirmAvatarUpload() pattern.
   const result: CompleteUploadResult = {
     mediaFileId: resp.data?.mediaFileId ?? null,
     videoAssetId: resp.data?.videoAssetId ?? null,
     subtitleId: resp.data?.subtitleId ?? null,
-    url: `${getCdnBaseUrl()}/${storageKey}`,
+    url: resp.data?.cdnUrl ?? "",
   };
   return Response.json({ success: true, data: result }, { status: resp.status ?? 200 });
```

Note: `storageKey` destructured from `parsed.data` above is now unused in this function — remove it from the destructure too:

```diff
-  const { storageKey, ...backendPayload } = parsed.data;
-  const resp = await completeUploadSessionDal(getCookieHeader(req), sessionId, backendPayload);
+  const { ...backendPayload } = parsed.data;
+  const resp = await completeUploadSessionDal(getCookieHeader(req), sessionId, backendPayload);
```

Check the `completeSchema` Zod object a few lines above — if `storageKey` is only used for this destructure (confirm via `grep -n storageKey` in this file after the edit) and the backend doesn't need it in `backendPayload` either, leave the schema field as-is (it's still required input validation — the backend's `complete` endpoint doesn't take a `storageKey` body field at all, it reads it from the session; this field exists purely so the BFF could derive a URL, which Step 2 just removed the need for). This is a candidate for removing `storageKey` from `completeSchema`/`BffCompleteUploadRequest` entirely, but that touches the client-side caller contract too — leave it in scope for this task only if `npm run type-check` (Step 4) doesn't surface it as dead; otherwise leave the field in place rather than widening this task.

- [x] **Step 3: Remove the now-unused `CDN_BASE_URL` env var**

`tinniestudio-partner-web/src/schema/env.schema.ts`:

```diff
-  // Server-only — the BFF's uploads/[sessionId]/complete route derives the final
-  // logoUrl/posterUrl/thumbnailUrl itself as `${CDN_BASE_URL}/${storageKey}`,
-  // mirroring the backend's own UserProfileServiceImpl.confirmAvatarUpload()
-  // pattern (see stripe.cdn-base-url / CDN_BASE_URL in application.yml — same
-  // env var name, same default, deliberately kept in sync). Only the generic
-  // presigned-upload flow needs this; the dedicated avatar endpoint already
-  // returns its own URL from the backend, no client-side construction needed.
-  CDN_BASE_URL: z
-    .string()
-    .trim()
-    .min(1, "CDN_BASE_URL is required")
-    .url("CDN_BASE_URL must be a valid URL")
-    .default("http://localhost:9000"),
   NODE_ENV: z.enum(["development", "production", "test"]).default("development"),
```

`tinniestudio-partner-web/src/config/env.config.ts`:

```diff
-export function getCdnBaseUrl(): string {
-  return getEnv().CDN_BASE_URL;
-}
-
 export function getNodeEnv(): Env["NODE_ENV"] {
```

`tinniestudio-partner-web/.env.example`:

```diff
-
-# Server-only — base URL the BFF derives storageKey-based URLs (partner logo,
-# content poster/thumbnail) from: the final url is `${CDN_BASE_URL}/${storageKey}`.
-# MUST include the storage bucket path segment — this has to be
-# `<app.storage.public-endpoint>/<app.storage.bucket>` from the backend's own
-# config (application.yml / its .env), NOT just the bare MinIO endpoint. Getting
-# this wrong doesn't error at upload time — it silently persists a broken url
-# (pointing at a different, non-public bucket) into content.thumbnail_url etc.,
-# which only surfaces later as an AccessDenied when a browser tries to load it.
-CDN_BASE_URL=http://localhost:9000/tinniestudio
```

- [x] **Step 4: Type-check and lint**

```bash
cd tinniestudio-partner-web
npm run type-check
npm run lint
```

Expected: both pass with no errors. `type-check` specifically confirms nothing else in the codebase still imports `getCdnBaseUrl` or references `Env["CDN_BASE_URL"]`.

- [x] **Step 5: Manual verification**

Start the dev server (`npm run dev`), log in as a partner, upload a content thumbnail or partner logo through the existing UI, and confirm the image renders immediately after upload (same as before — the visible behavior doesn't change, only where the URL came from).

- [x] **Step 6: Commit**

```bash
cd tinniestudio-partner-web
git add src/types/upload.type.ts src/app/api/uploads/[sessionId]/complete/route.ts \
        src/schema/env.schema.ts src/config/env.config.ts .env.example
git commit -m "refactor(uploads): consume backend cdnUrl instead of reconstructing from frontend CDN_BASE_URL"
```

---

## Task 5: [Backend] Add the CloudFront SDK dependency and signing config

**Files:**
- Modify: `api-service/build.gradle:60-62`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/config/AppProperties.java:123-125`
- Modify: `api-service/src/main/resources/application.yml:142-143`
- Modify: `.env` / `.env.prod` (not committed — see Step 4)

- [x] **Step 1: Add the dependency**

`api-service/build.gradle`:

```diff
     // AWS SDK v2 (S3-compatible — works with MinIO, S3, R2)
     implementation platform('software.amazon.awssdk:bom:2.25.0')
     implementation 'software.amazon.awssdk:s3'
+    implementation 'software.amazon.awssdk:cloudfront'
```

- [x] **Step 2: Verify it resolves**

```bash
./gradlew :api-service:dependencies --configuration compileClasspath | grep cloudfront
```

Expected: a line showing `software.amazon.awssdk:cloudfront:2.25.0` (version pinned by the existing BOM).

- [x] **Step 3: Extend `AppProperties.Cdn` with signing config**

`api-service/src/main/java/com/tinniestudio/api/shared/config/AppProperties.java`:

```diff
     @Getter
     @Setter
     public static class Cdn {
         private String baseUrl = "http://localhost:3000";
+        // When false (the default — correct for local/dev, which has no real CloudFront to
+        // validate a signed cookie against), video manifest/segment URLs are plain CDN links,
+        // identical to images. Only ever true in production.
+        private boolean signingEnabled = false;
+        private String cloudfrontKeyPairId;
+        // PEM-encoded PKCS#8 private key, injected via secret/env — never committed. See
+        // CloudFrontSigningService for the PEM parsing and the one-time AWS setup this depends on.
+        private String cloudfrontPrivateKey;
+        private long signedCookieTtlSeconds = 21_600L; // 6h
+        // Empty means a host-only cookie (fine for same-host local testing). Production sets this
+        // to ".tinniestudio.com" so the cookie set by the API's own domain is also sent on requests
+        // to cdn.tinniestudio.com.
+        private String cookieDomain = "";
     }
```

- [x] **Step 4: Wire the new config into `application.yml`**

`api-service/src/main/resources/application.yml`:

```diff
   cdn:
     base-url: ${CDN_BASE_URL:http://localhost:3000}
+    signing-enabled: ${CDN_SIGNING_ENABLED:false}
+    cloudfront-key-pair-id: ${CLOUDFRONT_KEY_PAIR_ID:}
+    cloudfront-private-key: ${CLOUDFRONT_PRIVATE_KEY:}
+    signed-cookie-ttl-seconds: ${CDN_SIGNED_COOKIE_TTL_SECONDS:21600}
+    cookie-domain: ${CDN_COOKIE_DOMAIN:}
```

Dev's `.env` needs no changes — every new var defaults to signing-disabled behavior. Add to `.env.prod` (not committed, edit directly on the deployment host or secret store) once Task 8's AWS setup produces real values:

```
CDN_SIGNING_ENABLED=true
CLOUDFRONT_KEY_PAIR_ID=<from Task 8>
CLOUDFRONT_PRIVATE_KEY=<PEM contents from Task 8, newlines preserved>
CDN_SIGNED_COOKIE_TTL_SECONDS=21600
CDN_COOKIE_DOMAIN=.tinniestudio.com
```

- [x] **Step 5: Confirm the app still boots with the new, all-defaulted config**

```bash
./gradlew :api-service:test --tests "*ApplicationContext*"
```

(Or any existing Spring context smoke test — the goal is confirming `application.yml`'s new keys don't break property binding. If no such test exists, run `./gradlew :api-service:bootRun` locally for a few seconds and confirm no startup exception, then stop it.)

- [x] **Step 6: Commit**

```bash
git add api-service/build.gradle api-service/src/main/java/com/tinniestudio/api/shared/config/AppProperties.java \
        api-service/src/main/resources/application.yml
git commit -m "feat(cdn): add CloudFront SDK dependency and signed-cookie configuration"
```

---

## Task 6: [Backend] `CloudFrontSigningService`

**Files:**
- Create: `api-service/src/main/java/com/tinniestudio/api/shared/storage/CloudFrontSigningService.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/shared/storage/CloudFrontSigningServiceTest.java`

- [x] **Step 1: Write the failing tests**

Create `api-service/src/test/java/com/tinniestudio/api/shared/storage/CloudFrontSigningServiceTest.java`:

```java
package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudfront.cookie.CookiesForCustomPolicy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudFrontSigningService")
class CloudFrontSigningServiceTest {

    private String generateTestPrivateKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String base64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
    }

    @Test
    @DisplayName("returns empty when signing is disabled (dev default)")
    void returnsEmptyWhenSigningDisabled() {
        AppProperties appProperties = new AppProperties();
        appProperties.getCdn().setSigningEnabled(false);
        CloudFrontSigningService service = new CloudFrontSigningService(appProperties);

        Optional<CookiesForCustomPolicy> result = service.signVideoAccess();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns three signed cookie header values when signing is enabled")
    void returnsSignedCookiesWhenSigningEnabled() throws Exception {
        AppProperties appProperties = new AppProperties();
        appProperties.getCdn().setBaseUrl("https://cdn.tinniestudio.com");
        appProperties.getCdn().setSigningEnabled(true);
        appProperties.getCdn().setCloudfrontKeyPairId("APKATEST123");
        appProperties.getCdn().setCloudfrontPrivateKey(generateTestPrivateKeyPem());
        appProperties.getCdn().setSignedCookieTtlSeconds(21_600L);
        CloudFrontSigningService service = new CloudFrontSigningService(appProperties);

        Optional<CookiesForCustomPolicy> result = service.signVideoAccess();

        assertThat(result).isPresent();
        CookiesForCustomPolicy cookies = result.get();
        assertThat(cookies.policyHeaderValue()).startsWith("CloudFront-Policy=");
        assertThat(cookies.signatureHeaderValue()).startsWith("CloudFront-Signature=");
        assertThat(cookies.keyPairIdHeaderValue()).isEqualTo("CloudFront-Key-Pair-Id=APKATEST123");
    }

    @Test
    @DisplayName("exposes configured cookie domain and TTL for the caller to apply as cookie attributes")
    void exposesCookieDomainAndMaxAge() {
        AppProperties appProperties = new AppProperties();
        appProperties.getCdn().setCookieDomain(".tinniestudio.com");
        appProperties.getCdn().setSignedCookieTtlSeconds(21_600L);
        CloudFrontSigningService service = new CloudFrontSigningService(appProperties);

        assertThat(service.cookieDomain()).isEqualTo(".tinniestudio.com");
        assertThat(service.cookieMaxAgeSeconds()).isEqualTo(21_600L);
    }
}
```

- [x] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.shared.storage.CloudFrontSigningServiceTest"
```

Expected: compile failure — `CloudFrontSigningService` doesn't exist yet.

- [x] **Step 3: Implement the service**

Create `api-service/src/main/java/com/tinniestudio/api/shared/storage/CloudFrontSigningService.java`:

```java
package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.cookie.CookiesForCustomPolicy;
import software.amazon.awssdk.services.cloudfront.model.CustomSignerRequest;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Issues CloudFront signed cookies gating access to {@code processed/*} (all HLS manifests and
 * segments, across every video) to viewers who've just passed the existing subscription check —
 * see PlaybackController. Pure local RSA signing, no network call, same reasoning as
 * StorageServiceConfig's S3Presigner: the private key material never leaves this process.
 */
@Service
@RequiredArgsConstructor
public class CloudFrontSigningService {

    private final AppProperties appProperties;
    private final CloudFrontUtilities cloudFrontUtilities = CloudFrontUtilities.create();

    public Optional<CookiesForCustomPolicy> signVideoAccess() {
        AppProperties.Cdn cdn = appProperties.getCdn();
        if (!cdn.isSigningEnabled()) {
            return Optional.empty();
        }
        CustomSignerRequest request = CustomSignerRequest.builder()
            .resourceUrl(cdn.getBaseUrl() + "/processed/*")
            .privateKey(loadPrivateKey(cdn.getCloudfrontPrivateKey()))
            .keyPairId(cdn.getCloudfrontKeyPairId())
            .expirationDate(Instant.now().plusSeconds(cdn.getSignedCookieTtlSeconds()))
            .build();
        return Optional.of(cloudFrontUtilities.getCookiesForCustomPolicy(request));
    }

    public String cookieDomain() {
        return appProperties.getCdn().getCookieDomain();
    }

    public long cookieMaxAgeSeconds() {
        return appProperties.getCdn().getSignedCookieTtlSeconds();
    }

    private PrivateKey loadPrivateKey(String pem) {
        try {
            String sanitized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(sanitized);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to parse CLOUDFRONT_PRIVATE_KEY — expected a PKCS#8 PEM-encoded RSA private key " +
                "(convert a traditional OpenSSL key with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem)",
                e);
        }
    }
}
```

- [x] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.shared.storage.CloudFrontSigningServiceTest"
```

Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [x] **Step 5: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/storage/CloudFrontSigningService.java \
        api-service/src/test/java/com/tinniestudio/api/shared/storage/CloudFrontSigningServiceTest.java
git commit -m "feat(cdn): add CloudFrontSigningService for signed video-access cookies"
```

---

## Task 7: [Backend] Wire signed cookies into the subscription-gated playback endpoints

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/modules/playback/controller/PlaybackController.java`
- Test: `api-service/src/test/java/com/tinniestudio/api/modules/playback/controller/PlaybackControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `PlaybackControllerTest.java`:

```diff
     @MockBean private PlaybackService playbackService;
     @MockBean private JwtTokenProvider jwtTokenProvider;
     @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
     @MockBean private UserDetailsServiceImpl userDetailsService;
+    @MockBean private com.tinniestudio.api.shared.storage.CloudFrontSigningService cloudFrontSigningService;
```

Add two new test methods (near `getContentManifest_returnsManifest`):

```java
    @Test
    @DisplayName("GET /playback/manifest/content/{contentId} sets no CloudFront cookies when signing is disabled (dev default)")
    @WithMockUser(username = USER_ID, roles = "USER")
    void getContentManifest_noCookies_whenSigningDisabled() throws Exception {
        UUID contentId = UUID.randomUUID();
        PlaybackManifestResponse manifest = new PlaybackManifestResponse(
            "http://cdn.test/manifest.m3u8", List.of(), 120, 3600);
        when(playbackService.getContentManifest(any(org.springframework.security.core.userdetails.UserDetails.class), any(UUID.class)))
            .thenReturn(manifest);
        // cloudFrontSigningService.signVideoAccess() is left unstubbed — Mockito's default
        // answer for an Optional-returning method is Optional.empty(), matching real
        // app.cdn.signing-enabled=false behavior.

        mockMvc.perform(getWithContext("/playback/manifest/content/" + contentId))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("GET /playback/manifest/content/{contentId} sets 3 CloudFront signed cookies when signing is enabled")
    @WithMockUser(username = USER_ID, roles = "USER")
    void getContentManifest_setsCloudFrontCookies_whenSigningEnabled() throws Exception {
        UUID contentId = UUID.randomUUID();
        PlaybackManifestResponse manifest = new PlaybackManifestResponse(
            "http://cdn.test/manifest.m3u8", List.of(), 120, 3600);
        when(playbackService.getContentManifest(any(org.springframework.security.core.userdetails.UserDetails.class), any(UUID.class)))
            .thenReturn(manifest);

        com.tinniestudio.api.shared.config.AppProperties realProps = new com.tinniestudio.api.shared.config.AppProperties();
        realProps.getCdn().setSigningEnabled(true);
        realProps.getCdn().setBaseUrl("https://cdn.tinniestudio.com");
        realProps.getCdn().setCloudfrontKeyPairId("APKATEST123");
        realProps.getCdn().setCloudfrontPrivateKey(generateTestPrivateKeyPem());
        com.tinniestudio.api.shared.storage.CloudFrontSigningService realSigningService =
            new com.tinniestudio.api.shared.storage.CloudFrontSigningService(realProps);
        when(cloudFrontSigningService.signVideoAccess()).thenReturn(realSigningService.signVideoAccess());
        when(cloudFrontSigningService.cookieDomain()).thenReturn("");
        when(cloudFrontSigningService.cookieMaxAgeSeconds()).thenReturn(21_600L);

        mockMvc.perform(getWithContext("/playback/manifest/content/" + contentId))
            .andExpect(status().isOk())
            .andExpect(header().stringValues("Set-Cookie",
                org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("CloudFront-Policy="))))
            .andExpect(header().stringValues("Set-Cookie",
                org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("CloudFront-Signature="))))
            .andExpect(header().stringValues("Set-Cookie",
                org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("CloudFront-Key-Pair-Id="))));
    }

    private String generateTestPrivateKeyPem() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair keyPair = generator.generateKeyPair();
        String base64 = java.util.Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.controller.PlaybackControllerTest"
```

Expected: the "disabled" test passes already (no code change needed for that case), but the "enabled" test fails — no cookies are set yet since the controller doesn't call `cloudFrontSigningService` at all.

- [ ] **Step 3: Wire the signing service into the controller**

`api-service/src/main/java/com/tinniestudio/api/modules/playback/controller/PlaybackController.java`:

```diff
 package com.tinniestudio.api.modules.playback.controller;

+import com.tinniestudio.api.shared.storage.CloudFrontSigningService;
 import com.tinniestudio.api.shared.security.CurrentUser;
 import com.tinniestudio.api.modules.playback.dto.*;
 import com.tinniestudio.api.modules.playback.service.PlaybackService;
+import jakarta.servlet.http.HttpServletResponse;
 import io.swagger.v3.oas.annotations.Operation;
 import io.swagger.v3.oas.annotations.tags.Tag;
 import jakarta.validation.Valid;
 import lombok.RequiredArgsConstructor;
+import org.springframework.http.HttpHeaders;
 import org.springframework.http.ResponseEntity;
+import software.amazon.awssdk.services.cloudfront.cookie.CookiesForCustomPolicy;
 import org.springframework.security.core.annotation.AuthenticationPrincipal;
 import org.springframework.security.core.userdetails.UserDetails;
 import org.springframework.web.bind.annotation.*;

 import java.util.List;
 import java.util.Map;
 import java.util.UUID;

 @Tag(name = "Playback", description = "Playback access, manifests, and progress tracking")
 @RestController
 @RequestMapping("/playback")
 @RequiredArgsConstructor
 public class PlaybackController {

     private final PlaybackService playbackService;
+    private final CloudFrontSigningService cloudFrontSigningService;
```

```diff
     @Operation(summary = "Get HLS manifest for a movie or standalone content")
     @GetMapping("/manifest/content/{contentId}")
     public ResponseEntity<PlaybackManifestResponse> getContentManifest(
             @AuthenticationPrincipal UserDetails principal,
-            @PathVariable UUID contentId) {
-        return ResponseEntity.ok(playbackService.getContentManifest(principal, contentId));
+            @PathVariable UUID contentId,
+            HttpServletResponse response) {
+        PlaybackManifestResponse manifest = playbackService.getContentManifest(principal, contentId);
+        applySignedCookiesIfEnabled(response);
+        return ResponseEntity.ok(manifest);
     }
```

```diff
     @Operation(summary = "Get HLS manifest for a specific episode")
     @GetMapping("/manifest/episode/{episodeId}")
     public ResponseEntity<PlaybackManifestResponse> getEpisodeManifest(
             @AuthenticationPrincipal UserDetails principal,
-            @PathVariable UUID episodeId) {
-        return ResponseEntity.ok(playbackService.getEpisodeManifest(principal, episodeId));
+            @PathVariable UUID episodeId,
+            HttpServletResponse response) {
+        PlaybackManifestResponse manifest = playbackService.getEpisodeManifest(principal, episodeId);
+        applySignedCookiesIfEnabled(response);
+        return ResponseEntity.ok(manifest);
     }
```

Add the two private helpers at the bottom of the class, right before the final closing `}`:

```java
    private void applySignedCookiesIfEnabled(HttpServletResponse response) {
        cloudFrontSigningService.signVideoAccess().ifPresent(cookies -> applyCloudFrontCookies(response, cookies));
    }

    private void applyCloudFrontCookies(HttpServletResponse response, CookiesForCustomPolicy cookies) {
        String attributes = cookieAttributes();
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.policyHeaderValue() + attributes);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.signatureHeaderValue() + attributes);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.keyPairIdHeaderValue() + attributes);
    }

    private String cookieAttributes() {
        StringBuilder sb = new StringBuilder("; Path=/; Max-Age=")
            .append(cloudFrontSigningService.cookieMaxAgeSeconds())
            .append("; Secure; HttpOnly; SameSite=Lax");
        String domain = cloudFrontSigningService.cookieDomain();
        if (domain != null && !domain.isBlank()) {
            sb.append("; Domain=").append(domain);
        }
        return sb.toString();
    }
```

No change to `getTrailerManifest`/`getSeasonTrailerManifest`/`getEpisodeTrailerManifest`/`checkAccess`/`recordProgress`/`getContinueWatching` — these stay exactly as they are.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :api-service:test --tests "com.tinniestudio.api.modules.playback.controller.PlaybackControllerTest"
```

Expected: `BUILD SUCCESSFUL`, both new tests pass, and every pre-existing test in this class still passes unchanged.

- [ ] **Step 5: Run the full backend test suite**

```bash
./gradlew :api-service:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/playback/controller/PlaybackController.java \
        api-service/src/test/java/com/tinniestudio/api/modules/playback/controller/PlaybackControllerTest.java
git commit -m "feat(playback): set CloudFront signed cookies on subscription-gated manifest endpoints"
```

---

## Task 8: [Ops] One-time CloudFront key pair + key group setup

Depends on Task 1 (CloudFront already reading from the bucket) being verified first, since this reuses the same distribution. Do this before flipping `CDN_SIGNING_ENABLED=true` in production.

**Files:** none.

- [ ] **Step 1: Generate the RSA key pair**

```bash
openssl genrsa -out cf-private-key-pkcs1.pem 2048
openssl rsa -pubout -in cf-private-key-pkcs1.pem -out cf-public-key.pem
```

- [ ] **Step 2: Convert the private key to PKCS#8 (required by `CloudFrontSigningService`'s parser)**

```bash
openssl pkcs8 -topk8 -nocrypt -in cf-private-key-pkcs1.pem -out cf-private-key.pem
```

Verify it now starts with `-----BEGIN PRIVATE KEY-----` (not `-----BEGIN RSA PRIVATE KEY-----` — the latter is the old PKCS#1 format Task 6's `KeyFactory`/`PKCS8EncodedKeySpec` parser can't read):

```bash
head -1 cf-private-key.pem
```

Expected: `-----BEGIN PRIVATE KEY-----`.

- [ ] **Step 3: Create the CloudFront public key and key group**

In the CloudFront console: **Public keys** → create one from `cf-public-key.pem`. Then **Key groups** → create one containing that public key. Note the key group's associated public key's **Key ID** (shown next to the public key in the console) — this is `CLOUDFRONT_KEY_PAIR_ID`.

- [ ] **Step 4: Restrict viewer access on the video cache behavior**

In the distribution's behaviors, find (or create) the one matching `/processed/*`. Set **Restrict viewer access** to **Yes**, and select the key group from Step 3 as a trusted key group.

- [ ] **Step 5: Add a credentialed-CORS response headers policy to that same behavior**

Create (or reuse) a response headers policy with CORS enabled: `Access-Control-Allow-Credentials: true`, `Access-Control-Allow-Origin: https://tinniestudio.com` (the exact origin — credentialed CORS forbids `*`), `Access-Control-Allow-Methods: GET, HEAD, OPTIONS`. Attach it to the `/processed/*` behavior.

- [ ] **Step 6: Store the secrets and flip the flag**

Put `cf-private-key.pem`'s contents into `CLOUDFRONT_PRIVATE_KEY` and the Key ID into `CLOUDFRONT_KEY_PAIR_ID` in the production secret store (same place `JWT_ACCESS_SECRET` already lives). Set `CDN_SIGNING_ENABLED=true` in `.env.prod`. Deploy.

- [ ] **Step 7: Verify end-to-end**

```bash
# Without cookies — should be denied now that viewer access is restricted:
curl -i https://cdn.tinniestudio.com/processed/<a-known-video-asset-id>/master.m3u8
```

Expected: `HTTP/1.1 403 Forbidden`. Then, as a real logged-in subscriber in a browser, load a content detail page and confirm the video plays (this also requires Task 9).

No commit — AWS console + secret-store config only.

---

## Task 9: [Frontend: client-web] Send credentials on HLS requests

**Files:**
- Modify: `tinniestudio-client-web/src/components/content/HLSPlayer.tsx:147`

- [ ] **Step 1: Add `xhrSetup` to the `Hls` constructor**

```diff
       hls = new Hls({
+        xhrSetup: (xhr) => {
+          xhr.withCredentials = true;
+        },
```

(Match the existing object's formatting/indentation at that call site — insert as the first property.)

- [ ] **Step 2: Type-check and lint**

```bash
cd tinniestudio-client-web
npm run type-check
npm run lint
```

Expected: both pass.

- [ ] **Step 3: Manual verification (requires Task 8 deployed to a real environment with signing enabled)**

Log in as a subscriber, open a content detail page, start playback, and in the browser's Network tab confirm: (a) the `/playback/manifest/content/{id}` response has 3 `Set-Cookie` headers, (b) subsequent requests to `cdn.tinniestudio.com/processed/...` include a `Cookie` header with `CloudFront-Policy`/`CloudFront-Signature`/`CloudFront-Key-Pair-Id`, (c) the video actually plays. Then confirm trailer playback (no login) still works unaffected — trailers never touch this cookie logic.

- [ ] **Step 4: Commit**

```bash
cd tinniestudio-client-web
git add src/components/content/HLSPlayer.tsx
git commit -m "feat(player): send credentials on HLS requests for CloudFront signed-cookie playback"
```

---

## Task 10: End-to-end verification checklist

No new code — this is the final sign-off pass tying Tasks 1-9 together in a real environment.

- [ ] **Step 1: Production upload, unsigned-read regression check**

From the real production frontend, upload a partner logo or content thumbnail. Confirm: the browser's Network tab shows no CORS error on the presigned PUT, the upload completes, and the resulting image loads via `https://cdn.tinniestudio.com/...`.

- [ ] **Step 2: Avatar upload regression check**

Upload a user avatar via the account settings page. Confirm the resulting `avatarUrl` is `https://cdn.tinniestudio.com/avatars/...` (proving the `StripeProperties` → `AppProperties` swap in Task 3 didn't change the URL shape).

- [ ] **Step 3: Video playback with signing enabled**

As in Task 9 Step 3 — full logged-in playback check with cookie inspection.

- [ ] **Step 4: Trailer playback regression check (public, no cookies)**

As a logged-out visitor, load a content/season/episode page and confirm its trailer still plays with no `Set-Cookie`/`Cookie` involvement at all.

- [ ] **Step 5: Direct CDN access without cookies is denied for video, allowed for images**

```bash
curl -o /dev/null -s -w "%{http_code}\n" https://cdn.tinniestudio.com/processed/<known-video-asset-id>/master.m3u8   # expect 403
curl -o /dev/null -s -w "%{http_code}\n" https://cdn.tinniestudio.com/thumbnails/<known-key>.jpg                      # expect 200
```
