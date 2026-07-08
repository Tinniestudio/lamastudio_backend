# TinnieStudio — Master Task & Context Document
> Last updated: 2026-07-08
> Purpose: Full context restoration for Claude across sessions. Read this before any implementation session.

---

## 1. What Is TinnieStudio

A Netflix-like video streaming platform. Users subscribe to a plan, browse content (movies/series), and stream via adaptive HLS. Partners upload and publish content. Admins moderate the platform.

**Two runtime services — one repo:**
- `api-service` — REST API for frontend clients
- `media-worker` — Async video transcoding (FFmpeg + RabbitMQ)

Both deploy independently via Docker. Both share the same PostgreSQL database.

---

## 2. Architecture Decisions (locked in)

| Decision | Choice | Notes |
|----------|--------|-------|
| Build system | Gradle multi-project | Migrating from Maven |
| Services | api-service + media-worker | Same repo, separate JARs |
| Root package (API) | `com.tinniestudio.api` | Renaming from `com.lamastudio.backend` |
| Root package (Worker) | `com.tinniestudio.worker` | New |
| Repo layout | Monorepo | `api-service/`, `media-worker/`, `docker-compose.yml` at root |
| Dockerfiles | One per service | `api-service/Dockerfile`, `media-worker/Dockerfile` |
| Database | PostgreSQL 16 + Flyway | Migrations owned by api-service only |
| Cache | Redis 7 (Lettuce) | Key prefix: `tinnie:{module}:{key}` |
| Queue | RabbitMQ 3.x + Spring AMQP | `QueuePublisher` interface + `RabbitQueuePublisher` done (api-service) |
| Storage | S3-compatible via `StorageService` interface | `MinioStorageService` done (MINIO provider); NoOp fallback; `StorageException` wrapping |
| Email | Resend SDK | `ResendEmailService` done |
| Auth | JWT (access body + refresh HttpOnly cookie) | Dual JWT: user + admin separate secrets |
| OAuth2 | Google | Done |
| Payment | Stripe | Done (not Paystack) |
| Rate limiting | Redis token bucket via AOP `@RateLimit` | Done |

---

## 3. Repository Layout (Target State after Migration)

```
/server (repo root)
├── settings.gradle
├── build.gradle                    ← dependency versions only (no plugins applied here)
├── gradlew / gradlew.bat
├── gradle/wrapper/
├── docker-compose.yml              ← brings up: postgres, redis, rabbitmq, minio, api-service, media-worker
├── .env / .env.prod
├── task.md                         ← this file
├── BATCH-PLAN.md
│
├── api-service/
│   ├── build.gradle
│   ├── Dockerfile
│   └── src/
│       ├── main/java/com/tinniestudio/api/
│       │   ├── ApiServiceApplication.java
│       │   ├── shared/
│       │   │   ├── config/         ← AppProperties, SecurityConfig, RedisConfig, etc.
│       │   │   ├── entity/         ← BaseEntity, User, UserProfile, Content, etc.
│       │   │   ├── exception/      ← GlobalExceptionHandler, AppException hierarchy
│       │   │   ├── web/            ← ApiResponse, SuccessResponseWrapper
│       │   │   ├── cache/          ← CacheService, RedisCacheService
│       │   │   ├── email/          ← ResendEmailService
│       │   │   ├── ratelimit/      ← @RateLimit, RateLimiterService
│       │   │   ├── security/       ← JWT filters, OAuth2 handlers
│       │   │   ├── storage/        ← StorageService interface + implementations
│       │   │   ├── jobs/           ← Scheduled jobs
│       │   │   └── util/
│       │   └── modules/
│       │       ├── auth/           ← AuthController, AuthService, admin auth
│       │       ├── billing/        ← SubscriptionController, StripeService, CouponService
│       │       ├── category/       ← CategoryService (no controller yet)
│       │       ├── content/        ← ContentService (no controller yet)
│       │       ├── notification/   ← NotificationService (no controller yet)
│       │       ├── upload/         ← UploadService (no controller yet)
│       │       ├── user/           ← UserProfileController, UserProfileService
│       │       └── role/           ← RoleRepository
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── application.dev.yml
│       │   ├── application.prod.yml
│       │   └── db/migration/       ← Flyway V1–V13 (api-service owns ALL migrations)
│       └── test/
│
└── media-worker/
    ├── build.gradle
    ├── Dockerfile                  ← installs FFmpeg + FFprobe
    └── src/
        ├── main/java/com/tinniestudio/worker/
        │   ├── MediaWorkerApplication.java
        │   ├── config/             ← RabbitMQ, DB, Storage config
        │   ├── consumer/           ← @RabbitListener for media.video.process
        │   ├── processor/          ← VideoProcessingService, pipeline stages
        │   ├── ffmpeg/             ← FFmpegRunner, FFprobeRunner
        │   └── storage/            ← worker-side StorageService impl
        └── main/resources/
            └── application.yml
```

---

## 4. Current Implementation Status

### DONE ✅

**Auth module** (`modules/auth/`)
- Register: email validation, BCrypt (strength 12), PENDING_VERIFICATION status
- Login: password verify, JWT access token + refresh cookie, Redis refresh cache
- Logout: revoke refresh token in DB + Redis + clear cookie
- Token refresh: Redis fast-path + DB fallback, rotation
- Email verification: token generation, expiry, resend with rate limit
- Password reset: hash token, 1h TTL, revoke all sessions on reset
- Admin auth: separate JWT secrets, separate filter chain, session management
- Admin bootstrap: seed initial admin from env token
- OAuth2: Google login via Spring Security OAuth2

**User module** (`modules/user/`)
- UserProfileController: GET/PATCH profile, PATCH notifications, PATCH password

**Billing module** (`modules/billing/`)
- SubscriptionController: GET plans, GET my subscription, POST checkout (Stripe), POST cancel
- StripeWebhookController: payment event handling (HMAC signature validated)
- AdminSubscriptionController: admin subscription management
- CouponService: validate, redeem, redemption tracking
- SubscriptionExpirationJob: daily cron — expires subscriptions past end_date
- SubscriptionExpiryReminderJob: daily cron — notifies users 3 days before expiry
- CapabilityService: checks user plan limits (device count, video quality)

**Shared infrastructure**
- Redis: CacheService + RedisCacheService (get/set/delete/exists/increment)
- Rate limiting: @RateLimit AOP annotation, Redis token bucket
- Email: ResendEmailService, EmailTemplates (HTML)
- Security: JwtAuthenticationFilter, AdminJwtAuthenticationFilter, CookieFactory
- Response envelope: ApiResponse, SuccessResponseWrapper, GlobalExceptionHandler
- OpenAPI / Swagger UI
- AsyncConfig: async executor
- **RabbitMQ publisher** (2026-07-08): `QueuePublisher` interface, `RabbitQueuePublisher` (RabbitTemplate-backed, JSON), `QueueMessage<T>` envelope (messageId/type/publishedAt/attempt/version/payload), `RabbitConfig` (exchange `tinniestudio.direct` + 5 queues with DLX wiring). `RabbitTemplate` confined to `RabbitQueuePublisher` only.
- **StorageService** (2026-07-08): `MinioStorageService` (AWS SDK v2, path-style for MinIO, S3Client+S3Presigner constructor-injected), `StorageServiceConfig` (@ConditionalOnProperty MINIO / @ConditionalOnMissingBean NoOp), `StorageProperties` (@ConfigurationProperties prefix=app.storage), `StorageException` wraps SDK types. S3Client/S3Presigner confined to infra layer only. `copyObject` + `getMetadata` deferred to Batch 7.

**Entities** (JPA — tables created via Flyway)
- User, UserProfile, UserSubscription, SubscriptionPlan, Payment, Coupon, CouponRedemption
- Content, Season, Episode, Category
- VideoAsset, VideoVariant, UploadSession, Subtitle
- WatchProgress, Notification

**Flyway migrations**
- V1: users, roles, refresh_tokens, email_verification_tokens, password_reset_tokens
- V2: seed roles (USER, PARTNER, ADMIN, SUPER_ADMIN)
- V3: admin tables (admins, admin_sessions)
- V4: user_sessions
- V5: subscription_plans, user_subscriptions
- V6: coupons, coupon_redemptions
- V7: subscription fields (trial_ends_at, auto_renew, etc.)
- V8: remove admin roles from users table
- V9: user_profiles
- V10: payments
- V11: subscription cancelled_at column
- V12: user_subscription max_devices
- V13: update subscription plan prices

---

### ENTITIES EXIST BUT NO CONTROLLERS / BUSINESS LOGIC YET ⚠️

| Entity/Area | File exists | Controller | Service | Status |
|---|---|---|---|---|
| Content (CRUD, status workflow) | ✅ entity | ❌ | partial | Not built |
| Category (CRUD, Redis cache) | ✅ entity | ❌ | partial | Not built |
| Season + Episode | ✅ entity | ❌ | ❌ | Not built |
| Upload session (presigned URL) | ✅ entity | ❌ | partial | Not built |
| Notification (in-app) | ✅ entity | ❌ | partial | Not built |

---

### NOT STARTED ❌

| Batch | Feature |
|---|---|
| Batch 3 | Category API controllers + homepage discovery |
| Batch 4 | Content API (CRUD, status workflow: DRAFT→REVIEW→PUBLISHED→ARCHIVED) |
| Batch 5 | Episode + Series API |
| Batch 6 | Upload session API (presigned URL flow, complete, status) |
| Batch 7 | **Media Worker** — FFprobe metadata, FFmpeg HLS transcoding, retry logic |
| Batch 8 | Playback system — manifest delivery, subscription enforcement, watch progress |
| Batch 9 | Search + Discovery (PostgreSQL FTS, recommendations) |
| Batch 10 | Favorites + Watch History APIs |
| Batch 11 | Ratings + Reviews + aggregate scoring |
| Batch 13 | Partner Portal (dashboard, analytics, upload management) |
| Batch 14 | Admin Moderation (user management, content queue, audit log) |
| Batch 15 | Notification system (queue consumer, email + in-app delivery) |
| Batch 16 | Analytics system (event ingestion, daily aggregation) |
| Batch 17 | Full background job suite |
| Batch 18 | Observability, structured JSON logging, security hardening |

---

## 5. Immediate Task: Gradle Migration (In Progress)

### Goal
Convert the single Maven project into a Gradle multi-project build with two deployable subprojects: `api-service` and `media-worker`.

### Decision
- Option A: In-place migration on a feature branch
- Package rename: `com.lamastudio.backend` → `com.tinniestudio.api`

### Step-by-Step Checklist

- [ ] **Step 1 — Gradle wrapper**
  - Run `gradle wrapper --gradle-version 8.8` at repo root (or download wrapper files manually)
  - Files: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

- [ ] **Step 2 — Root build files**
  - Create `settings.gradle` — defines `rootProject.name = 'tinniestudio'`, includes `api-service` and `media-worker`
  - Create root `build.gradle` — shared version catalog / `ext {}` block for dependency versions only

- [ ] **Step 3 — Create api-service subproject**
  - Create `api-service/` directory
  - Move `src/` → `api-service/src/`
  - Move `src/main/resources/` → `api-service/src/main/resources/` (includes application.yml, Flyway migrations)
  - Create `api-service/build.gradle` — all current `pom.xml` dependencies translated to Gradle syntax

- [ ] **Step 4 — Package rename (api-service)**
  - Rename all `.java` files: `com.lamastudio.backend` → `com.tinniestudio.api`
  - Rename package directories accordingly
  - Update `LamaStudioApplication.java` → `ApiServiceApplication.java`
  - Update `application.yml`: `spring.application.name: tinniestudio-api`
  - Update logging config: `com.tinniestudio` instead of `com.lamastudio`
  - Update `@SpringBootApplication` scan annotation if needed

- [ ] **Step 5 — api-service Dockerfile**
  - Create `api-service/Dockerfile`
  - Multi-stage: build stage (`./gradlew :api-service:bootJar`), runtime stage (JRE 21 slim)
  - EXPOSE 8080

- [ ] **Step 6 — Create media-worker subproject scaffold**
  - Create `media-worker/` directory with full package structure:
    ```
    media-worker/src/main/java/com/tinniestudio/worker/
      MediaWorkerApplication.java
      config/RabbitConfig.java (stub)
      consumer/VideoProcessingConsumer.java (stub)
      processor/VideoProcessingService.java (stub)
      ffmpeg/FFmpegRunner.java (stub)
      ffmpeg/FFprobeRunner.java (stub)
    media-worker/src/main/resources/application.yml
    ```
  - Create `media-worker/build.gradle` — Spring Boot + AMQP + JPA + PostgreSQL driver

- [ ] **Step 7 — media-worker Dockerfile**
  - Multi-stage build + runtime stage installs `ffmpeg` and `ffprobe` via apt
  - EXPOSE 8081 (or no port — worker has no HTTP listeners)

- [ ] **Step 8 — Update docker-compose.yml**
  - Replace single `app` service with `api-service` + `media-worker`
  - Add missing infrastructure services: `redis`, `rabbitmq`, `minio`
  - Keep `db` (postgres)
  - All services on shared `tinniestudio-network`

- [ ] **Step 9 — Delete pom.xml**

- [ ] **Step 10 — Verify**
  - `./gradlew :api-service:build` — all tests pass
  - `./gradlew :media-worker:build` — compiles clean
  - `docker-compose build` — both images build

---

## 6. docker-compose.yml Target (after migration)

```yaml
services:
  api-service:
    build: ./api-service
    container_name: tinniestudio-api
    restart: unless-stopped
    ports:
      - "8080:8080"
    env_file: .env
    environment:
      DB_HOST: db
      DB_PORT: 5432
      DB_NAME: tinniestudio_db
      DB_USER: postgres
      DB_PASSWORD: postgres
      REDIS_URL: redis://redis:6379
      RABBITMQ_HOST: rabbitmq
    depends_on: [db, redis, rabbitmq]
    networks: [tinniestudio-network]

  media-worker:
    build: ./media-worker
    container_name: tinniestudio-worker
    restart: unless-stopped
    env_file: .env
    environment:
      DB_HOST: db
      DB_PORT: 5432
      DB_NAME: tinniestudio_db
      DB_USER: postgres
      DB_PASSWORD: postgres
      RABBITMQ_HOST: rabbitmq
    depends_on: [db, rabbitmq]
    networks: [tinniestudio-network]

  db:
    image: postgis/postgis:16-3.4
    container_name: tinniestudio-db
    restart: always
    environment:
      POSTGRES_DB: tinniestudio_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - db_data:/var/lib/postgresql/data
    networks: [tinniestudio-network]

  redis:
    image: redis:7-alpine
    container_name: tinniestudio-redis
    restart: always
    ports:
      - "6379:6379"
    networks: [tinniestudio-network]

  rabbitmq:
    image: rabbitmq:3-management-alpine
    container_name: tinniestudio-rabbitmq
    restart: always
    ports:
      - "5672:5672"
      - "15672:15672"   # management UI
    networks: [tinniestudio-network]

  minio:
    image: minio/minio:latest
    container_name: tinniestudio-minio
    restart: always
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports:
      - "9000:9000"
      - "9001:9001"   # MinIO console
    volumes:
      - minio_data:/data
    networks: [tinniestudio-network]

networks:
  tinniestudio-network:
    driver: bridge

volumes:
  db_data:
  minio_data:
```

---

## 7. Queue Topology (RabbitMQ — to be wired in Batch 6/7)

```
Exchange: tinniestudio.direct

Queues:
  media.video.process     → DLX: media.video.failed        (publisher: api-service, consumer: media-worker)
  media.video.retry       → TTL + re-routes to process     (publisher/consumer: media-worker)
  media.video.failed      → dead letters, manual review    (consumer: none — admin notification only)
  notifications.send      → event-driven delivery          (publisher: both, consumer: api-service)
  analytics.ingest        → event ingestion                (publisher: api-service, consumer: api-service)
```

**Message envelope (all messages):**
```json
{
  "messageId": "uuid",
  "type": "VIDEO_PROCESSING_JOB",
  "publishedAt": "2026-01-01T00:00:00Z",
  "attempt": 1,
  "version": 1,
  "payload": {}
}
```

---

## 8. DB Write Ownership (enforced by architecture)

| Tables | Owner |
|--------|-------|
| users, user_profiles, user_subscriptions, coupons, payments, sessions | api-service ONLY |
| contents, seasons, episodes, categories | api-service ONLY |
| upload_sessions, media_files | api-service ONLY |
| video_assets.processing_status, processing_error, manifest_key | media-worker ONLY |
| video_variants, processing_jobs | media-worker ONLY |

---

## 9. Redis Key Namespacing

```
tinnie:auth:refresh:{userId}:{tokenId}     TTL 7d
tinnie:auth:otp:{email}                    TTL 10min
tinnie:rate:{ip}:{endpoint}                token bucket counter
tinnie:upload:{sessionId}                  upload state, TTL 30min
tinnie:playback:{contentId}:{userId}       signed URL cache, TTL 5min
tinnie:home:sections                       homepage sections, TTL 5min
tinnie:category:list                       category list, TTL 10min
tinnie:content:{slug}                      content detail, TTL 2min
tinnie:lock:{jobName}                      distributed job lock
```

---

## 10. API Endpoint Master Reference

### Base path: `/api/v1`

#### Auth (DONE ✅)
```
POST /auth/register
POST /auth/login
POST /auth/logout
POST /auth/refresh
POST /auth/verify-email
POST /auth/resend-verification
POST /auth/forgot-password
POST /auth/reset-password
GET  /auth/me
GET  /auth/oauth2/callback/google
```

#### Users / Profile (DONE ✅)
```
GET    /users/me
PATCH  /users/me
PATCH  /users/me/notifications
PATCH  /users/me/password
PATCH  /users/me/avatar
```

#### Subscriptions / Billing (DONE ✅)
```
GET   /subscriptions/plans
POST  /subscriptions/checkout
GET   /subscriptions/me
PATCH /subscriptions/cancel
POST  /subscriptions/apply-coupon
POST  /webhooks/payment
```

#### Categories (NOT BUILT ❌)
```
GET    /categories
GET    /categories/:slug
POST   /admin/categories
PATCH  /admin/categories/:id
DELETE /admin/categories/:id
```

#### Content (NOT BUILT ❌)
```
GET    /contents
GET    /contents/:slug
POST   /partner/contents
PATCH  /partner/contents/:id
DELETE /partner/contents/:id
PATCH  /partner/contents/:id/publish
PATCH  /partner/contents/:id/unpublish
PATCH  /admin/contents/:id/approve
PATCH  /admin/contents/:id/reject
PATCH  /admin/contents/:id/feature
```

#### Seasons + Episodes (NOT BUILT ❌)
```
GET    /contents/:contentId/seasons
POST   /partner/contents/:contentId/seasons
PATCH  /partner/seasons/:seasonId
DELETE /partner/seasons/:seasonId
GET    /seasons/:seasonId/episodes
POST   /partner/seasons/:seasonId/episodes
PATCH  /partner/episodes/:id
DELETE /partner/episodes/:id
PATCH  /partner/seasons/:seasonId/episodes/reorder
```

#### Upload Sessions (NOT BUILT ❌)
```
POST /uploads/sessions
POST /uploads/:sessionId/complete
GET  /uploads/:sessionId/status
```

#### Playback (NOT BUILT ❌)
```
GET  /playback/:contentId/access
GET  /playback/:contentId/manifest
GET  /playback/episode/:episodeId/manifest
POST /playback/progress
GET  /playback/continue-watching
```

#### Discovery + Search (NOT BUILT ❌)
```
GET /discover/home
GET /discover/featured
GET /discover/trending
GET /discover/new-releases
GET /discover/recommended
GET /search
```

#### Favorites + History (NOT BUILT ❌)
```
GET    /favorites
POST   /favorites/:contentId
DELETE /favorites/:contentId
GET    /history
DELETE /history/:id
DELETE /history
```

#### Reviews (NOT BUILT ❌)
```
GET    /contents/:contentId/reviews
POST   /contents/:contentId/reviews
PATCH  /reviews/:id
DELETE /reviews/:id
PATCH  /admin/reviews/:id/status
```

#### Notifications (NOT BUILT ❌)
```
GET   /notifications
PATCH /notifications/:id/read
PATCH /notifications/read-all
```

#### Partner (NOT BUILT ❌)
```
GET /partner/dashboard
GET /partner/analytics
GET /partner/uploads
GET /partner/contents
GET /partner/revenue
```

#### Admin (PARTIAL ⚠️)
```
GET   /admin/dashboard                  ← not built
GET   /admin/users                      ← not built
PATCH /admin/users/:id/status           ← not built
GET   /admin/uploads/processing         ← not built
GET   /admin/analytics/platform         ← not built
GET   /admin/categories (see above)     ← not built
GET   /admin/contents/* (see above)     ← not built
POST  /admin/auth/login                 ← DONE ✅
POST  /admin/auth/logout                ← DONE ✅
POST  /admin/auth/refresh               ← DONE ✅
```

---

## 11. Media Worker — Processing Pipeline Reference

When a `RAW_VIDEO` upload is completed via `/uploads/:sessionId/complete`, the api-service:
1. Creates a `VideoAsset` (status=PENDING)
2. Publishes a `MediaProcessingJob` message to `media.video.process`

The worker then runs:
```
VALIDATING → DOWNLOADING → PROBING (FFprobe) → TRANSCODING (FFmpeg HLS) 
→ THUMBNAIL_GENERATION → UPLOADING_OUTPUT → FINALIZING → CLEANUP
```

**FFmpeg HLS output per resolution:**
| Resolution | Video bitrate | Audio |
|------------|--------------|-------|
| 1080p | 5000k | 192k |
| 720p | 2800k | 128k |
| 480p | 1400k | 128k |
| 360p | 800k | 96k |

Output path in storage: `processed/{videoAssetId}/{resolution}/`
Master manifest: `processed/{videoAssetId}/master.m3u8`

Retry: max 3 attempts. Attempt 2 after 1 min, attempt 3 after 5 min (via `media.video.retry` TTL queue). After 3 failures → route to `media.video.failed`.

---

## 12. Response Envelope (ALL endpoints)

```json
{
  "success": true,
  "data": {},
  "error": null,
  "meta": { "page": 1, "limit": 20, "total": 100 }
}
```

Error shape:
```json
{
  "success": false,
  "data": null,
  "error": { "code": "NOT_FOUND", "message": "Content not found", "details": null }
}
```

Machine-readable codes: `NOT_FOUND`, `UNAUTHORIZED`, `FORBIDDEN`, `CONFLICT`, `VALIDATION_FAILED`, `UPGRADE_REQUIRED`, `RATE_LIMIT_EXCEEDED`

---

## 13. Role Hierarchy

```
SUPER_ADMIN > ADMIN > PARTNER > USER
```

Security guard summary:
- `/auth/*`, `GET /contents`, `GET /categories`, `/discover/*`, `/search` — Public
- `/playback/*`, `/favorites/*`, `/history/*`, `/reviews/*` — USER+
- `/uploads/sessions`, `/partner/*` — PARTNER+
- `/admin/*` — ADMIN+
- `/webhooks/*` — Public (signature validated internally)

---

## 14. Environment Variables Required

```
# Database
DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD

# Redis
REDIS_URL

# JWT (user)
JWT_ACCESS_SECRET, JWT_ACCESS_EXPIRATION_MS
JWT_REFRESH_SECRET, JWT_REFRESH_EXPIRATION_MS

# JWT (admin)
JWT_ADMIN_ACCESS_SECRET, JWT_ADMIN_ACCESS_EXPIRATION_MS
JWT_ADMIN_REFRESH_SECRET, JWT_ADMIN_REFRESH_EXPIRATION_MS

# OAuth2
GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET

# App
APP_BASE_URL, FRONTEND_URL
ADMIN_BOOTSTRAP_TOKEN
FREE_TIER_CONTENT_LIMIT
COOKIE_SECURE, COOKIE_SAME_SITE, COOKIE_DOMAIN

# Stripe
STRIPE_SECRET_KEY, STRIPE_WEBHOOK_SECRET
CDN_BASE_URL

# Email
RESEND_API_KEY, RESEND_BASE_URL, RESEND_FROM_EMAIL

# Storage (to be added)
STORAGE_PROVIDER (S3 | R2 | MINIO)
STORAGE_BUCKET, STORAGE_REGION, STORAGE_ENDPOINT
AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY

# RabbitMQ (to be added)
RABBITMQ_HOST, RABBITMQ_PORT, RABBITMQ_USER, RABBITMQ_PASSWORD
```

---

## 15. Architecture Drift — Forbidden Patterns

These are hard violations (not code review comments — blocking):

| Rule | Violation |
|------|-----------|
| No direct infra in domain services | `AuthService` injecting `RedisTemplate` directly |
| No storage SDK outside `StorageService` | `S3Client` in `UploadService` |
| No `RabbitTemplate` outside `QueuePublisher` | `rabbitTemplate.convertAndSend()` in `ContentService` |
| No FFmpeg/FFprobe outside media-worker | API service calling ProcessBuilder("ffprobe") |
| No business logic in controllers | Subscription check inside `@RestController` method body |
| No cross-domain repository injection | `AuthService` injecting `ContentRepository` |
| No `@Value` in service/use-case classes | `@Value("${jwt.secret}")` in `AuthService` |
| No `System.getenv()` in application code | Anywhere outside bootstrap config |

---

*Read BATCH-PLAN.md for full batch specifications including DB schemas, flows, and completion gates.*
