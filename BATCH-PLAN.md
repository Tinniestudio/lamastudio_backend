# TinnieStudio — Full Execution Roadmap
> Spring Boot 3 · Java 21 · Modular Monolith + Worker Service(seperate on different server) · Clean Architecture

---

## Legend

| Symbol | Meaning |
|--------|---------|
| 🔵 | API Service task |
| 🟠 | Worker Service task |
| ⚙️ | Shared / Infrastructure |
| ✅ | Completion checkpoint |

---

## Architecture At A Glance

```
Client Apps (Web / Mobile)
       ↓
   Nginx Reverse Proxy
       ↓
Spring Boot API Service           ←→  PostgreSQL (source of truth)
       ↓                          ←→  Redis (cache / rate-limit / OTP)
   RabbitMQ                       ←→  Object Storage (S3 / R2) 
> Spring Boot 3 · Java 21 · Modular Monolith + Worker Service · Clean Architecture

---

## Legend

| Symbol | Meaning |
|--------|---------|
| 🔵 | API Service task |
| 🟠 | Worker Service task |
| ⚙️ | Shared / Infrastructure |
| ✅ | Completion checkpoint |

---

## Architecture At A Glance

```
Client Apps (Web / Mobile)
       ↓
   Nginx Reverse Proxy
       ↓
Spring Boot API Service           ←→  PostgreSQL (source of truth)
       ↓                          ←→  Redis (cache / rate-limit / OTP)
   RabbitMQ                       ←→  Object Storage (S3 / R2)
       ↓
Spring Boot Media Worker
       ↓
   FFmpeg Pipeline
       ↓
   CDN (Cloudflare / Bunny)
       ↓
   HLS Playback Clients
```

---

# EXECUTION GOVERNANCE SYSTEM

> This section defines HOW Speckit executes batches. All rules are mandatory. See `server/.specify/memory/constitution.md` for the full governance rationale.

---

## 1. Feature Execution Lifecycle

Every feature follows this mandatory lifecycle — no stage may be skipped:

```
IDEA
→ SPECIFY              (speckit-specify: create spec.md from natural language description)
→ ARCHITECTURE REVIEW  (validate spec against constitution + domain boundaries)
→ PLAN                 (speckit-plan: generate plan.md with design artifacts)
→ TASK BREAKDOWN       (speckit-tasks: generate ordered tasks.md)
→ IMPLEMENT            (speckit-implement: execute tasks one at a time, TDD-first)
→ VALIDATE             (run completion gates defined in batch)
→ INTEGRATION REVIEW   (verify cross-service contracts, no architecture drift)
→ COMPLETE             (batch marked done, downstream batches unblocked)
```

### Stage Responsibilities

| Stage | Responsible For |
|-------|----------------|
| SPECIFY | Documenting the business requirement, acceptance criteria, and domain boundaries |
| ARCHITECTURE REVIEW | Checking the spec against domain ownership rules and constitution |
| PLAN | Defining implementation approach, file structure, and design artifacts |
| TASK BREAKDOWN | Generating ordered, dependency-aware tasks for Speckit to execute |
| IMPLEMENT | Executing tasks in order, one at a time, TDD-first |
| VALIDATE | Running the batch completion gates (functional, security, integration, performance, rollback) |
| INTEGRATION REVIEW | Verifying shared contracts, queue payloads, and cross-service interfaces |
| COMPLETE | Committing final state, unblocking dependent batches |

If a stage produces no issues (e.g., ARCHITECTURE REVIEW finds no gaps), it is logged as passed — not omitted.

---

## 2. Speckit Batch Consumption Rules

- Speckit processes **ONE batch at a time** — no batch combining in a single run
- Speckit **cannot skip declared dependencies** — all `dependsOn` batches must be COMPLETE before the run starts
- **Infrastructure batches always execute before** any business or domain batch that depends on them
- **Shared abstractions must be finalized** (COMPLETE) before any batch that consumes them begins
- Cross-batch work discovered during implementation is deferred, not executed inline

---

## 3. Batch Classification System

Every batch must declare exactly one classification. Classification determines execution priority and dependency ordering rules.

| Classification | Description | Phase |
|---------------|-------------|-------|
| `FOUNDATION` | Core infrastructure and shared abstractions | Phase 0 |
| `SECURITY` | Identity, auth, token management, session governance | Phase 1 |
| `DOMAIN` | Core business domain models and workflows | Phase 2 |
| `MEDIA` | Async media processing pipeline | Phase 3 |
| `PLAYBACK` | Content delivery and stream access | Phase 4 |
| `DISCOVERY` | Search, recommendations, library management | Phase 4 |
| `PAYMENT` | Billing, subscriptions, coupons | Phase 5 |
| `PARTNER` | Creator tools, analytics, revenue | Phase 6 |
| `ADMIN` | Platform governance and moderation | Phase 7 |
| `OBSERVABILITY` | Logging, jobs, health, hardening | Phase 8 |
| `SCALING` | Performance optimization and horizontal scale | Phase 9 |

**Ordering rules by classification:**
1. `FOUNDATION` must be COMPLETE before all other classifications
2. `SECURITY` must be COMPLETE before any authenticated domain batch
3. All `requiredInfrastructure` items must be running before a batch starts
4. `ADMIN` batches require the domain they govern to be COMPLETE
5. `OBSERVABILITY` wraps all other phases; it executes last

---

## 4. Batch Dependency Contracts

Each batch explicitly declares these fields in its header:

```yaml
dependsOn:                 # Batch numbers/names that must be COMPLETE before this starts
blocks:                    # Batch numbers/names that cannot start until this is COMPLETE
requiredInfrastructure:    # PostgreSQL | Redis | RabbitMQ | Storage | CDN
crossServiceContracts:     # Shared DTOs, queue payloads, or API contracts this batch consumes
```

**Example — Batch 8 (Playback):**
```yaml
dependsOn: [Batch-0, Batch-1, Batch-4, Batch-7]
blocks: [Batch-9, Batch-10]
requiredInfrastructure: [PostgreSQL, Redis, CDN]
crossServiceContracts: [VideoAsset.manifestKey, UserSubscription.status, QueueMessage<VideoProcessingJob>]
```

If a batch has no dependencies, declare `dependsOn: []` explicitly — never leave it undeclared.

---

## 5. Completion Gate System

No batch is COMPLETE until all five gates pass. Gates are validated in order.

| Gate | What to Verify |
|------|---------------|
| `functionalValidation` | All API endpoints return expected responses for happy path and all defined error cases |
| `securityValidation` | Auth guards enforced, no role escalation possible, no public access to protected endpoints |
| `integrationValidation` | Queue messages consumed and processed correctly; DB state transitions match expected business flow |
| `performanceValidation` | Redis caching verified (cache hit confirmed); no N+1 queries on list endpoints; DB indexes applied via migration |
| `rollbackReadiness` | Flyway migration runs cleanly on a clean DB; any destructive operation has a documented rollback path |

If any gate fails, the batch implementation is incomplete — not the gate. Fix the implementation.

---

## 6. Worker/API Coordination Standards

### Queue Ownership
| Queue | Publisher | Consumer |
|-------|-----------|----------|
| `media.video.process` | API Service | Media Worker |
| `media.video.retry` | Media Worker (DLX) | Media Worker |
| `media.video.failed` | RabbitMQ DLX | Admin notification only |
| `notifications.send` | API Service + Media Worker | API Service |
| `analytics.ingest` | API Service | API Service |

### DB Write Ownership
| Entity | Owns Writes |
|--------|------------|
| `users`, `contents`, `user_subscriptions`, `coupons`, `sessions` | API Service only |
| `video_assets.processing_status/error/manifest_key` | Media Worker |
| `video_variants.*`, `processing_jobs.*` | Media Worker |

### Retry Ownership
- Business operation retries: API Service
- Media processing retries: Media Worker via `media.video.retry` queue with TTL
- Max retries: 3 attempts; after attempt 3, route to `media.video.failed`

### State Synchronization
- `video_assets.processing_status` is the authoritative source for processing state
- Redis `tinnie:upload:{sessionId}` is the fast-path cache for upload session state
- DB is always authoritative; Redis is always a cache — never the source of truth

---

## 7. Shared Contract Governance

### Response Envelope (ALL endpoints, no exceptions)
```json
{ "success": true, "data": {}, "error": null, "meta": { "page": 1, "limit": 20, "total": 100 } }
```

### Pagination (ALL list endpoints)
- Request: `page` (default 1), `limit` (default 20, max 100), `sortBy`, `sortOrder`
- Response: `PageResult<T>` with `items`, `total`, `page`, `limit`

### Error Standardization
- All errors: `success: false`, `error: { code, message, details? }`
- Machine-readable codes: `NOT_FOUND`, `UNAUTHORIZED`, `FORBIDDEN`, `CONFLICT`, `VALIDATION_FAILED`, `UPGRADE_REQUIRED`

### Queue Payload Versioning
- All `QueueMessage<T>` payloads include a `version` field
- Consumers must handle the declared version; unknown versions are dead-lettered with a version mismatch error
- Breaking payload changes require a version bump; consumers must be updated before producers are deployed

### DTO Versioning
- DTOs internal to a single service: can change freely
- DTOs that cross service or module boundaries: require a new version field if the change is breaking

---

## 8. Architecture Drift Prevention

These violations are **blocking issues** in code review and Speckit validation — not comments:

| Rule | Violation Example |
|------|-----------------|
| No direct infrastructure in domain services | `AuthService` injecting `RedisTemplate` |
| No storage SDK outside `StorageService` | `S3Client` in `UploadService` |
| No `RabbitTemplate` outside `QueuePublisher` | `rabbitTemplate.convertAndSend(...)` in `ContentService` |
| No FFmpeg/FFprobe outside media worker | API service executing `ProcessBuilder("ffprobe")` |
| No business logic in `@RestController` | Subscription access check inside controller method body |
| No cross-domain repository injection | `AuthService` injecting `ContentRepository` |
| No `@Value` in service/use-case classes | `@Value("${jwt.secret}")` in `AuthService` |
| No `System.getenv()` in application code | `System.getenv("JWT_SECRET")` anywhere outside bootstrap config |

---

# BATCH FORMAT TEMPLATE

> Every new batch added to this roadmap must follow this template. Speckit will not execute a batch that omits required sections.

```markdown
# BATCH X — NAME

## Classification
FOUNDATION | SECURITY | DOMAIN | MEDIA | PLAYBACK | DISCOVERY | PAYMENT | PARTNER | ADMIN | OBSERVABILITY | SCALING

## Goal
One sentence: the business capability this batch delivers.

## Business Value
Why this batch exists — what the platform cannot do without it.

## Scope
What is IN scope (exact implementation boundary) and what is explicitly NOT in scope.

## Dependencies
dependsOn: []
blocks: []
requiredInfrastructure: []
crossServiceContracts: []

## Domains Affected
Auth | Media | Playback | Discovery | Billing | Partner | Admin | Notification | Analytics

## API Service Responsibilities
- List what the API service owns in this batch

## Worker Responsibilities
- List what the media worker does in this batch, or: None

## Shared Contracts
- List all DTOs, queue payloads, or API response shapes that cross service or module boundaries

## Database Changes
- List all new tables, columns, indexes, and Flyway migration file names

## Queue Contracts
- Queue name, message type, payload schema, version

## Cache Contracts
- Redis key pattern, TTL, invalidation trigger

## Security Requirements
- Auth guards, rate limits, token requirements, audit log entries

## Observability Requirements
- Logging expectations, metrics, health check additions

## Feature Flows
Describe the primary user/system flows implemented in this batch (numbered steps).

## API Endpoints
| Method | Path | Auth | Notes |

## Validation Requirements
- Input validation rules and edge cases to handle

## Completion Gates
- [ ] functionalValidation: ...
- [ ] securityValidation: ...
- [ ] integrationValidation: ...
- [ ] performanceValidation: ...
- [ ] rollbackReadiness: ...

## Rollback Considerations
How to safely revert this batch if needed (Flyway baseline, feature flag, etc.).

## Expected System Capability
What the system can do after this batch completes that it could not do before.
```

---

# PHASED ROADMAP ORDERING

> Phases define the strategic execution sequence. Every batch belongs to exactly one phase. A phase may not begin until all batches in the preceding phase are COMPLETE.

## PHASE 0 — FOUNDATION

**Purpose:** Lay the entire technical substrate before any business logic.
**Dependency reasoning:** No business feature can be implemented without a working build system, centralized configuration, database connection, queue infrastructure, storage abstraction, and security layer. Everything else depends on this phase being correct and stable.
**Business capability unlocked:** Both services start, connect to all infrastructure, serve a health check endpoint, and are ready to receive feature implementation.

| Batch | Name | Classification |
|-------|------|---------------|
| 0 | Core Foundation | FOUNDATION |

---

## PHASE 1 — IDENTITY & SECURITY

**Purpose:** Establish who can use the platform, how they are authenticated, and how their sessions are governed.
**Dependency reasoning:** Every downstream feature either requires an authenticated user, checks subscription state, or depends on role-based access. Auth must be architecturally complete — not just "working" — before any feature that relies on identity, sessions, or access control.
**Business capability unlocked:** Users can register, verify email, log in, refresh sessions, and log out. Admins are fully isolated with separate JWT secrets. Session/device governance and subscription-aware device limits are active.

| Batch | Name | Classification |
|-------|------|---------------|
| 1 | Authentication + User System | SECURITY |
| Auth Refactor | Multi-Actor Auth, RBAC, Session Governance, /auth/me Aggregation | SECURITY |

---

## PHASE 2 — CONTENT DOMAIN

**Purpose:** Define the core data model for all content the platform serves.
**Dependency reasoning:** Playback, search, discovery, analytics, and partner tools all depend on the content entity, category taxonomy, and content hierarchy existing. This phase creates the foundation that all content-facing features build on.
**Business capability unlocked:** Partners can create content entries. Admins can manage categories. Content status workflow (DRAFT → REVIEW → PUBLISHED → ARCHIVED) is operational. User profiles are manageable.

| Batch | Name | Classification |
|-------|------|---------------|
| 2 | User Profile + Settings | DOMAIN |
| 3 | Category + Discovery Foundation | DOMAIN |
| 4 | Content Core System | DOMAIN |
| 5 | Episodes + Series System | DOMAIN |

---

## PHASE 3 — MEDIA PIPELINE

**Purpose:** Enable raw video uploads and automated transcoding to adaptive HLS streams.
**Dependency reasoning:** Content cannot be published without a READY VideoAsset. The pipeline must be operational and end-to-end tested before playback, partner upload management, or content status transitions can be validated for the full flow.
**Business capability unlocked:** Partners can upload videos via presigned URLs. The worker transcodes to HLS variants (360p through 1080p). Content becomes streamable from the CDN.

| Batch | Name | Classification |
|-------|------|---------------|
| 6 | Upload Session System | MEDIA |
| 7 | Media Processing Worker | MEDIA |

---

## PHASE 4 — DISCOVERY & PLAYBACK

**Purpose:** Let users find content and stream it, and build their personal library.
**Dependency reasoning:** Playback requires READY VideoAssets (Phase 3). Discovery and search require published content (Phase 2). Progress tracking and favorites require an authenticated user (Phase 1). Recommendations require watch history.
**Business capability unlocked:** Users can search, browse categories, discover trending content, stream videos with subscription enforcement, track watch progress, manage favorites, and write reviews.

| Batch | Name | Classification |
|-------|------|---------------|
| 8 | Playback System | PLAYBACK |
| 9 | Search + Discovery | DISCOVERY |
| 10 | Favorites + Watch History | DISCOVERY |
| 11 | Ratings + Reviews | DISCOVERY |

---

## PHASE 5 — BILLING & ACCESS CONTROL

**Purpose:** Monetize the platform and enforce subscription-based content access.
**Dependency reasoning:** Subscription state gates playback access at the capability level. Payment webhooks must be wired before subscriptions can activate via real payment flow. Coupon validation requires an active plan catalog. Free-tier quota enforcement (from the Auth Refactor) requires subscription plans to exist.
**Business capability unlocked:** Users can subscribe to plans, apply discount coupons at checkout, and have their content access automatically enforced based on subscription status. Free tier users are subject to content watch quotas.

| Batch | Name | Classification |
|-------|------|---------------|
| 12 | Subscription + Billing | PAYMENT |

---

## PHASE 6 — PARTNER PLATFORM

**Purpose:** Provide creator tooling, content analytics, and revenue visibility to partners.
**Dependency reasoning:** Partner analytics requires analytics event ingestion (which depends on Phase 4 playback events). Revenue reporting requires subscription and payment data (Phase 5). Upload management depends on the complete media pipeline (Phase 3).
**Business capability unlocked:** Partners have a full dashboard — upload queue management, content performance analytics, and revenue reporting broken down by content.

| Batch | Name | Classification |
|-------|------|---------------|
| 13 | Partner Portal | PARTNER |

---

## PHASE 7 — ADMIN & MODERATION

**Purpose:** Give platform admins the tools to govern content, users, and platform state.
**Dependency reasoning:** Admin moderation requires content (Phase 2), users (Phase 1), and subscriptions (Phase 5) to exist at scale. Admin session revocation governance is provided by the Auth Refactor (Phase 1). Admin coupon management requires the billing domain (Phase 5).
**Business capability unlocked:** Admins can moderate the content review queue, manage user accounts, suspend or ban users with session revocation, feature/unfeature content, manage coupons, and view the platform-wide analytics dashboard.

| Batch | Name | Classification |
|-------|------|---------------|
| 14 | Admin Moderation System | ADMIN |

---

## PHASE 8 — OBSERVABILITY & HARDENING

**Purpose:** Make the platform production-ready: reliable, observable, automated, and resilient.
**Dependency reasoning:** Notification delivery, analytics aggregation, and background job automation depend on all domain data and events existing. Observability wraps everything — it is meaningless before the system has features to observe.
**Business capability unlocked:** Platform has structured JSON logging with trace IDs, health checks covering all infrastructure, automated background jobs (subscription expiration, token cleanup, analytics aggregation), event-driven notification delivery (email + in-app), and security hardening for production deployment.

| Batch | Name | Classification |
|-------|------|---------------|
| 15 | Notification System | OBSERVABILITY |
| 16 | Analytics System | OBSERVABILITY |
| 17 | Background Jobs + Automation | OBSERVABILITY |
| 18 | Observability + Production Hardening | OBSERVABILITY |

---

## PHASE 9 — SCALE & OPTIMIZATION

**Purpose:** Optimize for production traffic growth — caching strategy, index tuning, horizontal scaling, and upgrade paths.
**Dependency reasoning:** You cannot optimize what is not yet complete and production-validated. Scaling decisions require real load data and production metrics.
**Business capability unlocked:** Platform handles production traffic load. Worker scales horizontally without coordination. Cache hit rates are high. DB queries are indexed and profiled. Future capability upgrades (full-text search engine, WebSocket invalidation, suspicious login detection) have a clear integration path.

| Batch | Name | Classification |
|-------|------|---------------|
| Future | Search upgrade (Elasticsearch / Meilisearch) | SCALING |
| Future | WebSocket session invalidation | SCALING |
| Future | Suspicious login tracking + device analytics | SCALING |
| Future | Partner revenue automation + payout integration | SCALING |

---

# SPECKIT EXECUTION INSTRUCTIONS

> These instructions govern every Speckit run for TinnieStudio. They are mandatory and non-negotiable.

## Execution Model

Speckit executes **incrementally** — one batch at a time, one task at a time, one step at a time. There is no bulk mode. Every batch is a complete feature lifecycle from Speckit's perspective.

## The Five-Step Feature Loop

```
speckit-specify   → spec.md
                    (business requirement, acceptance criteria, domain boundaries, non-goals)
speckit-plan      → plan.md
                    (implementation design, file structure, design artifacts)
speckit-tasks     → tasks.md
                    (ordered, dependency-aware, TDD task list)
speckit-implement → execute tasks
                    (one task at a time, failing test first)
speckit-analyze   → cross-artifact consistency check
                    (spec ↔ plan ↔ tasks ↔ implementation alignment verified)
```

## Non-Negotiable Execution Rules

1. **No implementation without specification.** `speckit-specify` must produce an approved `spec.md` before `speckit-plan` is invoked.

2. **TDD is the implementation method.** Every task in `tasks.md` follows: write failing test → confirm fail → implement minimal code → confirm pass → commit. Code is never written before the failing test exists.

3. **One batch at a time.** `speckit-implement` processes tasks for the current batch only. Work discovered that belongs to a different batch is deferred and logged — never executed inline.

4. **Dependency state is checked before starting.** If any batch in the `dependsOn` list is not COMPLETE, the Speckit run is rejected. Fix the dependency state, then retry.

5. **Completion gates must all pass.** `speckit-implement` does not exit a batch until all five completion gates are verified (functional, security, integration, performance, rollback).

6. **Architecture drift is a blocking issue.** Any implementation choice that violates Section 8 (Architecture Drift Prevention) above is a blocker — not a code review comment. It must be fixed before the batch can be marked COMPLETE.

7. **No architecture drift is allowed.** All governance rules defined in this document and in `server/.specify/memory/constitution.md` are mandatory and override any default Spring Boot conventions or personal coding preferences.

8. **Queue contracts are versioned and immutable once published.** Any change to a queue payload schema requires a version bump and a migration plan for all consumers before the change is deployed.

## Enforcement

Speckit run failures are not bugs to work around. If a batch cannot pass its completion gates, the batch implementation is incomplete — not the gates. Fix the implementation, fix the drift, then re-run the gates.

---

# BATCH 0 [X] — CORE FOUNDATION
> **Goal:** Lay the entire architectural skeleton before any business logic.
> Both services must compile, start, and connect to all infrastructure before Batch 1 begins.

---

## 0.1 · Monorepo & Module Structure

### Technology
- Gradle multi-project build
- Docker Compose for local infra

### Directory Layout
```
/tinniestudio
  /api-service          ← Main Spring Boot API
    /src/main/java/com/tinniestudio/api
      /config
      /common
        /entity
        /exception
        /response
        /validation
        /pagination
      /modules           ← Feature modules live here
      /infra
        /storage
        /queue
        /cache
        /mail
  /media-worker         ← Standalone Spring Boot Worker(for easy extraction)
    /src/main/java/com/tinniestudio/worker
      /config
      /consumer
      /processor
      /ffmpeg
      /storage
  /docker
    docker-compose.yml
    docker-compose.override.yml
  build.gradle
  settings.gradle
```

### Deliverables
- `./gradlew :api-service:bootRun` works
- `./gradlew :media-worker:bootRun` works
- `docker-compose up` starts: PostgreSQL, RabbitMQ, Redis, MinIO (local S3)

---

## 0.2 · Centralized Configuration System

### Technology
- `@ConfigurationProperties` beans (never `@Value` directly)
- `application.yml` per profile: `local`, `dev`, `prod`

### Config Beans to Create
```
DatabaseConfig
RedisConfig
RabbitMQConfig
JwtConfig
  - accessTokenTtlSeconds
  - refreshTokenTtlSeconds
  - secret
StorageConfig
  - provider (S3 | R2)
  - bucket
  - region
  - endpoint
  - presignedUrlTtlSeconds
CdnConfig
  - baseUrl
MailConfig
  - from
  - provider
UploadConfig
  - maxFileSizeBytes
  - allowedMimeTypes
  - sessionTtlMinutes
PlaybackConfig
  - signedUrlEnabled
  - signedUrlTtlSeconds
```

### Rules
- Never access `System.getenv()` or `@Value("${...}")` inside service/use-case classes
- All config accessed via injected config beans

---

## 0.3 · Common Shared Components

### Base Entity
```java
@MappedSuperclass
public abstract class BaseEntity {
    UUID id;            // @GeneratedValue(UUID)
    Instant createdAt;  // @CreationTimestamp
    Instant updatedAt;  // @UpdateTimestamp
}
```

### Response Envelope
```json
{
  "success": true,
  "data": {},
  "error": null,
  "meta": { "page": 1, "limit": 20, "total": 100 }
}
```

### Exception Hierarchy
```
AppException (base)
├── NotFoundException (404)
├── UnauthorizedException (401)
├── ForbiddenException (403)Recommended order for you
Run exactly:
├── ValidationException (422)
├── ConflictException (409)
└── InternalException (500)
```

### Global Exception Handler
- `@RestControllerAdvice` maps all exceptions to the response envelope
- Logs 5xx errors with full stack; logs 4xx at WARN level

### Pagination Abstraction
- `PageRequest` DTO (page, limit, sortBy, sortOrder)
- `PageResult<T>` response wrapper

---

## 0.4 · Security Layer

### Technology
- Spring Security 6
- JWT (JJWT library)
- BCrypt password hashing

### JWT Flow
```
POST /auth/login
  → Validate credentials
  → Issue accessToken (15 min) in response body
  → Issue refreshToken (7 days) in HttpOnly Secure cookie
  → Store refreshToken hash in Redis (key = userId:tokenId)

Subsequent requests:
  Authorization: Bearer <accessToken>
  → JwtAuthFilter validates signature + expiry
  → Loads CurrentUser from token claims
  → Refresh rotation on /auth/refresh
```

### Role Hierarchy
```
SUPER_ADMIN > ADMIN > PARTNER > USER
```

### Permission System
- Role-based (`@PreAuthorize("hasRole('ADMIN')")`)
- Ownership checks inside service layer (not annotation-based)

### Rate Limiting
- Redis token bucket per IP + per user
- Configurable per endpoint group (auth = 10/min, api = 300/min)

### CurrentUser Abstraction
```java
@Component
public class CurrentUserProvider {
    public UserPrincipal get();       // from SecurityContext
    public UUID getUserId();
    public boolean hasRole(Role role);
}
```

---

## 0.5 · Storage Abstraction Layer

### Interface
```java
public interface StorageService {
    PresignedUrl generateUploadUrl(String key, String mimeType, long maxBytes, Duration ttl);
    PresignedUrl generateDownloadUrl(String key, Duration ttl);
    boolean objectExists(String key);
    void deleteObject(String key);
    void copyObject(String sourceKey, String destKey);
    ObjectMetadata getMetadata(String key);
}
```

### Implementations
- `S3StorageService` — AWS S3 via AWS SDK v2
- `R2StorageService` — Cloudflare R2 (S3-compatible)
- `MinioStorageService` — Local dev via MinIO

### Selection
- Controlled by `StorageConfig.provider` property
- `@ConditionalOnProperty` or `@Primary` bean selection

---

## 0.6 · Queue Infrastructure

### Technology
- RabbitMQ with Spring AMQP
- Dead Letter Exchange (DLX) pattern

### Exchange / Queue Topology
```
Exchange: tinniestudio.direct

Queues:
  media.video.process     → DLX: media.video.failed
  media.video.retry       → TTL + re-routes to media.video.process
  media.video.failed      → dead letters, manual review
  notifications.send
  analytics.ingest
```

### Message Envelope
```java
public class QueueMessage<T> {
    String messageId;   // UUID, for idempotency
    String type;
    Instant publishedAt;
    int attempt;
    T payload;
}
```

### Publisher Service
```java
public interface QueuePublisher {
    void publish(String queue, Object payload);
    void publishWithDelay(String queue, Object payload, Duration delay);
}
```

---

## 0.7 · Redis Infrastructure

### Key Namespacing Convention
```
tinnie:{module}:{key}
Examples:
  tinnie:auth:refresh:{userId}:{tokenId}   → refresh token hash, TTL 7d
  tinnie:auth:otp:{email}                  → OTP code, TTL 10min
  tinnie:rate:{ip}:{endpoint}              → token bucket counter
  tinnie:playback:{contentId}:{userId}     → signed URL cache, TTL 5min
  tinnie:upload:{sessionId}                → temp upload state, TTL 30min
```

### Redis Service
```java
public interface CacheService {
    void set(String key, Object value, Duration ttl);
    <T> Optional<T> get(String key, Class<T> type);
    void delete(String key);
    boolean exists(String key);
    void increment(String key, Duration ttl);
}
```

---

## 0.8 · Flyway Database Migrations

### Convention
```
V{batch}_{sequence}__{description}.sql
Examples:
  V1__create_users.sql
  V2__create_refresh_tokens.sql 
  V4__create_content.sql
```

## 0.9 Swagger doc

### Setup swagger docs
```
 Ensure proper documentation
```

### Rules
- Never edit existing migration files
- One migration file per entity or schema change
- Rollback scripts stored separately (not run automatically)

---

✅ **Batch 0 Complete When:**
- API service starts with all config loaded
- Worker service starts and connects to RabbitMQ
- PostgreSQL migrations run cleanly
- Storage abstraction tested against MinIO locally
- Queue can publish and consume a test message
- Redis connects and basic ops work

---

# BATCH 1 [X] — AUTHENTICATION + USER SYSTEM
> **Service:** API Service only
> **Goal:** Identity, session, and email verification foundation.

---

## 1.1 · Database Schema

### Entities + Migration Files

**users** table
```sql
id UUID PRIMARY KEY
email VARCHAR(255) UNIQUE NOT NULL
password_hash VARCHAR(255) NOT NULL
role VARCHAR(50) NOT NULL DEFAULT 'USER'
status VARCHAR(50) NOT NULL DEFAULT 'PENDING_VERIFICATION'
email_verified_at TIMESTAMP
created_at TIMESTAMP
updated_at TIMESTAMP
```

**refresh_tokens** table
```sql
id UUID PRIMARY KEY
user_id UUID REFERENCES users(id) ON DELETE CASCADE
token_hash VARCHAR(255) UNIQUE NOT NULL
expires_at TIMESTAMP NOT NULL
revoked_at TIMESTAMP
created_at TIMESTAMP
```

**email_verification_tokens** table
```sql
id UUID PRIMARY KEY
user_id UUID REFERENCES users(id)
token VARCHAR(255) UNIQUE NOT NULL
expires_at TIMESTAMP NOT NULL
used_at TIMESTAMP
```

**password_reset_tokens** table
```sql
id UUID PRIMARY KEY
user_id UUID REFERENCES users(id)
token_hash VARCHAR(255) UNIQUE NOT NULL
expires_at TIMESTAMP NOT NULL
used_at TIMESTAMP
```

---

## 1.2 · Feature Flow

### Registration Flow
```
POST /auth/register
  Request: { email, password, fullName }
  1. Validate email format + password strength
  2. Check email uniqueness → ConflictException if taken
  3. Hash password with BCrypt (strength 12)
  4. Create User (status=PENDING_VERIFICATION, role=USER)
  5. Generate email verification token (UUID, 24h TTL)
  6. Store token in email_verification_tokens
  7. Publish to notifications.send queue (WELCOME + VERIFY_EMAIL)
  8. Return 201 with user DTO (no token yet)
```

### Login Flow
```
POST /auth/login
  Request: { email, password }
  1. Load user by email (404 → generic "invalid credentials")
  2. Verify password hash
  3. Check account status (SUSPENDED → 403)
  4. Generate accessToken JWT (15 min)
  5. Generate refreshToken (UUID)
  6. Hash refreshToken, store in refresh_tokens table
  7. Cache refreshToken hash in Redis (TTL 7d)
  8. Set refreshToken in HttpOnly Secure SameSite=Strict cookie
  9. Return accessToken + user DTO in body
```

### Token Refresh Flow
```
POST /auth/refresh
  1. Read refreshToken from cookie
  2. Look up token hash in Redis first (fast path)
  3. Validate against DB (existence + expiry + not revoked)
  4. Revoke old refresh token
  5. Issue new accessToken + refreshToken (rotation)
  6. Return new accessToken
```

### Logout Flow
```
POST /auth/logout
  1. Read refreshToken from cookie
  2. Revoke in DB (set revoked_at)
  3. Delete from Redis
  4. Clear cookie
```

### Email Verification Flow
```
POST /auth/verify-email
  Request: { token }
  1. Look up token in email_verification_tokens
  2. Validate: exists, not used, not expired
  3. Mark token as used
  4. Set user.status = ACTIVE, user.email_verified_at = now()
  5. Return 200

POST /auth/resend-verification
  Request: { email }
  Rate limit: 3 per hour per email
  1. Load user by email
  2. Check not already verified
  3. Invalidate previous tokens
  4. Generate new token
  5. Publish to notification queue
```

### Forgot / Reset Password Flow
```
POST /auth/forgot-password
  Request: { email }
  Rate limit: 5 per hour per email
  1. Load user (silently succeed even if not found, for security)
  2. Generate reset token (UUID, 1h TTL)
  3. Hash and store in password_reset_tokens
  4. Publish RESET_PASSWORD notification

POST /auth/reset-password
  Request: { token, newPassword }
  1. Look up by token hash
  2. Validate not used + not expired
  3. Hash new password
  4. Update user.password_hash
  5. Mark token as used
  6. Revoke ALL refresh tokens for this user (security)
```

---

## 1.3 · API Endpoints

```
POST /auth/register             Public
POST /auth/login                Public
POST /auth/logout               Authenticated
POST /auth/refresh              Cookie (no auth header)
POST /auth/verify-email         Public
POST /auth/resend-verification  Public
POST /auth/forgot-password      Public
POST /auth/reset-password       Public
GET  /auth/me                   Authenticated
```

---

## 1.4 · DTOs

### RegisterRequest
```json
{ "email": "user@example.com", "password": "Min8chars1!", "fullName": "John Doe" }
```

### LoginResponse
```json
{
  "accessToken": "eyJ...",
  "user": { "id": "uuid", "email": "...", "role": "USER", "status": "ACTIVE" }
}
```

---

## 1.5 · Security Rules
- Passwords: min 8 chars, 1 uppercase, 1 number
- Tokens expire and are single-use
- All auth failures return identical error message (prevent enumeration)
- Account lockout after 10 failed logins within 15 min (Redis counter)

---

✅ **Batch 1 Complete When:**
- Full auth flow tested (register → verify → login → refresh → logout)
- Password reset tested end-to-end
- JWT validated on protected endpoints
- Role guard working (`/admin/*` blocked for USER role)

---

# BATCH 2 [X] — USER PROFILE + SETTINGS
> **Service:** API Service
> **Goal:** User identity, preferences, avatar.

---

## 2.1 · Database Schema

**user_profiles** table
```sql
user_id UUID PRIMARY KEY REFERENCES users(id)
full_name VARCHAR(255)
bio TEXT
avatar_url VARCHAR(500)
language_code VARCHAR(10) DEFAULT 'en'
country_code VARCHAR(10)
timezone VARCHAR(100)
notification_email BOOLEAN DEFAULT true
notification_push BOOLEAN DEFAULT true
notification_in_app BOOLEAN DEFAULT true
updated_at TIMESTAMP
```

---

## 2.2 · Feature Flow

### Get My Profile
```
GET /users/me
  → Join users + user_profiles
  → Return UserProfileDTO
```

### Update Profile
```
PATCH /users/me
  Request: { fullName?, bio?, languageCode?, countryCode?, timezone? }
  1. Validate fields
  2. Update user_profiles (upsert)
  3. Return updated profile
```

### Update Notification Preferences
```
PATCH /users/me/notifications
  Request: { email?, push?, inApp? }
  → Update notification flags in user_profiles
```

### Change Password
```
PATCH /users/me/password
  Request: { currentPassword, newPassword }
  1. Authenticate current password
  2. Validate new password strength
  3. Hash and update
  4. Revoke all refresh tokens except current session
```

### Avatar Upload
```
PATCH /users/me/avatar
  1. Create UploadSession (type=THUMBNAIL, entity=USER)
  2. Return presigned upload URL
  → Client uploads directly to storage
  → Client calls /uploads/:sessionId/complete
  → Backend updates user_profiles.avatar_url
```

---

## 2.3 · API Endpoints

```
GET    /users/me                  Authenticated
PATCH  /users/me                  Authenticated
PATCH  /users/me/preferences      Authenticated
PATCH  /users/me/notifications    Authenticated
PATCH  /users/me/password         Authenticated
PATCH  /users/me/avatar           Authenticated
```

---

✅ **Batch 2 Complete When:**
- Profile CRUD working
- Avatar upload session wired to storage (tested with local MinIO)
- Notification preferences persisted

---


# BATCH 12 [X] — SUBSCRIPTION + BILLING
> **Service:** API Service
> **Goal:** Monetization layer with plan management and payment integration.

---

## 12.1 · Database Schema

**subscription_plans** table
```sql
id UUID PRIMARY KEY
name VARCHAR(100) NOT NULL
slug VARCHAR(100) UNIQUE NOT NULL
description TEXT
price NUMERIC(10,2) NOT NULL
currency VARCHAR(10) DEFAULT 'NGN'
billing_cycle VARCHAR(20) NOT NULL   -- MONTHLY, YEARLY
max_devices INTEGER DEFAULT 1
video_quality VARCHAR(20)            -- SD, HD, FULL_HD
trial_days INTEGER DEFAULT 0
is_active BOOLEAN DEFAULT true
display_order INTEGER
created_at TIMESTAMP
```

**user_subscriptions** table
```sql
id UUID PRIMARY KEY
user_id UUID REFERENCES users(id)
plan_id UUID REFERENCES subscription_plans(id)
status VARCHAR(50) NOT NULL         -- ACTIVE, EXPIRED, CANCELLED, PAST_DUE, TRIALING
start_date TIMESTAMP NOT NULL
end_date TIMESTAMP NOT NULL
trial_ends_at TIMESTAMP
auto_renew BOOLEAN DEFAULT true
cancelled_at TIMESTAMP
created_at TIMESTAMP
updated_at TIMESTAMP
```

**payments** table
```sql
id UUID PRIMARY KEY
user_id UUID REFERENCES users(id)
subscription_id UUID REFERENCES user_subscriptions(id)
provider VARCHAR(50)               -- PAYSTACK, STRIPE, FLUTTERWAVE
provider_reference VARCHAR(255) UNIQUE
amount NUMERIC(10,2)
currency VARCHAR(10)
status VARCHAR(50)                 -- PENDING, SUCCESSFUL, FAILED, REFUNDED
paid_at TIMESTAMP
created_at TIMESTAMP
```

**coupons** table
```sql
id UUID PRIMARY KEY
code VARCHAR(100) UNIQUE NOT NULL
discount_type VARCHAR(20)          -- PERCENTAGE, FIXED
discount_value NUMERIC(10,2)
max_uses INTEGER
used_count INTEGER DEFAULT 0
plan_ids UUID[]                    -- null = all plans
expires_at TIMESTAMP
is_active BOOLEAN DEFAULT true
created_at TIMESTAMP
```

---

## 12.2 · Feature Flow

### Checkout Flow
```
POST /subscriptions/checkout
  Request: { planId, couponCode? }
  1. Load plan (must be active)
  2. Check no active subscription already exists
  3. If couponCode: validate coupon (exists, active, not expired, not maxed, plan eligible)
  4. Compute final price after discount
  5. Create pending payment record
  6. Call payment provider SDK to initialize payment
  7. Return { paymentUrl, paymentReference }

POST /webhooks/payment (Paystack/Stripe webhook)
  1. Verify webhook signature
  2. Load payment by provider_reference
  3. If SUCCESSFUL:
     - Update payment status=SUCCESSFUL
     - Create/activate UserSubscription
     - Publish SUBSCRIPTION_ACTIVATED notification
  4. If FAILED:
     - Update payment status=FAILED
     - Publish PAYMENT_FAILED notification
```

### Subscription Expiration (Background Job)
```
Scheduled daily (03:00):
  1. Find subscriptions where end_date < now() AND status=ACTIVE
  2. Set status=EXPIRED
  3. Publish SUBSCRIPTION_EXPIRED notification
```

### Cancel Subscription
```
PATCH /subscriptions/cancel
  1. Load user's active subscription
  2. Set auto_renew=false, cancelled_at=now()
  3. Status remains ACTIVE until end_date (no immediate cutoff)
  4. Publish SUBSCRIPTION_CANCELLED notification
```

---

## 12.3 · API Endpoints

```
GET  /subscriptions/plans           Public
POST /subscriptions/checkout        Authenticated
GET  /subscriptions/me              Authenticated
PATCH /subscriptions/cancel         Authenticated
POST /subscriptions/apply-coupon    Authenticated (validate only)
POST /webhooks/payment              Public (signature validated)
```

---

✅ **Batch 12 Complete When:**
- Plans seeded in DB
- Checkout creates payment and returns URL
- Webhook handler activates subscription
- Subscription expiry job runs and marks expired correctly
- Playback endpoint blocks user with expired subscription

---


# BATCH 3 [X] — CATEGORY + DISCOVERY FOUNDATION
> **Service:** API Service
> **Goal:** Content taxonomy and homepage configuration system.

---

## 3.1 · Database Schema

**categories** table
```sql
id UUID PRIMARY KEY
name VARCHAR(255) NOT NULL
slug VARCHAR(255) UNIQUE NOT NULL
description TEXT
poster_url VARCHAR(500)
is_active BOOLEAN DEFAULT true
display_order INTEGER
created_at TIMESTAMP
updated_at TIMESTAMP
```

**homepage_sections** table
```sql
id UUID PRIMARY KEY
title VARCHAR(255) NOT NULL
section_type VARCHAR(100) NOT NULL   -- TRENDING, FEATURED, CONTINUE_WATCHING, CATEGORY
category_id UUID REFERENCES categories(id) NULLABLE
display_order INTEGER
is_active BOOLEAN DEFAULT true
content_limit INTEGER DEFAULT 10
created_at TIMESTAMP
```

---

## 3.2 · Feature Flow

### Categories
- Slug auto-generated from name on create
- Soft delete (is_active = false), not hard delete
- Cache category list in Redis (TTL 10 min) → invalidate on any CUD operation

### Homepage Dynamic Sections
```
GET /discover/home
  1. Load active homepage_sections ordered by display_order
  2. For each section:
     - TRENDING → query most-viewed content last 7 days
     - FEATURED → query content where featured=true
     - CONTINUE_WATCHING → load user's watch progress (auth required)
     - CATEGORY → query published content for that category_id
  3. Return sections array with content lists
  4. Cache per section type (TTL 5 min)
     - CONTINUE_WATCHING is never cached (user-specific)
```

---

## 3.3 · API Endpoints

```
GET /categories                     Public
GET /categories/:slug               Public
POST /admin/categories              Admin
PATCH /admin/categories/:id         Admin
DELETE /admin/categories/:id        Admin

GET /discover/home                  Public (CONTINUE_WATCHING requires auth)
GET /discover/featured              Public
GET /discover/trending              Public
GET /discover/new-releases          Public
```

---

✅ **Batch 3 Complete When:**
- Category CRUD tested with admin role
- Homepage endpoint returns structured sections
- Redis caching on category list confirmed

---

# BATCH 4 [X] — CONTENT CORE SYSTEM
> **Service:** API Service
> **Goal:** Primary content domain (Movies, Series, Documentaries).

---

## 4.1 · Database Schema

**contents** table
```sql
id UUID PRIMARY KEY
title VARCHAR(255) NOT NULL
slug VARCHAR(255) UNIQUE NOT NULL
description TEXT
short_description VARCHAR(500)
type VARCHAR(50) NOT NULL        -- MOVIE, SERIES
status VARCHAR(50) NOT NULL DEFAULT 'DRAFT'
release_date DATE
language VARCHAR(50)
country VARCHAR(50)
duration_seconds INTEGER         -- null for SERIES
maturity_rating VARCHAR(20)      -- G, PG, PG-13, R
featured BOOLEAN DEFAULT false
poster_url VARCHAR(500)
thumbnail_url VARCHAR(500)
published_at TIMESTAMP
created_by UUID REFERENCES users(id)
created_at TIMESTAMP
updated_at TIMESTAMP
```

**content_categories** table
```sql
content_id UUID REFERENCES contents(id) ON DELETE CASCADE
category_id UUID REFERENCES categories(id) ON DELETE CASCADE
PRIMARY KEY (content_id, category_id)
```

**content_cast** table
```sql
id UUID PRIMARY KEY
content_id UUID REFERENCES contents(id) ON DELETE CASCADE
name VARCHAR(255) NOT NULL
role VARCHAR(100)               -- ACTOR, DIRECTOR, PRODUCER
character_name VARCHAR(255)
display_order INTEGER
```

---

## 4.2 · Content Status Workflow

```
DRAFT
  ↓ (partner submits)
REVIEW
  ↓ (admin approves)        ↓ (admin rejects)
PUBLISHED                REJECTED
  ↓ (partner unpublishes)
ARCHIVED
```

### Rules
- Only `PUBLISHED` content visible to regular users
- Partner can only see/edit their own content
- Admin can see all statuses
- Slug auto-generated from title; must be unique; append `-{n}` if collision

---

## 4.3 · Feature Flow

### Public Content Listing
```
GET /contents
  Query: page, limit, categorySlug, type, country, language, sort, featured
  1. Build dynamic query with filters
  2. Only return status=PUBLISHED
  3. Apply sorting: LATEST, POPULAR, RATING
  4. Return paginated result with minimal fields
  5. Cache response in Redis (TTL 2 min, key includes all query params)
```

### Partner Content Creation
```
POST /partner/contents
  Request: { title, description, type, releaseDate, language, country,
             maturityRating, categoryIds[], castMembers[] }
  1. Validate request DTO
  2. Set created_by = currentUser.id
  3. Generate slug
  4. Set status = DRAFT
  5. Create content + content_categories + content_cast in transaction
  6. Return ContentDetailDTO
```

### Publish Flow
```
PATCH /partner/contents/:id/publish
  1. Validate owner or admin
  2. Check content has at least one READY VideoAsset
  3. Change status to REVIEW
  4. Publish CONTENT_SUBMITTED notification to admin

PATCH /admin/contents/:id/approve
  1. Change status to PUBLISHED, set published_at
  2. Publish CONTENT_APPROVED notification to partner

PATCH /admin/contents/:id/reject
  Request: { reason }
  1. Change status to REJECTED
  2. Publish CONTENT_REJECTED notification with reason
```

---

## 4.4 · API Endpoints

```
GET /contents                         Public
GET /contents/:slug                   Public
POST /partner/contents                Partner
PATCH /partner/contents/:id           Partner (owner)
DELETE /partner/contents/:id          Partner (owner, DRAFT only)
PATCH /partner/contents/:id/publish   Partner (owner)
PATCH /partner/contents/:id/unpublish Partner (owner)
PATCH /admin/contents/:id/approve     Admin
PATCH /admin/contents/:id/reject      Admin
PATCH /admin/contents/:id/feature     Admin
```

---

✅ **Batch 4 Complete When:**
- Content CRUD working for partner role
- Status workflow tested (draft → review → published)
- Admin approve/reject working
- Filtering + pagination tested

---

# BATCH 5 [X] — EPISODES + SERIES SYSTEM
> **Service:** API Service
> **Goal:** Season and episode hierarchy for Series content.

---

## 5.1 · Database Schema

**seasons** table
```sql
id UUID PRIMARY KEY
content_id UUID REFERENCES contents(id) ON DELETE CASCADE
season_number INTEGER NOT NULL
title VARCHAR(255)
description TEXT
release_date DATE
poster_url VARCHAR(500)
thumbnail_url VARCHAR(500)
created_at TIMESTAMP
updated_at TIMESTAMP
UNIQUE (content_id, season_number)
```

**episodes** table
```sql
id UUID PRIMARY KEY
season_id UUID REFERENCES seasons(id) ON DELETE CASCADE
content_id UUID REFERENCES contents(id)
episode_number INTEGER NOT NULL
title VARCHAR(255) NOT NULL
description TEXT
duration_seconds INTEGER
thumbnail_url VARCHAR(500)
release_date DATE
created_at TIMESTAMP
updated_at TIMESTAMP
UNIQUE (season_id, episode_number)
```

---

## 5.2 · Feature Flow

### Season Creation
```
POST /partner/contents/:contentId/seasons
  Request: { seasonNumber, title, description, releaseDate }
  1. Validate content type = SERIES
  2. Validate ownership
  3. Check season_number uniqueness for this content
  4. Create season
```

### Episode Auto-Numbering
```
POST /partner/seasons/:seasonId/episodes
  Request: { title, description, releaseDate }
  (episodeNumber is optional; if absent, auto = max + 1)
  1. Validate season ownership
  2. Compute next episode number if not provided
  3. Create episode with status DRAFT
```

### Episode Reordering
```
PATCH /partner/seasons/:seasonId/episodes/reorder
  Request: { episodes: [{ id, episodeNumber }] }
  1. Validate all episode IDs belong to season
  2. Validate no number conflicts
  3. Bulk update in transaction
```

---

## 5.3 · API Endpoints

```
GET /contents/:contentId/seasons                  Public
POST /partner/contents/:contentId/seasons         Partner
PATCH /partner/seasons/:seasonId                  Partner
DELETE /partner/seasons/:seasonId                 Partner

GET /seasons/:seasonId/episodes                   Public
POST /partner/seasons/:seasonId/episodes          Partner
PATCH /partner/episodes/:id                       Partner
DELETE /partner/episodes/:id                      Partner
PATCH /partner/seasons/:seasonId/episodes/reorder Partner
```

---

✅ **Batch 5 Complete When:**
- Series → Season → Episode hierarchy working
- Auto episode numbering tested
- Reorder endpoint working

---

# BATCH 6 [X] — UPLOAD SESSION SYSTEM
> **Service:** API Service + minimal DB read in Worker
> **Goal:** Secure direct-to-bucket upload pipeline.

---

## 6.1 · Database Schema

**upload_sessions** table
```sql
id UUID PRIMARY KEY
user_id UUID REFERENCES users(id)
upload_type VARCHAR(50) NOT NULL    -- RAW_VIDEO, THUMBNAIL, SUBTITLE, TRAILER
target_entity_type VARCHAR(50)      -- CONTENT, SEASON, EPISODE, USER
target_entity_id UUID
storage_key VARCHAR(500) NOT NULL
original_filename VARCHAR(255)
mime_type VARCHAR(100)
expected_max_size_bytes BIGINT
upload_status VARCHAR(50) DEFAULT 'PENDING'
presigned_url TEXT                  -- stored for debugging, not returned after creation
expires_at TIMESTAMP NOT NULL
completed_at TIMESTAMP
created_at TIMESTAMP
```

**media_files** table
```sql
id UUID PRIMARY KEY
upload_session_id UUID REFERENCES upload_sessions(id)
user_id UUID REFERENCES users(id)
file_type VARCHAR(50)
storage_key VARCHAR(500) NOT NULL
original_filename VARCHAR(255)
mime_type VARCHAR(100)
file_size_bytes BIGINT
created_at TIMESTAMP
```

---

## 6.2 · Upload Session Flow

```
1. POST /uploads/sessions
   Request: {
     uploadType: "RAW_VIDEO",
     targetEntityType: "EPISODE",
     targetEntityId: "uuid",
     originalFilename: "episode1.mp4",
     mimeType: "video/mp4",
     fileSizeBytes: 2147483648
   }
   Validation:
     - Check user permissions for target entity
     - Validate mimeType against uploadType allowlist
     - Validate fileSizeBytes <= UploadConfig.maxFileSizeBytes
   Processing:
     - Generate storage key: raw/{uuid}/original.mp4
     - Generate presigned PUT URL (TTL from UploadConfig)
     - Create UploadSession (status=PENDING)
     - Cache session state in Redis (key: tinnie:upload:{sessionId})
   Response:
     { sessionId, uploadUrl, storageKey, expiresAt }

2. Client uploads directly to bucket (no backend involvement)

3. POST /uploads/:sessionId/complete
   Processing:
     - Load UploadSession
     - Validate status=PENDING and not expired
     - Call storageService.objectExists(storageKey) → 422 if missing
     - Create MediaFile record
     - Update UploadSession (status=COMPLETED, completedAt=now())
     - If uploadType=RAW_VIDEO or TRAILER:
         → Create VideoAsset (processingStatus=PENDING)
         → Publish to media.video.process queue
     - If uploadType=THUMBNAIL:
         → Update target entity poster_url / thumbnail_url
     - If uploadType=SUBTITLE:
         → Create Subtitle record (format detected from filename)
   Response: { mediaFileId, videoAssetId? }

4. GET /uploads/:sessionId/status
   → Return session status + associated videoAsset processingStatus
```

---

## 6.3 · Upload Type Allowlists

| uploadType | Allowed MIME Types | Max Size |
|-----------|-------------------|---------|
| RAW_VIDEO | video/mp4, video/quicktime, video/x-matroska | 10 GB |
| TRAILER | video/mp4, video/quicktime | 2 GB |
| THUMBNAIL | image/jpeg, image/png, image/webp | 10 MB |
| SUBTITLE | text/vtt, application/x-subrip | 5 MB |

---

## 6.4 · API Endpoints

```
POST /uploads/sessions             Partner / Admin
POST /uploads/:sessionId/complete  Partner / Admin (owner)
GET  /uploads/:sessionId/status    Partner / Admin (owner)
```

---

✅ **Batch 6 Complete When:**
- Upload session created, presigned URL generated
- Client can upload directly to MinIO in local env
- Completion endpoint verifies object and creates VideoAsset
- VideoAsset published to RabbitMQ queue (verify with RabbitMQ UI)

---

# BATCH 7 [X] — MEDIA PROCESSING WORKER
> **Service:** Media Worker Service (standalone Spring Boot app)
> **Goal:** Async FFmpeg pipeline consuming from RabbitMQ.

---

## 7.1 · Worker Database Entities

**video_assets** table (shared with API, written by worker)
```sql
id UUID PRIMARY KEY
content_id UUID REFERENCES contents(id) NULLABLE
season_id UUID REFERENCES seasons(id) NULLABLE
episode_id UUID REFERENCES episodes(id) NULLABLE
upload_session_id UUID REFERENCES upload_sessions(id)
asset_type VARCHAR(50)           -- MAIN_VIDEO, TRAILER
raw_storage_key VARCHAR(500) NOT NULL
manifest_key VARCHAR(500)
duration_seconds INTEGER
width INTEGER
height INTEGER
codec VARCHAR(100)
bitrate BIGINT
file_size_bytes BIGINT
processing_status VARCHAR(50) DEFAULT 'PENDING'
processing_error TEXT
processing_attempts INTEGER DEFAULT 0
created_at TIMESTAMP
updated_at TIMESTAMP
```

**video_variants** table
```sql
id UUID PRIMARY KEY
video_asset_id UUID REFERENCES video_assets(id) ON DELETE CASCADE
resolution VARCHAR(20)          -- 1080p, 720p, etc.
width INTEGER
height INTEGER
bitrate BIGINT
manifest_key VARCHAR(500)
segment_count INTEGER
created_at TIMESTAMP
```

**processing_jobs** table
```sql
id UUID PRIMARY KEY
video_asset_id UUID REFERENCES video_assets(id)
job_id VARCHAR(255) UNIQUE       -- from RabbitMQ message
status VARCHAR(50)               -- VALIDATING, DOWNLOADING, TRANSCODING, UPLOADING, FINALIZING, DONE, FAILED
stage_started_at TIMESTAMP
completed_at TIMESTAMP
error_message TEXT
attempt INTEGER DEFAULT 1
created_at TIMESTAMP
```

---

## 7.2 · Worker Processing Pipeline

### Job Consumer
```java
@RabbitListener(queues = "media.video.process")
public void consumeProcessingJob(MediaProcessingJob job) {
    // Idempotency: check if already processed
    if (processingJobRepo.existsByJobIdAndStatus(job.getJobId(), DONE)) return;

    // Update VideoAsset: status=PROCESSING
    processVideo(job);
}
```

### Full Processing Lifecycle
```
1. VALIDATING
   - Update VideoAsset status=PROCESSING
   - Create ProcessingJob record
   - Load VideoAsset from DB

2. DOWNLOADING
   - Create temp directory: /tmp/tinniestudio/jobs/{jobId}/
   - Download raw file from object storage to /tmp/tinniestudio/jobs/{jobId}/input.mp4
   - Verify file hash if available

3. PROBING (FFprobe)
   - Execute: ffprobe -v quiet -print_format json -show_streams -show_format {input}
   - Extract: duration, width, height, codec, bitrate, fps, container, hasAudio
   - Validate:
       - Container in [mp4, mov, mkv]
       - Has video stream
       - Has audio stream
       - Duration <= maxAllowedDuration (from config)
   - Non-retryable validation failure → status=FAILED, no retry

4. RESOLUTION LADDER PLANNING
   - source >= 1080p: generate [1080, 720, 480, 360]
   - source >= 720p:  generate [720, 480, 360]
   - source >= 480p:  generate [480, 360]
   - source < 480p:   generate [360] (never upscale)

5. TRANSCODING (FFmpeg)
   For each resolution in ladder:
   Execute FFmpeg:
   ffmpeg -i {input}
     -vf scale={width}:{height}
     -c:v libx264 -b:v {videoBitrate} -maxrate {maxRate} -bufsize {bufsize}
     -c:a aac -b:a {audioBitrate}
     -hls_time 6
     -hls_playlist_type vod
     -hls_segment_filename {jobDir}/{res}/segment_%04d.ts
     {jobDir}/{res}/playlist.m3u8

   Bitrate Ladder:
   | Resolution | Video  | Audio |
   |------------|--------|-------|
   | 1080p      | 5000k  | 192k  |
   | 720p       | 2800k  | 128k  |
   | 480p       | 1400k  | 128k  |
   | 360p       | 800k   | 96k   |

6. MASTER PLAYLIST GENERATION
   Generate HLS master.m3u8 linking all variant playlists
   #EXTM3U
   #EXT-X-VERSION:3
   #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
   1080p/playlist.m3u8
   ...

7. THUMBNAIL GENERATION
   ffmpeg -i {input} -ss 00:00:05 -vframes 1 -q:v 2 thumbnail.jpg

8. UPLOADING_OUTPUT
   For each resolution:
     Upload segments: processed/{videoAssetId}/{res}/segment_*.ts
     Upload playlist: processed/{videoAssetId}/{res}/playlist.m3u8
   Upload master: processed/{videoAssetId}/master.m3u8
   Upload thumbnail: thumbnails/{contentId}/poster.jpg

9. FINALIZING
   - Persist VideoVariant records per resolution
   - Update VideoAsset:
       manifestKey = processed/{videoAssetId}/master.m3u8
       processingStatus = READY
       duration, width, height, codec, bitrate (from probe)
   - Update ProcessingJob: status=DONE
   - Publish CONTENT_PROCESSED notification to notifications queue

10. CLEANUP
    - Delete /tmp/tinniestudio/jobs/{jobId}/ recursively
```

---

## 7.3 · Retry Strategy

```
Max attempts: 3
Retry delay:
  Attempt 2 → 1 min (via media.video.retry queue with TTL)
  Attempt 3 → 5 min

Retryable errors:
  - Network failures (download/upload)
  - Storage timeouts
  - Worker crash recovery

Non-retryable errors (set FAILED immediately):
  - Invalid codec
  - Corrupt file
  - FFprobe validation failure
  - Missing audio stream

Dead Letter:
  After attempt 3 fails → route to media.video.failed
  → Admin notified via PROCESSING_FAILED notification
```

---

## 7.4 · FFmpeg / FFprobe Validation on Startup
```java
@PostConstruct
public void validateTools() {
    assertCommandAvailable("ffmpeg -version");
    assertCommandAvailable("ffprobe -version");
    log.info("FFmpeg tools validated successfully");
}
```

---

## 7.5 · Worker Configuration
```yaml
worker:
  processing:
    maxJobConcurrency: 2          # parallel jobs
    tempDir: /tmp/tinniestudio
    maxDurationSeconds: 14400     # 4 hours
    retryDelayMinutes: [1, 5]
  ffmpeg:
    path: /usr/bin/ffmpeg
    ffprobePath: /usr/bin/ffprobe
    hlsSegmentDuration: 6
```

---

✅ **Batch 7 Complete When:**
- Worker consumes job from RabbitMQ
- FFprobe extracts valid metadata from test MP4
- FFmpeg produces HLS output at all applicable resolutions
- Processed files uploaded to MinIO
- VideoAsset status updated to READY in DB
- Retry logic tested (force failure → verify retry count)
- Temp directory cleaned up after job

---

# BATCH 8 [X] — PLAYBACK SYSTEM
> **Service:** API Service
> **Goal:** Secure adaptive streaming with subscription enforcement.

---

## 8.1 · Database Schema

**watch_progress** table
```sql
id UUID PRIMARY KEY
user_id UUID REFERENCES users(id)
content_id UUID REFERENCES contents(id) NULLABLE
episode_id UUID REFERENCES episodes(id) NULLABLE
video_asset_id UUID REFERENCES video_assets(id)
progress_seconds INTEGER NOT NULL DEFAULT 0
duration_seconds INTEGER NOT NULL
completed BOOLEAN DEFAULT false
completion_percentage NUMERIC(5,2)
last_watched_at TIMESTAMP NOT NULL
device_type VARCHAR(50)
created_at TIMESTAMP
updated_at TIMESTAMP
UNIQUE (user_id, content_id)          -- one row per user/content (movie)
UNIQUE (user_id, episode_id)          -- one row per user/episode
```

---

## 8.2 · Playback Flow

### Access Validation
```
GET /playback/:contentId/access
  1. Authenticate user
  2. Load content (must be PUBLISHED)
  3. Load user subscription → must be ACTIVE
  4. Check plan.videoQuality matches content requirements
  5. Check geo restriction (if content has country restrictions)
  6. Return { hasAccess: true/false, reason? }
```

### Manifest Delivery
```
GET /playback/:contentId/manifest
  1. Run access validation (same as above, inline)
  2. Load VideoAsset where status=READY and type=MAIN_VIDEO
  3. Construct playback URL:
       playbackUrl = CdnConfig.baseUrl + "/" + videoAsset.manifestKey
  4. Load subtitles for this videoAsset
  5. Load watch progress for this user/content
  6. Return PlaybackDTO:
     {
       manifestUrl: "https://cdn.tinniestudio.com/processed/{id}/master.m3u8",
       subtitles: [{ languageCode, label, url, isDefault }],
       resumeAt: 1234,     ← from watch progress
       duration: 5400
     }
```

### Progress Tracking
```
POST /playback/progress
  Request: { contentId?, episodeId?, progressSeconds, durationSeconds, deviceType }
  1. Upsert watch_progress record
  2. Compute completion_percentage = (progress / duration) * 100
  3. If completion_percentage >= 90: set completed=true
  4. Update last_watched_at = now()
  5. Publish to analytics.ingest queue (async)
  6. Return 204

GET /playback/continue-watching
  1. Load watch_progress where completed=false, ordered by last_watched_at DESC
  2. Limit 20
  3. Join content/episode data
  4. Return list with progress info
```

---

## 8.3 · API Endpoints

```
GET  /playback/:contentId/access        Authenticated
GET  /playback/:contentId/manifest      Authenticated
GET  /playback/episode/:episodeId/manifest  Authenticated
POST /playback/progress                 Authenticated
GET  /playback/continue-watching        Authenticated
```

---

✅ **Batch 8 Complete When:**
- Manifest URL returned for READY video asset
- Subscription check blocks access for expired users
- Progress persisted and continue-watching list populated
- HLS manifest plays in VLC/browser with correct CDN URL

---

# BATCH 9 [X] — SEARCH + DISCOVERY
> **Service:** API Service
> **Goal:** Content discovery with full-text and filter-based search.

---

## 9.1 · Search Implementation

### Technology
- PostgreSQL full-text search (`tsvector` + `tsquery`) for MVP
- Future: Elasticsearch / Meilisearch upgrade path

### Full-Text Index
```sql
ALTER TABLE contents ADD COLUMN search_vector tsvector;

CREATE INDEX idx_contents_search ON contents USING GIN(search_vector);

UPDATE contents SET search_vector =
  setweight(to_tsvector('english', coalesce(title,'')), 'A') ||
  setweight(to_tsvector('english', coalesce(description,'')), 'B');

CREATE TRIGGER update_search_vector
BEFORE INSERT OR UPDATE ON contents
FOR EACH ROW EXECUTE FUNCTION update_content_search_vector();
```

### Search Query
```
GET /search
  Query params: q, type, categorySlug, language, country, sort, page, limit
  1. Validate q (min 2 chars)
  2. Build tsquery from q
  3. Apply filters (type, category, language, country)
  4. WHERE status = 'PUBLISHED'
  5. ORDER BY ts_rank or LATEST or POPULAR
  6. Paginate + return
  7. Cache results per normalized query string (TTL 60s)
```

---

## 9.2 · Recommendations

```
GET /discover/recommended
  Algorithm (MVP — rule-based):
  1. Load user's watch history (last 30 items)
  2. Extract most-watched category IDs
  3. Load content in those categories NOT already watched
  4. Supplement with trending content
  5. Deduplicate + limit 20
  6. Cache per userId (TTL 10 min)
```

---

## 9.3 · API Endpoints

```
GET /search               Public (enhanced results for authenticated)
GET /discover/recommended Authenticated
```

---

✅ **Batch 9 Complete When:**
- Search returns ranked results for text query
- Category + language filters working
- Recommended endpoint returns relevant content for a user with watch history

---

# BATCH 10 [X] — FAVORITES + WATCH HISTORY
> **Service:** API Service
> **Goal:** User engagement and library.

---

## 10.1 · Database Schema

**favorites** table
```sql
id UUID PRIMARY KEY
user_id UUID REFERENCES users(id) ON DELETE CASCADE
content_id UUID REFERENCES contents(id) ON DELETE CASCADE
created_at TIMESTAMP
UNIQUE (user_id, content_id)
```

**watch_history** table
```sql
id UUID PRIMARY KEY
user_id UUID REFERENCES users(id) ON DELETE CASCADE
content_id UUID REFERENCES contents(id)
episode_id UUID REFERENCES episodes(id) NULLABLE
watched_at TIMESTAMP NOT NULL
progress_seconds INTEGER
duration_seconds INTEGER
device_type VARCHAR(50)
created_at TIMESTAMP
```

---

## 10.2 · Feature Flow

### Favorites
- Toggle: POST creates if not exists, returns 409 if already exists
- DELETE removes entry
- GET returns paginated list with content details
- Max favorites per user: 500 (configurable)

### Watch History
```
GET /history
  1. Load watch_history for current user
  2. Ordered by watched_at DESC
  3. Join content/episode details
  4. Paginate (default limit 50)

DELETE /history/:id
  1. Validate ownership
  2. Hard delete the history entry

DELETE /history (clear all)
  1. Delete all history for current user
```

---

## 10.3 · API Endpoints

```
GET    /favorites                 Authenticated
POST   /favorites/:contentId      Authenticated
DELETE /favorites/:contentId      Authenticated

GET    /history                   Authenticated
DELETE /history/:id               Authenticated
DELETE /history                   Authenticated
```

---

✅ **Batch 10 Complete When:**
- Favorites add/remove/list working
- Watch history populated from playback progress
- Clear all history working

---

# BATCH 11 — RATINGS + REVIEWS
> **Service:** API Service
> **Goal:** Trust layer for content.

---

## 11.1 · Database Schema

**reviews** table
```sql
id UUID PRIMARY KEY
user_id UUID REFERENCES users(id)
content_id UUID REFERENCES contents(id)
rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5)
body TEXT
status VARCHAR(50) DEFAULT 'PUBLISHED'   -- PUBLISHED, FLAGGED, REMOVED
created_at TIMESTAMP
updated_at TIMESTAMP
UNIQUE (user_id, content_id)
```

**content_rating_aggregates** table (materialized)
```sql
content_id UUID PRIMARY KEY REFERENCES contents(id)
average_rating NUMERIC(3,2)
total_reviews INTEGER
updated_at TIMESTAMP
```

---

## 11.2 · Feature Flow

### Review Submission
```
POST /contents/:contentId/reviews
  Request: { rating: 4, body: "Great movie!" }
  1. Validate user has ACTIVE subscription (can't review without access)
  2. Check no existing review (unique constraint)
  3. Insert review
  4. Trigger aggregate recalculation (async via analytics queue)
  5. Return 201
```

### Rating Aggregation (async)
```
analytics.ingest queue consumer:
  On REVIEW_CREATED / REVIEW_UPDATED / REVIEW_DELETED:
  UPDATE content_rating_aggregates
  SET average_rating = (SELECT AVG(rating) FROM reviews WHERE content_id = ?),
      total_reviews = (SELECT COUNT(*) FROM reviews WHERE content_id = ?)
```

### Moderation
```
PATCH /admin/reviews/:id/status
  Admin sets status to FLAGGED or REMOVED
  Removed reviews excluded from aggregates
```

---

## 11.3 · API Endpoints

```
GET /contents/:contentId/reviews     Public
POST /contents/:contentId/reviews    Authenticated
PATCH /reviews/:id                   Authenticated (owner)
DELETE /reviews/:id                  Authenticated (owner) or Admin
PATCH /admin/reviews/:id/status      Admin
```

---

✅ **Batch 11 Complete When:**
- One review per user/content enforced
- Aggregate rating updates after review create/update/delete
- Admin moderation flag working

---

# BATCH 13 — PARTNER PORTAL
> **Service:** API Service
> **Goal:** Creator dashboard, content analytics, revenue reporting.

---

## 13.1 · Database Schema

**partner_profiles** table
```sql
id UUID PRIMARY KEY
user_id UUID REFERENCES users(id) UNIQUE
company_name VARCHAR(255)
website_url VARCHAR(500)
bio TEXT
logo_url VARCHAR(500)
revenue_share_percentage NUMERIC(5,2) DEFAULT 70.00
is_verified BOOLEAN DEFAULT false
created_at TIMESTAMP
updated_at TIMESTAMP
```

**revenue_reports** table
```sql
id UUID PRIMARY KEY
partner_id UUID REFERENCES partner_profiles(id)
period_start DATE
period_end DATE
total_views BIGINT DEFAULT 0
watch_minutes BIGINT DEFAULT 0
gross_revenue NUMERIC(12,2) DEFAULT 0
partner_share NUMERIC(12,2) DEFAULT 0
status VARCHAR(50) DEFAULT 'DRAFT'   -- DRAFT, FINALIZED, PAID
created_at TIMESTAMP
```

---

## 13.2 · Feature Flow

### Partner Dashboard
```
GET /partner/dashboard
  Returns:
  - Total published content count
  - Content in review count
  - Total views (last 30 days)
  - Total watch minutes (last 30 days)
  - Active uploads (processing)
  - Revenue this month (estimate)
  - Recent activity feed
```

### Partner Analytics
```
GET /partner/analytics
  Query: contentId?, dateFrom?, dateTo?, granularity (DAY|WEEK|MONTH)
  - Views over time series
  - Watch time over time series
  - Top performing content
  - Completion rates per content
```

### Upload Management
```
GET /partner/uploads
  - All upload sessions for partner's content
  - Include VideoAsset processingStatus
  - Filter by status (PENDING, PROCESSING, READY, FAILED)
```

---

## 13.3 · API Endpoints

```
GET /partner/dashboard    Partner
GET /partner/analytics    Partner
GET /partner/uploads      Partner
GET /partner/contents     Partner
GET /partner/revenue      Partner
```

---

✅ **Batch 13 Complete When:**
- Partner dashboard returns correct aggregated stats
- Upload list shows processing states
- Revenue report endpoint returns data

---

# BATCH 14 — ADMIN MODERATION SYSTEM
> **Service:** API Service
> **Goal:** Platform governance, content moderation, user management.

---

## 14.1 · Feature Flow

### Admin Dashboard
```
GET /admin/dashboard
  Returns:
  - Total users (total, new this week)
  - Active subscriptions
  - Revenue this month
  - Content in review queue count
  - Processing failures count
  - Storage usage
  - Queue depths (via RabbitMQ management API)
```

### User Management
```
GET /admin/users
  Filter: role, status, search, page, limit
  → Full user list with profile info

PATCH /admin/users/:id/status
  Actions: SUSPEND, ACTIVATE, BAN
  1. Update user.status
  2. If SUSPEND/BAN: revoke all refresh tokens
  3. Log admin action in audit_log table
```

### Content Moderation Queue
```
GET /admin/uploads/processing
  → All VideoAssets where processingStatus IN (PROCESSING, FAILED)
  → Include partner info, content info

PATCH /admin/contents/:id/feature
  Request: { featured: true/false }
  → Toggle content.featured flag
  → Invalidate homepage cache in Redis
```

### Audit Log
```sql
audit_logs table:
  id UUID
  actor_id UUID (admin user)
  action VARCHAR(100)   -- USER_SUSPENDED, CONTENT_APPROVED, etc.
  target_type VARCHAR(50)
  target_id UUID
  reason TEXT
  metadata JSONB
  created_at TIMESTAMP
```

---

## 14.2 · API Endpoints

```
GET /admin/dashboard                      Admin
GET /admin/users                          Admin
GET /admin/users/:id                      Admin
PATCH /admin/users/:id                    Admin
PATCH /admin/users/:id/status             Admin
GET /admin/uploads/processing             Admin
PATCH /admin/contents/:id/approve         Admin
PATCH /admin/contents/:id/reject          Admin
PATCH /admin/contents/:id/feature         Admin
```

---

✅ **Batch 14 Complete When:**
- Admin can list, suspend, and ban users
- Content moderation queue shows processing failures
- Feature toggle works and invalidates homepage cache
- Audit log captures all admin actions

---

# BATCH 15 — NOTIFICATION SYSTEM
> **Service:** API Service (consumer) + both services (publishers)
> **Goal:** Event-driven, multi-channel notification delivery.

---

## 15.1 · Database Schema

**notifications** table
```sql
id UUID PRIMARY KEY
user_id UUID REFERENCES users(id)
type VARCHAR(100) NOT NULL
title VARCHAR(255)
message TEXT
channel VARCHAR(50)   -- IN_APP, EMAIL, PUSH
read BOOLEAN DEFAULT false
read_at TIMESTAMP
metadata JSONB
created_at TIMESTAMP
```

**notification_templates** table
```sql
id UUID PRIMARY KEY
event_type VARCHAR(100) UNIQUE NOT NULL
subject_template TEXT
body_template TEXT
channels VARCHAR(50)[]
created_at TIMESTAMP
```

---

## 15.2 · Notification Consumer

### Queue: notifications.send
```java
@RabbitListener(queues = "notifications.send")
public void handleNotification(NotificationMessage msg) {
    // Load user notification preferences
    // Load template for event type
    // For each enabled channel:
    //   IN_APP → insert notification record
    //   EMAIL  → send via mail provider (SendGrid/SES)
    //   PUSH   → send via FCM/APNs (future)
}
```

### Event Types + Triggers

| Event | Trigger Point | Channels |
|-------|--------------|---------|
| WELCOME | Registration | EMAIL |
| EMAIL_VERIFICATION | Registration / Resend | EMAIL |
| PASSWORD_RESET | Forgot password | EMAIL |
| CONTENT_SUBMITTED | Partner publishes | IN_APP |
| CONTENT_APPROVED | Admin approves | EMAIL, IN_APP |
| CONTENT_REJECTED | Admin rejects | EMAIL, IN_APP |
| PROCESSING_COMPLETE | Worker finishes | IN_APP |
| PROCESSING_FAILED | Worker fails all retries | EMAIL, IN_APP |
| SUBSCRIPTION_ACTIVATED | Checkout webhook | EMAIL |
| SUBSCRIPTION_EXPIRING_SOON | 3 days before end_date | EMAIL, IN_APP |
| SUBSCRIPTION_EXPIRED | Expiry job | IN_APP |
| PAYMENT_FAILED | Checkout webhook | EMAIL |

---

## 15.3 · API Endpoints

```
GET /notifications                  Authenticated
PATCH /notifications/:id/read       Authenticated
PATCH /notifications/read-all       Authenticated
```

---

✅ **Batch 15 Complete When:**
- Notification consumer active and processing queue
- Email sends via configured provider (test with Mailtrap locally)
- In-app notifications retrievable via API
- read/read-all working

---

# BATCH 16 — ANALYTICS SYSTEM
> **Service:** API Service + background aggregation
> **Goal:** Platform intelligence and reporting.

---

## 16.1 · Database Schema

**analytics_events** table (append-only, high volume)
```sql
id UUID PRIMARY KEY
event_type VARCHAR(100) NOT NULL
user_id UUID
content_id UUID
episode_id UUID
session_id UUID
value NUMERIC
metadata JSONB
occurred_at TIMESTAMP NOT NULL
```

**content_analytics_daily** table (aggregated)
```sql
content_id UUID
date DATE
view_count BIGINT DEFAULT 0
unique_viewers BIGINT DEFAULT 0
watch_minutes BIGINT DEFAULT 0
completion_rate NUMERIC(5,2)
PRIMARY KEY (content_id, date)
```

**platform_analytics_daily** table
```sql
date DATE PRIMARY KEY
total_views BIGINT
active_users BIGINT
new_subscriptions INTEGER
revenue NUMERIC(12,2)
```

---

## 16.2 · Event Ingestion

```
analytics.ingest queue consumer:
  Events: VIEW_START, PROGRESS_UPDATE, VIEW_COMPLETE, REVIEW_CREATED
  → Insert into analytics_events (bulk insert, 100ms batching)

Aggregation job (runs hourly):
  → GROUP analytics_events by content_id + date
  → UPSERT into content_analytics_daily
```

---

## 16.3 · API Endpoints

```
GET /admin/analytics/platform       Admin
GET /admin/analytics/content        Admin
GET /admin/analytics/users          Admin
GET /admin/analytics/revenue        Admin
GET /partner/analytics              Partner (own content only)
```

---

✅ **Batch 16 Complete When:**
- View events captured on playback start
- Aggregation job populates daily tables
- Analytics endpoints return correct data

---

# BATCH 17 — BACKGROUND JOBS + AUTOMATION
> **Service:** Both
> **Goal:** Reliability, cleanup, and automation.

---

## 17.1 · API Service Jobs

| Job | Schedule | Logic |
|-----|----------|-------|
| ExpiredUploadCleanup | Every 30 min | Delete UploadSession where expires_at < now() AND status=PENDING; delete orphaned storage objects |
| TokenCleanup | Daily 02:00 | Delete used/expired email_verification_tokens, password_reset_tokens; delete revoked refresh_tokens |
| SubscriptionExpiration | Daily 03:00 | Set ACTIVE subscriptions with end_date < now() to EXPIRED; notify users |
| SubscriptionExpiringReminder | Daily 08:00 | Find subscriptions expiring in 3 days; publish SUBSCRIPTION_EXPIRING_SOON |
| AnalyticsAggregation | Hourly | Aggregate raw analytics_events into daily tables |
| NotificationRetry | Every 15 min | Retry failed notification deliveries (max 3 attempts) |

---

## 17.2 · Worker Jobs

| Job | Schedule | Logic |
|-----|----------|-------|
| TempFileCleanup | Every hour | Delete /tmp/tinniestudio/jobs/* older than 2 hours |
| StaleJobRecovery | Every 30 min | Find ProcessingJobs stuck in non-terminal state > 60 min; re-publish to queue |
| OrphanedAssetCleanup | Daily 04:00 | Find VideoAssets in FAILED state > 7 days old; move raw files to cold storage |
| DLQReview | Daily 06:00 | Check media.video.failed queue depth; alert if > 0 |

---

## 17.3 · Job Standards

- All jobs use `@Scheduled` with cron or fixed-rate
- Distributed lock via Redis (`SETNX tinnie:lock:{jobName}`) before execution
- Lock TTL = max expected job runtime + buffer
- All jobs fully idempotent (safe to run multiple times)
- All jobs log start/end with duration
- Failed job execution logged but does not crash application

---

✅ **Batch 17 Complete When:**
- All scheduled jobs start without error on boot
- Upload cleanup job deletes expired sessions
- Subscription expiration job tested with mock expired subscription
- Worker temp cleanup removes stale directories

---

# BATCH 18 — OBSERVABILITY + PRODUCTION HARDENING
> **Service:** Both
> **Goal:** Production-readiness, security, performance.

---

## 18.1 · Structured Logging

```java
// Every request logs:
{
  "timestamp": "2025-01-01T00:00:00Z",
  "level": "INFO",
  "traceId": "uuid",
  "userId": "uuid",
  "method": "POST",
  "path": "/api/v1/uploads/sessions",
  "status": 201,
  "durationMs": 45,
  "service": "api-service"
}
```

- Use Logback + Logstash encoder (JSON output)
- MDC for traceId, userId propagation
- Worker logs include: jobId, videoAssetId, stage, durationMs

---

## 18.2 · Health Checks

```
GET /actuator/health
  → PostgreSQL: connection test
  → Redis: PING
  → RabbitMQ: connection check
  → Storage: bucket access check
  → FFmpeg (worker): binary present
```

---

## 18.3 · Security Hardening

- CORS: whitelist specific origins only (no `*` in production)
- Security headers: `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`
- Presigned URL validation: reject if URL not from our bucket
- Webhook signature validation: HMAC-SHA256 for all webhook endpoints
- SQL injection: no native queries without parameter binding
- Bucket policy: deny all public access, allow CDN origin only

---

## 18.4 · Performance

### Redis Caching Strategy

| Data | TTL | Invalidation |
|------|-----|-------------|
| Homepage sections | 5 min | Admin content feature/unfeature |
| Category list | 10 min | Admin CUD on categories |
| Content detail | 2 min | Partner edit/publish |
| Search results | 60 sec | None (TTL only) |
| Playback manifest | 5 min | VideoAsset status change |

### Database Indexes
```sql
CREATE INDEX idx_contents_slug ON contents(slug);
CREATE INDEX idx_contents_status_type ON contents(status, type);
CREATE INDEX idx_contents_featured ON contents(featured) WHERE featured = true;
CREATE INDEX idx_video_assets_content_status ON video_assets(content_id, processing_status);
CREATE INDEX idx_watch_progress_user ON watch_progress(user_id, last_watched_at DESC);
CREATE INDEX idx_reviews_content ON reviews(content_id, status);
CREATE INDEX idx_analytics_events_occurred ON analytics_events(occurred_at DESC);
CREATE INDEX idx_upload_sessions_expires ON upload_sessions(expires_at) WHERE upload_status = 'PENDING';
```

---

## 18.5 · API Rate Limiting

| Endpoint Group | Limit |
|----------------|-------|
| /auth/* | 10 req/min per IP |
| /uploads/* | 30 req/min per user |
| /playback/* | 60 req/min per user |
| General API | 300 req/min per user |
| Admin API | 500 req/min per user |

---

✅ **Batch 18 Complete When:**
- All logs in JSON format with traceId
- Health endpoint returns all green in Docker Compose
- CORS configured for production domains
- All DB indexes applied via migration
- Rate limiting tested (429 returned on breach)
- Load test run against playback + upload endpoints

---

# EXECUTION ORDER SUMMARY

| Batch | Name | Services | Est. Complexity |
|-------|------|----------|-----------------|
| 0 | Core Foundation | Both | High |
| 1 | Authentication | API | High |
| 2 | User Profile | API | Low |
| 3 | Categories + Discovery | API | Medium |
| 4 | Content Core | API | High |
| 5 | Episodes + Series | API | Medium |
| 6 | Upload Session | API | High |
| 7 | Media Worker Pipeline | Worker | Very High |
| 8 | Playback System | API | High |
| 9 | Search + Discovery | API | Medium |
| 10 | Favorites + History | API | Low |
| 11 | Ratings + Reviews | API | Medium |
| 12 | Subscription + Billing | API | High |
| 13 | Partner Portal | API | Medium |
| 14 | Admin Moderation | API | Medium |
| 15 | Notification System | Both | Medium |
| 16 | Analytics | Both | Medium |
| 17 | Background Jobs | Both | Medium |
| 18 | Observability + Hardening | Both | High |

---

# TECHNOLOGY STACK REFERENCE

## API Service
| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Security | Spring Security 6 + JWT (JJWT) |
| ORM | Spring Data JPA + Hibernate |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| Cache | Redis 7 (Lettuce client) |
| Queue | RabbitMQ 3.x (Spring AMQP) |
| Storage | AWS S3 SDK v2 (S3 / R2 / MinIO) |
| Email | SendGrid SDK / AWS SES |
| Validation | Jakarta Validation (Bean Validation 3) |
| Docs | SpringDoc OpenAPI 3 |
| Build | Gradle |

## Media Worker
| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Queue | Spring AMQP (RabbitMQ consumer) |
| FFmpeg | Process API (Java) → FFmpeg + FFprobe |
| Storage | AWS S3 SDK v2 |
| Database | Spring Data JPA (shared PostgreSQL) |

## Infrastructure
| Component | Technology |
|-----------|-----------|
| Database | PostgreSQL 16 |
| Cache | Redis 7 |
| Queue | RabbitMQ 3.x with Management Plugin |
| Storage (local) | MinIO |
| Storage (prod) | AWS S3 or Cloudflare R2 |
| CDN | Cloudflare CDN or Bunny CDN |
| Reverse Proxy | Nginx |
| Containers | Docker + Docker Compose |
| Process Manager | Systemd (VM) or Kubernetes (scaled) |

---

# API ENDPOINT MASTER REFERENCE

## Base URL: `/api/v1`

### Auth
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
```

### Users / Profile
```
GET    /users/me
PATCH  /users/me
PATCH  /users/me/preferences
PATCH  /users/me/notifications
PATCH  /users/me/password
PATCH  /users/me/avatar
```

### Categories
```
GET    /categories
GET    /categories/:slug
POST   /admin/categories
PATCH  /admin/categories/:id
DELETE /admin/categories/:id
```

### Content
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

### Seasons + Episodes
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

### Upload
```
POST /uploads/sessions
POST /uploads/:sessionId/complete
GET  /uploads/:sessionId/status
```

### Playback
```
GET  /playback/:contentId/access
GET  /playback/:contentId/manifest
GET  /playback/episode/:episodeId/manifest
POST /playback/progress
GET  /playback/continue-watching
```

### Favorites + History
```
GET    /favorites
POST   /favorites/:contentId
DELETE /favorites/:contentId
GET    /history
DELETE /history/:id
DELETE /history
```

### Reviews
```
GET    /contents/:contentId/reviews
POST   /contents/:contentId/reviews
PATCH  /reviews/:id
DELETE /reviews/:id
PATCH  /admin/reviews/:id/status
```

### Discovery + Search
```
GET /discover/home
GET /discover/featured
GET /discover/trending
GET /discover/new-releases
GET /discover/recommended
GET /search
```

### Subscriptions
```
GET   /subscriptions/plans
POST  /subscriptions/checkout
GET   /subscriptions/me
PATCH /subscriptions/cancel
POST  /subscriptions/apply-coupon
```

### Notifications
```
GET   /notifications
PATCH /notifications/:id/read
PATCH /notifications/read-all
```

### Partner
```
GET /partner/dashboard
GET /partner/analytics
GET /partner/uploads
GET /partner/contents
GET /partner/revenue
```

### Admin
```
GET   /admin/dashboard
GET   /admin/users
GET   /admin/users/:id
PATCH /admin/users/:id
PATCH /admin/users/:id/status
GET   /admin/uploads/processing
GET   /admin/analytics/platform
GET   /admin/analytics/content
GET   /admin/analytics/users
GET   /admin/analytics/revenue
```

### Webhooks
```
POST /webhooks/payment
POST /webhooks/storage
```

---

# ROLE GUARD REFERENCE

| Endpoint Group | Required Role |
|---------------|--------------|
| /auth/* | Public |
| GET /contents, /categories, /discover, /search | Public |
| /playback/*, /favorites/*, /history/*, /reviews/* | Authenticated |
| /uploads/sessions | Partner or Admin |
| /partner/* | Partner |
| /admin/* | Admin |
| /webhooks/* | Public (signature validated internally) |

---

# COMMON QUERY PARAMETERS

### Pagination (all list endpoints)
```
page         Integer, default 1
limit        Integer, default 20, max 100
```

### Sorting
```
sortBy       Field name
sortOrder    ASC | DESC
```

### Common Filters
```
search       Full-text search string
status       Entity status enum
createdAtFrom  ISO 8601
createdAtTo    ISO 8601
```

---

*TinnieStudio Execution Roadmap — Internal Development Reference*
*Architecture: Modular Monolith + Clean Architecture + Event-Driven Media Pipeline*
