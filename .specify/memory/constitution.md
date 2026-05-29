# TinnieStudio Server Constitution

## Core Principles

### I. Feature Lifecycle Governance (NON-NEGOTIABLE)
Every feature must pass through the full mandatory lifecycle:
`IDEA → SPECIFY → ARCHITECTURE REVIEW → PLAN → TASK BREAKDOWN → IMPLEMENT → VALIDATE → INTEGRATION REVIEW → COMPLETE`

- No implementation before specification approval
- No batch skipping; stages may not be omitted, only logged as passed
- Features must remain independently testable at every stage
- Speckit executes one batch at a time — no batch combining, no dependency skipping

### II. Batch Boundary Rules
Each batch represents exactly one bounded business capability:

- Infrastructure batches must reach COMPLETE before any dependent business module starts
- Shared abstractions must be finalized before any batch that consumes them
- Cross-service dependencies must be explicitly declared via `dependsOn` and `crossServiceContracts`
- Every batch declares its classification: `FOUNDATION | SECURITY | DOMAIN | MEDIA | PLAYBACK | DISCOVERY | PAYMENT | PARTNER | ADMIN | OBSERVABILITY | SCALING`

### III. API ↔ Worker Governance
The API service is the source of truth for all business state:

- Worker must never own business logic
- Worker may only: process jobs, update processing-state fields, persist its own technical output (VideoVariant, ProcessingJob)
- Worker writes are restricted to: `video_assets.processing_status`, `video_assets.processing_error`, `video_assets.manifest_key`, `video_variants.*`, `processing_jobs.*`
- Worker may publish only to `notifications.send`; never to business-logic queues
- Queue contracts are immutable versioned contracts — breaking changes require a version bump and consumer migration plan

### IV. Domain Ownership (NON-NEGOTIABLE)
Each domain owns its data exclusively. Cross-domain reads go through service interfaces, never shared JPA queries:

| Domain | Owns |
|--------|------|
| Auth | Identity, sessions, tokens, device governance |
| Billing | Subscription state, payment records, coupon redemptions |
| Playback | Watch state, progress, continue-watching |
| Media | Processing lifecycle, VideoAsset, VideoVariant |
| Discovery | Search index, recommendations, aggregated rankings |
| Admin | Moderation actions, audit log, platform-wide overrides |

No domain may directly inject another domain's repository. No circular domain dependencies.

### V. Scalability Governance
The architecture must absorb change without rewrites:

- New user roles must not require auth module rewrites (role array design)
- New billing tiers must not require session architecture changes
- New content types must extend existing abstractions, not fork them
- Worker scaling must be horizontal — no shared state between worker instances
- New admin capabilities extend the admin module; they do not touch user auth

### VI. Infrastructure Governance
All infrastructure access is mediated through abstractions:

- No domain service may use `S3Client`, `RabbitTemplate`, `RedisTemplate`, or `FFmpeg` directly
- All storage access goes through `StorageService` interface
- All queue publishing goes through `QueuePublisher` interface
- All cache access goes through `CacheService` interface
- All config access goes through `@ConfigurationProperties` beans — no `@Value` or `System.getenv()` in service classes
- Redis keys follow the namespace convention: `tinnie:{module}:{subkey}:{id}`
- Queue consumers are idempotent — safe to execute multiple times with the same message
- Every async process supports retries; non-retryable failures are explicitly identified and dead-lettered

### VII. Completion Gate Governance
No batch is COMPLETE until all five gates pass:

| Gate | Checks |
|------|--------|
| `functionalValidation` | All endpoints return expected responses for happy path and all error cases |
| `securityValidation` | Auth guards enforced, no role escalation, no public access to protected endpoints |
| `integrationValidation` | Cross-service contracts verified: queue messages consumed correctly, DB state transitions correct |
| `performanceValidation` | Redis caching in place, no N+1 queries on list endpoints, DB indexes applied via migration |
| `rollbackReadiness` | Flyway migration runs cleanly on a fresh DB; destructive changes have a documented rollback path |

### VIII. Architecture Drift Prevention
These rules are enforced during code review and Speckit validation. Drift is a blocking issue, not a comment:

| Rule | Violation Example |
|------|-----------------|
| No direct infrastructure access in domain services | `AuthService` calling `redisTemplate.opsForValue()` directly |
| No storage SDK outside storage abstraction | `S3Client` injected into `UploadService` |
| No RabbitTemplate outside queue abstraction | `rabbitTemplate.convertAndSend(...)` in `ContentService` |
| No FFmpeg orchestration outside media worker | API service executing `ffprobe` process |
| No controller business logic | Subscription access check inside `@RestController` method body |
| No cross-domain repository injection | `AuthService` injecting `ContentRepository` |
| No `@Value` in service classes | `@Value("${jwt.secret}")` in `AuthService` |
| No `System.getenv()` in application code | `System.getenv("JWT_SECRET")` anywhere in service/config layer |

### IX. Shared Contract Governance
Contracts that cross service or module boundaries are treated as public APIs:

- All API responses use the standard envelope: `{ success, data, error, meta }`
- All paginated responses use `PageResult<T>` with `items`, `total`, `page`, `limit`
- All error responses carry machine-readable codes: `NOT_FOUND`, `UNAUTHORIZED`, `CONFLICT`, `VALIDATION_FAILED`, `UPGRADE_REQUIRED`
- Pagination parameters are uniform: `page` (default 1), `limit` (default 20, max 100), `sortBy`, `sortOrder`
- Queue payload schemas include a `version` field; unknown versions are dead-lettered
- DTOs used across module or service boundaries require a version bump on breaking change

### X. Multi-Actor Security Boundaries (NON-NEGOTIABLE)
Admin and user auth flows are completely isolated — a compromise of one must not expose the other:

- Admin JWT uses a separate secret (`JWT_ADMIN_SECRET`) with audience claim `aud=admin`
- User JWT uses a separate secret (`JWT_USER_SECRET`) with audience claim `aud=user`
- A user token presented to an admin endpoint is rejected as unauthorized (not just forbidden)
- Admin session policy: single active session per admin; new login revokes previous session
- Super admin bootstrap token is one-time and self-disabling — endpoint closed after first successful use
- `@Order(1)` Spring Security chain handles `/auth/admin/**`; `@Order(2)` handles all other paths
- Device fingerprint (SHA-256 of UserAgent + IP) is display-only — never used for security decisions

---

## Architecture Boundaries

### API Service Responsibilities
- Owns all business logic and entity state transitions
- Publishes jobs to queues (never consumes from `media.video.process`)
- Owns DB writes for all business entities (`users`, `contents`, `user_subscriptions`, `coupons`, etc.)
- Exposes all client-facing REST endpoints under `/api/v1`
- Manages distributed locks for scheduled jobs via Redis `SETNX`

### Worker Service Responsibilities
- Consumes jobs from `media.video.process` only
- Stateless — safe to run multiple instances concurrently
- Writes only to processing-state columns and its own technical entities
- Publishes completion events to `notifications.send` only
- No HTTP endpoints; no Spring Security configuration

### Infrastructure Abstraction Layers
```
Domain Services
    ↓ interface only
StorageService / CacheService / QueuePublisher / MailService
    ↓ @ConditionalOnProperty implementations
S3StorageService / RedisTemplateCache / RabbitQueuePublisher / SendGridMail
```

---

## Execution Standards

### Speckit Process Order
1. `speckit-specify` → `spec.md` — business requirement, acceptance criteria, domain boundaries
2. `speckit-plan` → `plan.md` — implementation design, file structure, design artifacts
3. `speckit-tasks` → `tasks.md` — ordered, dependency-aware task list
4. `speckit-implement` — execute tasks TDD-first, one task at a time
5. `speckit-analyze` — cross-artifact consistency check before marking COMPLETE

### Test-Driven Development (NON-NEGOTIABLE)
Every task: write failing test → confirm fail → implement minimal code → confirm pass → commit.
No code is committed without a corresponding passing test.

### Quality Gates
- All tests pass before any commit on the implementation branch
- No placeholder code (`TODO`, `FIXME`, `//implement later`) in production paths
- All new endpoints documented in OpenAPI (Swagger via SpringDoc)
- All schema changes delivered as Flyway migrations — no direct schema edits to existing migrations
- All scheduled jobs are fully idempotent and acquire a distributed lock before execution

---

## Governance

This constitution supersedes all other development practices for the TinnieStudio server.

**Amendments** require: (1) documented rationale, (2) review of all active batches for impact, (3) update to `BATCH-PLAN.md` if batch structure is affected, (4) version increment.

All Speckit runs verify batch boundary compliance before execution. Architecture drift detected during implementation is a blocking issue — it must be corrected before the batch can be declared COMPLETE, not logged as a future fix.

The constitution is the enforcement layer. The `BATCH-PLAN.md` is the execution layer. The spec files are the feature layer. All three must remain consistent.

**Version**: 1.0.0 | **Ratified**: 2026-05-29 | **Last Amended**: 2026-05-29
