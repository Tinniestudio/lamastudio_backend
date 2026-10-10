# CDN-Fronted Upload & Playback Architecture — Design

**Date:** 2026-10-10
**Status:** Approved, ready for planning
**Repos:** `server` (api-service), `tinniestudio-client-web`, `tinniestudio-partner-web`
**Trigger:** Production file uploads failing after CloudFront was put in front of the (now private) `tls-prd-bucket` S3 bucket.

## Context

A presigned PUT upload in production fails from the browser. Investigation (not guesswork —
confirmed via the user: browser console shows a CORS error, and the S3 bucket has never had a
CORS policy applied) traced the actual architecture:

- **Uploads** (`MinioStorageService.generateUploadUrl`, `api-service/src/main/java/com/tinniestudio/api/shared/storage/MinioStorageService.java:50-75`)
  already sign PUT URLs directly against the real S3 host
  (`StorageServiceConfig.storageS3Presigner`, built against `props.getEffectivePublicEndpoint()`,
  `StorageServiceConfig.java:54-64`). This is already the standard, secure pattern — a private
  bucket plus a short-lived signed URL — and needs no architecture change. The failure is a
  missing **bucket-level CORS policy**, which is AWS-side config, not application code.
- **Reads** (thumbnails, avatars, HLS manifests) are a dumb string concatenation —
  `appProperties.getCdn().getBaseUrl() + "/" + storageKey` — with **zero signing or proxying in
  code**. `docs/deployment.md:210-215` already documents this plainly: *"nothing in api-service or
  media-worker proxies that traffic... whatever CDN_BASE_URL points at must serve the bucket's
  objects anonymously."* Putting CloudFront+OAC in front of the now-private bucket (already done
  on the AWS side) is exactly the doc's own "recommended for real traffic" path — this spec
  finishes that migration in code.
- Three independent `CDN_BASE_URL`-shaped configs exist and only agree by convention:
  `app.cdn.base-url` (`AppProperties.Cdn`, `application.yml:142-143`), `stripe.cdn-base-url`
  (`StripeProperties.java:18` — reused for avatars despite the name/package, `application.yml:175`),
  and `tinniestudio-partner-web`'s own frontend `CDN_BASE_URL` env var
  (`src/app/api/uploads/[sessionId]/complete/route.ts:47`), which exists only because
  `CompleteUploadResponse` never returns a URL for `THUMBNAIL`/`PARTNER_LOGO` uploads.
- There is no CloudFront signing code anywhere in the repo, and this is a monetized product
  (Stripe billing, `app.free-tier-content-limit`) — plain public CDN URLs for HLS manifests/segments
  would let a shared link bypass the subscription check that currently gates every manifest
  endpoint (`PlaybackServiceImpl` lines 61-74, 112, 140, 164, 188, 228).

Decisions locked in during review:

1. Keep direct presigned-PUT-to-S3 for uploads (no change to the upload architecture itself) — fix
   is purely the bucket CORS policy.
2. Images (thumbnails/avatars/logos) stay plain public CDN URLs through CloudFront+OAC — no
   signing, since they're already shown to anyone browsing the site.
3. HLS video (manifests + segments) gets CloudFront **signed cookies** — the thing actually worth
   protecting, tied to the existing subscription check.
4. Backend becomes the single source of truth for CDN URLs; the frontend's own `CDN_BASE_URL`
   reconstruction goes away.
5. Local dev (MinIO) gets CORS fixed for parity, but does **not** attempt to emulate CloudFront
   signing — signing is skipped entirely in dev via a config flag.

## Phase 1 — Immediate Fix: Bucket CORS + CloudFront Read Access (ops only, no code)

This is infrastructure config, not an app change, and should ship first/independently.

### 1.1 S3 bucket CORS (fixes the upload bug)

Apply to `tls-prd-bucket` (and the equivalent on the MinIO side for dev parity — §1.3):

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

```bash
aws s3api put-bucket-cors \
  --bucket tls-prd-bucket \
  --region eu-north-1 \
  --cors-configuration file://cors.json
```

`ExposeHeaders: ["ETag"]` matters specifically for multipart uploads (`RAW_VIDEO`/`TRAILER`) — the
client reads the `ETag` response header per part to assemble the `CompleteUploadRequest.parts`
payload; without it, the browser's `fetch`/XHR can read the PUT response status but not that
header, since it's not same-origin.

### 1.2 Bucket policy for CloudFront OAC (fixes reads through the CDN)

Separate from CORS — this is what lets CloudFront fetch objects from a bucket that otherwise has
all public access blocked. If not already in place (the user hasn't verified reads through the CDN
succeed yet):

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

Verify with a direct `curl https://cdn.tinniestudio.com/thumbnails/<known-key>` returning 200
before moving on — this must work before Phase 3's signed cookies can be tested, since the OAC
path is shared by both public (image) and signed (video) reads alike.

### 1.3 MinIO (dev) CORS

MinIO enforces bucket CORS the same way; set an equivalent rule via `mc`:

```bash
mc cors set local/tinniestudio cors.json   # same cors.json as §1.1, origins swapped to http://localhost:3000
```

(Exact origin list should match whatever `FRONTEND_URL`/`LOCAL_DEV_URL` resolve to in dev —
`application.yml:91-98`.)

## Phase 2 — CDN URL Consolidation (backend becomes the single source of truth)

### 2.1 `CompleteUploadResponse` returns a CDN URL for every upload type

`api-service/src/main/java/com/tinniestudio/api/modules/upload/dto/CompleteUploadResponse.java:5`:

```diff
- public record CompleteUploadResponse(UUID mediaFileId, UUID videoAssetId, UUID subtitleId) {}
+ public record CompleteUploadResponse(UUID mediaFileId, UUID videoAssetId, UUID subtitleId, String cdnUrl) {}
```

`UploadService.completeSession` (`api-service/src/main/java/com/tinniestudio/api/modules/upload/service/UploadService.java:120-232`)
computes the URL once, uniformly, regardless of type:

```diff
+ String cdnUrl = appProperties.getCdn().getBaseUrl() + "/" + session.getStorageKey();
  ...
- } else if (type == UploadType.SUBTITLE) {
-     subtitleId = attachSubtitle(userId, session, body);
- }
- return new CompleteUploadResponse(savedFile.getId(), videoAssetId, subtitleId);
+ } else if (type == UploadType.SUBTITLE) {
+     subtitleId = attachSubtitle(userId, session, body, cdnUrl);
+ }
+ return new CompleteUploadResponse(savedFile.getId(), videoAssetId, subtitleId, cdnUrl);
```

`attachSubtitle` (lines 240-270) already builds this exact string at line 266
(`subtitle.setFileUrl(appProperties.getCdn().getBaseUrl() + "/" + session.getStorageKey())`) — pass
the already-computed `cdnUrl` in instead of recomputing it, removing the duplication.

For `RAW_VIDEO`/`TRAILER`, `cdnUrl` points at the raw uploaded file, not the eventual processed HLS
manifest (that's built later by media-worker, under a different key — see §3). This is harmless:
nothing reads `cdnUrl` for those two types today, and returning it uniformly is simpler than a
type-conditional branch to omit it.

### 2.2 Remove `stripe.cdn-base-url` duplication

- `UserProfileServiceImpl.confirmAvatarUpload` (`api-service/src/main/java/com/tinniestudio/api/modules/user/service/UserProfileServiceImpl.java:144`):
  change `stripeProperties.getCdnBaseUrl()` → `appProperties.getCdn().getBaseUrl()`.
- Remove `StripeProperties.cdnBaseUrl` field (`StripeProperties.java:18`) and
  `stripe.cdn-base-url` from `application.yml:175` and `application-test.yml`. `StripeProperties`
  goes back to being purely Stripe config.

### 2.3 Frontend stops reconstructing the URL

`tinniestudio-partner-web/src/app/api/uploads/[sessionId]/complete/route.ts:40-48` — delete the
manual `url: \`${getCdnBaseUrl()}/${storageKey}\`` construction; use `resp.data.cdnUrl` from the
backend instead:

```diff
- const result: CompleteUploadResult = {
-   mediaFileId: resp.data?.mediaFileId ?? null,
-   videoAssetId: resp.data?.videoAssetId ?? null,
-   subtitleId: resp.data?.subtitleId ?? null,
-   url: `${getCdnBaseUrl()}/${storageKey}`,
- };
+ const result: CompleteUploadResult = {
+   mediaFileId: resp.data?.mediaFileId ?? null,
+   videoAssetId: resp.data?.videoAssetId ?? null,
+   subtitleId: resp.data?.subtitleId ?? null,
+   url: resp.data?.cdnUrl ?? null,
+ };
```

`CompleteUploadResponse`/`CompleteUploadResult` TypeScript types gain `cdnUrl: string | null`.
Once nothing reads it, remove `getCdnBaseUrl()`/the frontend's own `CDN_BASE_URL` env var
(`src/config/env.config.ts:34`, `src/schema/env.schema.ts:15-34`) — audit `client-web` and
`admin-web` for the same pattern while in here; if either reconstructs a CDN URL client-side from
its own env var for an upload-confirm flow, apply the same fix (if neither does, no change needed
there).

`ImageUploadField.tsx` (`tinniestudio-partner-web/src/features/uploads/components/ImageUploadField.tsx:64-68`)
needs no change — it already just does `onUploaded(result.url)`, agnostic to how `result.url` was
derived.

## Phase 3 — CloudFront Signed Cookies for Video Playback

### 3.1 Scope

Processed video output lives entirely under one prefix —
`media-worker/src/main/java/com/tinniestudio/worker/processor/VideoProcessingService.java:121`
(`"processed/" + videoAssetId`) covers the master manifest, any sub-playlists, and every segment.
The signed-cookie policy is scoped to that single prefix (`https://cdn.tinniestudio.com/processed/*`)
rather than per-video — access control already happens at manifest-request time via the existing
subscription check (`PlaybackServiceImpl` lines 61-74 and friends); the cookie is session-wide
proof "this viewer passed that check within the last few hours," not a per-title grant. Narrower
per-video scoping would need re-signing on every episode switch for no real security benefit here.

### 3.2 Dependency

`api-service/build.gradle`, alongside the existing AWS S3 SDK block:

```diff
  // AWS SDK v2 (S3-compatible — works with MinIO, S3, R2)
  implementation platform('software.amazon.awssdk:bom:2.26.0')
  implementation 'software.amazon.awssdk:s3'
+ implementation 'software.amazon.awssdk:cloudfront'
```

### 3.3 Config

`AppProperties.Cdn` (`api-service/src/main/java/com/tinniestudio/api/shared/config/AppProperties.java:123-125`):

```diff
  public static class Cdn {
      private String baseUrl = "http://localhost:3000";
+     private boolean signingEnabled = false;
+     private String cloudfrontKeyPairId;
+     private String cloudfrontPrivateKey;   // PEM, injected via env/secret — never committed
+     private long signedCookieTtlSeconds = 21_600L; // 6h
  }
```

`application.yml`:

```diff
  cdn:
    base-url: ${CDN_BASE_URL:http://localhost:3000}
+   signing-enabled: ${CDN_SIGNING_ENABLED:false}
+   cloudfront-key-pair-id: ${CLOUDFRONT_KEY_PAIR_ID:}
+   cloudfront-private-key: ${CLOUDFRONT_PRIVATE_KEY:}
+   signed-cookie-ttl-seconds: ${CDN_SIGNED_COOKIE_TTL_SECONDS:21600}
```

Dev (`.env`) leaves `CDN_SIGNING_ENABLED` unset (defaults `false`) — matches the decision to skip
signing entirely in local dev, since there's no local CloudFront to validate against.

### 3.4 One-time AWS setup (needed before any of this works — user has not done this yet)

1. Generate an RSA key pair (CloudFront requires 2048-bit RSA): `openssl genrsa -out cf-private-key.pem 2048` then `openssl rsa -pubout -in cf-private-key.pem -out cf-public-key.pem`.
2. In CloudFront: create a **public key** resource from `cf-public-key.pem`, then a **key group**
   containing it.
3. On the distribution's cache behavior covering `/processed/*`: set **"Restrict Viewer
   Access" → Yes**, and select that key group as a trusted key group.
4. Store `cf-private-key.pem`'s contents as `CLOUDFRONT_PRIVATE_KEY` (the deployment secret store —
   same place `JWT_ACCESS_SECRET` etc. already live) and the key's ID (shown in the CloudFront
   console next to the public key) as `CLOUDFRONT_KEY_PAIR_ID`.
5. On that same cache behavior, set a response-headers policy with CORS enabled:
   `Access-Control-Allow-Credentials: true` and `Access-Control-Allow-Origin` set to the exact
   prod origin (`https://tinniestudio.com` — **not** `*`, which credentialed CORS forbids).

### 3.5 `CloudFrontSigningService`

New class, `api-service/src/main/java/com/tinniestudio/api/shared/storage/CloudFrontSigningService.java`:

```java
@Service
@RequiredArgsConstructor
public class CloudFrontSigningService {

    private final AppProperties appProperties;

    public Optional<CookiesForCustomPolicy> signVideoAccess() {
        AppProperties.Cdn cdn = appProperties.getCdn();
        if (!cdn.isSigningEnabled()) {
            return Optional.empty();
        }
        Instant expiry = Instant.now().plusSeconds(cdn.getSignedCookieTtlSeconds());
        CloudFrontCookiesForCustomPolicyRequest request = CloudFrontCookiesForCustomPolicyRequest.builder()
            .resourceUrl(cdn.getBaseUrl() + "/processed/*")
            .privateKey(loadPrivateKey(cdn.getCloudfrontPrivateKey()))
            .keyPairId(cdn.getCloudfrontKeyPairId())
            .expirationDate(expiry)
            .build();
        return Optional.of(CloudFrontUtilities.create().getCookiesForCustomPolicy(request));
    }
}
```

(`loadPrivateKey` parses the PEM string from config into a `PrivateKey` — `CloudFrontUtilities`
takes a `Path` in its simplest overload; since the key is an env var, not a file on disk here, use
the `PrivateKey`-accepting overload with a small PEM→`PrivateKey` helper via
`java.security.KeyFactory`/`PKCS8EncodedKeySpec`.) Returns `Optional.empty()` in dev
(`signingEnabled=false`), so call sites have one clean branch.

### 3.6 Wiring into playback endpoints

`PlaybackController` methods that call `buildManifestResponse` for **subscription-gated** content
(`getContentManifest`, `getEpisodeManifest` — NOT the public trailer endpoints, which intentionally
have no access gate) gain an `HttpServletResponse response` parameter and set the cookies after a
successful manifest lookup:

```diff
  @GetMapping("/manifest/content/{contentId}")
  public ResponseEntity<PlaybackManifestResponse> getContentManifest(
          @AuthenticationPrincipal UserDetails principal,
-         @PathVariable UUID contentId) {
-     return ResponseEntity.ok(playbackService.getContentManifest(principal, contentId));
+         @PathVariable UUID contentId,
+         HttpServletResponse response) {
+     PlaybackManifestResponse manifest = playbackService.getContentManifest(principal, contentId);
+     cloudFrontSigningService.signVideoAccess().ifPresent(cookies -> applyCookies(response, cookies));
+     return ResponseEntity.ok(manifest);
  }
```

Same change for `getEpisodeManifest`. A small private `applyCookies(HttpServletResponse, CookiesForCustomPolicy)`
helper in `PlaybackController` sets the three `Set-Cookie` headers
(`CloudFront-Policy`, `CloudFront-Signature`, `CloudFront-Key-Pair-Id`) each with
`Domain=.tinniestudio.com; Path=/; Secure; HttpOnly; SameSite=Lax` — `Lax` (not `None`) is correct
here since `cdn.tinniestudio.com` and the API's own domain share the same registrable domain
(`tinniestudio.com`), making this a same-site, not cross-site, request; no need for `SameSite=None`'s
extra `Secure`-only restrictions.

No change to `getTrailerManifest`/`getSeasonTrailerManifest`/`getEpisodeTrailerManifest` — these
stay fully public, no cookies issued, matching their existing no-auth design.

### 3.7 Client-web player

`HLSPlayer.tsx`'s `new Hls({...})` call (`tinniestudio-client-web/src/components/content/HLSPlayer.tsx:147`)
needs `xhrSetup` so the browser actually attaches the cross-subdomain cookies on manifest/segment
requests:

```diff
  hls = new Hls({
+   xhrSetup: (xhr) => { xhr.withCredentials = true; },
    ...
  });
```

No change needed for the trailer path (`useTrailerManifest`, public endpoints) — only the
subscription-gated main-content/episode playback path needs credentialed requests.

## Testing & Verification

**Phase 1 (ops):**
- `curl -X PUT` a presigned URL with an `Origin: https://tinniestudio.com` header and confirm the
  preflight `OPTIONS` + the real `PUT` both succeed.
- `curl https://cdn.tinniestudio.com/<existing-thumbnail-key>` returns 200 with no auth.

**Phase 2 (backend):**
- `completeSession` returns a non-null `cdnUrl` for every `UploadType`, including `THUMBNAIL`/`PARTNER_LOGO` (previously null in effect, since it wasn't on the DTO at all).
- Avatar confirm (`confirmAvatarUpload`) still returns the same URL shape after the `stripeProperties` → `appProperties` swap.
- Integration test: `attachSubtitle`'s `fileUrl` is unchanged after removing the duplicate computation.

**Phase 2 (partner-web):**
- Manual: upload a partner logo / content thumbnail end-to-end; confirm the image renders using the backend-supplied `cdnUrl`, with the frontend's `CDN_BASE_URL` env var removed.

**Phase 3:**
- Unit test: `CloudFrontSigningService.signVideoAccess()` returns `Optional.empty()` when `signing-enabled=false` (dev default); returns a populated policy when enabled, with the correct resource prefix and expiry ~= now + TTL.
- Manual, prod only (no local CloudFront to test against): log in as a subscriber, request a content manifest, confirm three `Set-Cookie` headers appear with the right `Domain`/`Secure`/`HttpOnly` attributes; confirm the HLS player can then fetch `processed/*` segments; confirm a request to the same URL **without** those cookies (e.g. `curl`) gets CloudFront's 403.
- Manual: confirm trailer playback (no login, no cookies) still works unaffected.

## Non-goals

- No change to the upload architecture itself (presigned PUT direct to S3 stays) — only the bucket CORS policy changes.
- No signing for images (thumbnails/avatars/logos) — these stay plain public CDN URLs through CloudFront+OAC, per the explicit decision that paywall protection only matters for video.
- No CloudFront signing emulation in local dev — `signing-enabled=false` is the permanent dev default, not a stopgap.
- No per-video/per-title cookie scoping — the single `processed/*` prefix is a deliberate simplification; access control is enforced once, at manifest-request time, by the existing subscription check.
- No IaC (Terraform/CDK) added for the bucket CORS/policy or CloudFront distribution — delivered as AWS CLI commands/JSON per the explicit decision to keep this manual for now.
