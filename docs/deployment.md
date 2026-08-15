# Deployment Guide

How to deploy `api-service` and `media-worker` to production, managed via **Dokploy** (Docker
Swarm + Traefik), with **S3** for object storage.

This doc reflects the actual current codebase, not an aspirational target — where something is
half-built or misconfigured, it's called out explicitly in [Known Gaps](#known-gaps--things-to-fix-before-or-during-first-real-deploy)
rather than glossed over.

---

## 1. Architecture

```
                        ┌─────────────┐
                 HTTPS  │   Traefik   │  (Dokploy-managed reverse proxy, Let's Encrypt)
                        └──────┬──────┘
                               │
                   ┌───────────┴────────────┐
                   │                        │
            api.tinniestudio.com     (frontend origin — separate deploy)
                   │
          ┌────────▼─────────┐
          │   api-service     │  Spring Boot, port 8080 (+ 8081 actuator, same context via Spring Security)
          └───┬────┬────┬─────┘
              │    │    │
     ┌────────┘    │    └─────────┐
     ▼             ▼              ▼
 Postgres        Redis        RabbitMQ ◄──────┐
(Dokploy-        (Dokploy-    (exchange +     │
 managed)         managed)     queues,        │
                                auto-declared) │
                                    │          │
                              ┌─────▼─────┐    │
                              │media-worker│────┘ (consumes media.video.process,
                              │ (ffmpeg)   │       publishes notifications.send / analytics.ingest)
                              └─────┬──────┘
                                    │
                                    ▼
                          S3 (object storage — raw
                          uploads in, HLS output out)
```

- **api-service** — the REST API. Talks to Postgres, Redis, RabbitMQ (publish-only for most flows), and S3 (presigned URLs).
- **media-worker** — consumes `media.video.process` from RabbitMQ, transcodes with ffmpeg, uploads HLS output to S3, publishes `notifications.send` / `analytics.ingest`.
- Both are separate Dokploy **applications**, each built from its own `Dockerfile`, each pulling env vars from Dokploy's per-app environment panel.
- Postgres and Redis run as Dokploy-managed **database** services, not from this repo's `docker-compose.yml`.
- RabbitMQ, Prometheus, and Grafana currently only exist in this repo's `docker-compose.yml` — see [§8](#8-observability-prometheus--grafana).

---

## 2. Host constraint: build the JAR locally, not in the deploy pipeline

Both Dockerfiles say this explicitly, so it isn't missed:

```dockerfile
# Build JAR locally first: ./gradlew :api-service:bootJar -x test
# GitHub is blocked on this host so Gradle cannot run inside Docker.
```

The Dokploy host cannot reach GitHub / most Gradle dependency mirrors. **Do not** try to build
the JAR inside the Docker image on that host — it will fail resolving dependencies. Instead:

```bash
# From the repo root, on a machine with normal internet access:
./gradlew :api-service:bootJar -x test
./gradlew :media-worker:bootJar -x test
```

This produces:
- `api-service/build/libs/tinniestudio-api-service-*.jar`
- `media-worker/build/libs/tinniestudio-media-worker-*.jar`

Both Dockerfiles just `COPY` the prebuilt jar in — the image build itself needs no network access
beyond pulling `eclipse-temurin:21-jre-jammy` and (for media-worker) `mwader/static-ffmpeg` from
Docker Hub, both of which work fine on the Dokploy host.

**If a CI environment with full internet access becomes available**, the Dockerfiles can be
switched back to a multi-stage build that runs Gradle inside the image — the comment in each
Dockerfile flags this as the intended future state.

---

## 3. Dokploy application setup

For **each** of `api-service` and `media-worker`:

1. Create a new Dokploy **Application** (Dockerfile-based, not docker-compose).
2. Point it at this repo, with the Dockerfile path set to `api-service/Dockerfile` /
   `media-worker/Dockerfile` respectively, and build context set to the repo root (both
   Dockerfiles `COPY` from `api-service/build/libs/...` / `media-worker/build/libs/...`, which are
   repo-root-relative paths).
3. Since the JAR must be built locally first (§2), either:
   - commit the built JAR to a release branch/tag Dokploy deploys from, or
   - build locally and `docker build`/push the image yourself, pointing Dokploy at that image
     instead of building from source.
   (Pick whichever matches your actual release process — this repo doesn't currently script
   either path; see [Known Gaps](#known-gaps--things-to-fix-before-or-during-first-real-deploy).)
4. Set environment variables via Dokploy's per-application environment panel — see [§6](#6-environment-variables) for the full list. **Do not** commit real secrets to `.env.prod` in git (it's gitignored already — keep it that way, and treat it as a local scratch copy of what's in Dokploy, not a source of truth).
5. api-service exposes port `8080` (app) and `8081` (actuator — see [§7](#7-observability-endpoints-health--metrics)); only `8080` needs a public route.
6. media-worker exposes no HTTP port — it's a pure RabbitMQ consumer. Don't attach a Traefik route to it.
7. Attach both to the same Dokploy network so they (and Postgres/Redis/RabbitMQ) can resolve each other by Dokploy-assigned service hostname.

---

## 4. Traefik routing (api-service only)

Dokploy manages Traefik, but if you need to hand-adjust the router labels (as has been done
before — see `docs/TRAEFIK.md`), the pattern is:

```bash
docker service update \
  --label-add "traefik.enable=true" \
  --label-add "traefik.http.routers.tinniestudio.rule=Host(\`api.tinniestudio.com\`)" \
  --label-add "traefik.http.routers.tinniestudio.entrypoints=websecure" \
  --label-add "traefik.http.routers.tinniestudio.tls.certresolver=letsencrypt" \
  --label-add "traefik.http.services.tinniestudio.loadbalancer.server.port=8080" \
  --label-add "traefik.docker.network=dokploy-network" \
  <dokploy-generated-service-name>
```

Notes:
- This is a Docker **Swarm** command (`docker service update`), matching how Dokploy runs
  containers — not plain `docker run`/`docker compose`.
- `<dokploy-generated-service-name>` follows Dokploy's own naming convention
  (`<project>-<app>-<hash>`) — check `docker service ls` for the actual name; don't guess it.
- The load-balancer target port is `8080` (the app), never `8081` (actuator) — actuator has no
  business being internet-routable at all (see §7).
- CORS for the frontend origin is handled by the app itself
  (`app.cors.*` in `application.yml`, driven by `FRONTEND_URL`/`APP_BASE_URL`), not by Traefik.

---

## 5. Database

- Flyway runs automatically on api-service startup (`spring.flyway.enabled: true`,
  `baseline-on-migrate: false`) — no manual migration step. media-worker has
  `spring.flyway.enabled: false` (it's a schema consumer, not owner — never point it at a fresh,
  unmigrated DB before api-service has started at least once).
- Use a Dokploy-managed Postgres service; point `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`
  at it (both services need the same DB).
- **Backup** (adjust the volume name to whatever Dokploy actually named it — check `docker volume ls`):
  ```bash
  docker run --rm \
    -v <dokploy-postgres-volume-name>:/var/lib/postgresql/data:ro \
    -v $(pwd):/backup \
    alpine \
    tar czf /backup/tinniestudiodb-backup-$(date +%Y%m%d%H%M%S).tar.gz -C / var/lib/postgresql/data
  ```
- **Restore / inspect** (interactive shell into the data volume):
  ```bash
  docker run --rm -it \
    -v <dokploy-postgres-volume-name>:/var/lib/postgresql/data \
    --entrypoint bash \
    postgres:<matching-major-version>
  ```
- Take a backup before every deploy that includes a new Flyway migration — Flyway migrations in
  this repo are forward-only (no `undo` migrations), so a bad migration means restoring from
  backup, not rolling it back in place.

---

## 6. Environment variables

Full reference: `.env.example` at the repo root — treat it as the source of truth for names and
defaults; this section groups them by purpose and flags what's actually **required** in
production (no fallback default, or a dev-only default that must be overridden).

### Required, no safe default

| Variable | Notes |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | Dokploy-managed Postgres |
| `REDIS_URL` | Dokploy-managed Redis, full `redis://[:password@]host:port` form |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | See §9 — **not currently a Dokploy-managed service**, needs its own deploy |
| `APP_BASE_URL`, `FRONTEND_URL` | Real prod domains — required with no fallback in prod-facing CORS/cookie config |
| `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET` | User-token signing keys, base64, ≥32 bytes |
| `JWT_ADMIN_ACCESS_SECRET`, `JWT_ADMIN_REFRESH_SECRET` | **Separate** admin-token signing keys — must differ from the user ones |
| `COOKIE_SECURE=true`, `COOKIE_SAME_SITE`, `COOKIE_DOMAIN` | `COOKIE_SECURE` must be `true` in prod (HTTPS) or auth cookies won't set |
| `ADMIN_BOOTSTRAP_TOKEN` | One-time-use — see §10. Rotate/blank it out after the first super-admin exists |
| `STORAGE_PROVIDER`, `STORAGE_BUCKET`, `STORAGE_REGION`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` | See §6.1 — **as of this fix, the app refuses to start if these are missing/wrong**, it will not silently no-op |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` | Live keys in prod, not test keys |

### Required with a dev-safe default you must still override

| Variable | Dev default | Prod value |
|---|---|---|
| `STORAGE_ENDPOINT` | `http://localhost:9000` | Your S3 endpoint (see §6.1) |
| `STORAGE_PATH_STYLE_ACCESS` | `true` (MinIO) | `false` for real AWS S3 in most regions; check your provider |
| `CDN_BASE_URL` | `http://localhost:3000` | Wherever object storage is actually reachable from — see §6.2 |

### Optional / has a working default

`RESEND_API_KEY`/`RESEND_FROM_EMAIL` (email), `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` (OAuth login),
`FREE_TIER_CONTENT_LIMIT`, `STORAGE_PRESIGNED_TTL`, `PROMETHEUS_SCRAPE_USERNAME`/`PROMETHEUS_SCRAPE_PASSWORD`
(only matters if you're running the observability stack, §8), `GRAFANA_PASSWORD` (same).

### 6.1 Storage: MinIO → S3

`STORAGE_PROVIDER` must be exactly `MINIO` or `S3` — both activate the identical AWS-SDK-backed
client (`StorageServiceConfig`), the value only exists so prod config reads as "S3" rather than
"MinIO" pointed at a real bucket. **Any other value (including unset) means no storage bean is
created and the app fails to start** — this was fixed as part of this deployment work; previously
an unset/wrong value silently fell back to a stub that accepted uploads and returned fake URLs
without storing anything.

```
STORAGE_PROVIDER=S3
STORAGE_BUCKET=<your-bucket-name>
STORAGE_REGION=<your-region>                    # e.g. us-east-1
STORAGE_ENDPOINT=https://s3.<region>.amazonaws.com   # or your S3-compatible provider's endpoint
STORAGE_PUBLIC_ENDPOINT=                        # only set if different from STORAGE_ENDPOINT (see below)
STORAGE_ACCESS_KEY=<IAM access key>
STORAGE_SECRET_KEY=<IAM secret key>
STORAGE_PATH_STYLE_ACCESS=false                 # real AWS S3: false. MinIO/some S3-compatible providers: true — check yours
```

- `STORAGE_ENDPOINT` is what api-service itself uses to reach storage. `STORAGE_PUBLIC_ENDPOINT`
  is what gets baked into presigned upload/download URLs handed to browsers — only set it
  separately from `STORAGE_ENDPOINT` if the app's network path to storage differs from a
  browser's (not expected to be the case for real AWS S3 reached over the public internet; it
  mattered for the Docker `host.docker.internal` split in local dev).
- IAM credentials should be scoped to just this bucket (`s3:PutObject`, `s3:GetObject`,
  `s3:DeleteObject`, `s3:HeadObject`, `s3:ListBucket` on the one bucket) — not a broad account key.
- media-worker needs the **same** `STORAGE_*` variables — it downloads the raw upload and pushes
  processed HLS output through the same bucket.
- `path-style-access` differs per provider: AWS S3 buckets created after ~2020 generally want
  virtual-hosted style (`false`); MinIO and some S3-compatible providers require path-style
  (`true`). If uploads/downloads 403 or 404 mysteriously after switching to real S3, this is the
  first thing to check.

### 6.2 CDN_BASE_URL

`CDN_BASE_URL` is **not** an actual CDN integration in code — it's a URL prefix the app
concatenates onto storage keys to build the final playback/avatar URL
(`PlaybackServiceImpl.buildManifestResponse`, etc.). The player then makes direct HTTP requests to
`CDN_BASE_URL/<storage-key>` — nothing in api-service or media-worker proxies that traffic. For
that to work, whatever `CDN_BASE_URL` points at must serve the bucket's objects **anonymously**
(no auth) — the player never presigns per-segment.

Two ways to satisfy this in production:
1. **Simplest**: point `CDN_BASE_URL` straight at the bucket's public HTTPS endpoint, with the
   bucket (or just the `processed/`/`thumbnails/`/`partner-logos/` prefixes) set to public-read.
   No caching benefit, but functionally correct.
2. **Recommended for real traffic**: put a real CDN (CloudFront, Cloudflare, etc.) in front of a
   **private** bucket using Origin Access Control, and point `CDN_BASE_URL` at the CDN's public
   hostname. The bucket itself stays private; only the CDN can read it.

Either way, this is an infrastructure setup step outside the app — `CDN_BASE_URL` is just told
where to point once it's done.

---

## 7. Observability endpoints (health & metrics)

- `GET /api/v1/actuator/health` — public, no auth (Batch 18 #2 requirement).
- `GET /api/v1/actuator/metrics`, `GET /api/v1/actuator/prometheus` — **admin-only**, enforced by
  Spring Security, not by network isolation. This is why `management.server.port` is **not** set
  separately from the main app port in `application.yml` — a separate management port runs
  actuator in its own child context that Spring Security's filter chains never see, which would
  silently strip this protection. Do not "fix" this by adding a separate management port back.
- Traefik should only ever route the main app port (`8080`) publicly; `8081` isn't used by
  anything currently (see Known Gaps) but keep it un-routed regardless.

---

## 8. Observability (Prometheus + Grafana)

These currently only exist in this repo's root `docker-compose.yml`, alongside `redis` and
`rabbitmq` — that compose file is oriented at **local/staging** use (`host.docker.internal`,
`restart: no`, bare port bindings), not a direct drop-in for Dokploy production. To run it in
production:

1. Deploy `docker-compose.yml` as its own Dokploy "Docker Compose" application (Dokploy supports
   this natively), **or** stand up Prometheus/Grafana as separate Dokploy applications using the
   same images/config (`observability/prometheus.yml`, `observability/grafana/`).
2. Either way, `PROMETHEUS_SCRAPE_USERNAME`/`PROMETHEUS_SCRAPE_PASSWORD` must match the value in
   `observability/secrets/prometheus_scrape_password` (gitignored — see
   `observability/secrets/README.md`) and Prometheus must be able to reach api-service's
   `/actuator/prometheus` over the network.
3. `GRAFANA_PASSWORD` is required or the Grafana container refuses to start
   (`GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_PASSWORD:?GRAFANA_PASSWORD must be set}`).
4. If you deploy RabbitMQ this way too (see §9), note `redis` and `rabbitmq` in this
   compose file are the ones api-service/media-worker would need to point at — rewrite the
   `host.docker.internal` references and container-name-based service discovery for however
   Dokploy's networking actually resolves cross-application hostnames in your setup.

---

## 9. RabbitMQ — not yet a managed production service

`.env.prod` as it currently exists in this repo has **no `RABBITMQ_*` variables at all** — meaning
either RabbitMQ hasn't been provisioned for production yet, or it's provisioned somewhere this
file doesn't reflect. Either way, RabbitMQ is a hard dependency (Spring AMQP connects at startup;
api-service and media-worker won't come up cleanly without it), so before deploying:

- Stand up a RabbitMQ instance reachable from both services (Dokploy doesn't have a built-in
  RabbitMQ template as of this writing — use the `docker-compose.yml` service, or a standalone
  Dokploy application from the `rabbitmq:3-management-alpine` image).
- Set `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` on **both**
  api-service and media-worker.
- No manual queue/exchange setup needed — `RabbitConfig` in both services declares the exchange
  (`tinniestudio.direct`) and all queues (`media.video.process`, `media.video.retry`,
  `media.video.failed`, `notifications.send`, `analytics.ingest`) as Spring beans on startup.

---

## 10. First deploy: admin bootstrap

Before any admin exists, create the first super-admin via the one-time bootstrap endpoint:

```bash
curl -X POST https://api.tinniestudio.com/api/v1/auth/admin/bootstrap \
  -H "Content-Type: application/json" \
  -d '{"bootstrapToken":"<ADMIN_BOOTSTRAP_TOKEN value>","email":"admin@tinniestudio.com","password":"<strong password>"}'
```

This is permanently disabled the moment a `SUPER_ADMIN` exists (checked against the DB, not just
an in-memory flag, so it stays disabled across restarts) — safe to leave the env var set, but
rotate/blank `ADMIN_BOOTSTRAP_TOKEN` after first use as defense-in-depth regardless.

---

## 11. Deploy checklist

**First deploy:**
1. Provision Postgres, Redis, RabbitMQ (§9), S3 bucket + IAM credentials (§6.1).
2. Set all required env vars on both Dokploy applications (§6).
3. `./gradlew :api-service:bootJar -x test && ./gradlew :media-worker:bootJar -x test` locally.
4. Build/push or let Dokploy build both Dockerfiles (§2–3).
5. Deploy api-service first (it owns the Flyway migrations); confirm `GET /actuator/health` is
   green before deploying media-worker (which assumes an already-migrated schema).
6. Attach Traefik routing to api-service only (§4).
7. Run the admin bootstrap (§10).
8. (Optional) Deploy the observability stack (§8).

**Every subsequent deploy:**
1. Back up the database if this deploy includes a new Flyway migration (§5) — migrations are
   forward-only.
2. Build both JARs locally (§2).
3. Deploy api-service; watch `/actuator/health` and logs for Flyway migration success before
   moving on.
4. Deploy media-worker.
5. Smoke test: register/login, upload flow (presign → upload → complete), playback manifest for
   an existing published title, `/actuator/health` public, `/actuator/prometheus` returns 401/403
   without admin auth.

---

## Known gaps / things to fix before or during first real deploy

Found while writing this guide — flagging rather than silently working around:

1. **`.env.prod` in this repo is missing variables the current codebase requires**: no
   `RABBITMQ_*`, no `STORAGE_*`, no `JWT_ADMIN_*`, no `ADMIN_BOOTSTRAP_TOKEN`. If that file (or
   whatever Dokploy currently has configured) is genuinely what's live, the app is running an
   older version of its own config — RabbitMQ-dependent features and anything admin-JWT-related
   would be broken, and storage now fails to start at all rather than silently no-op-ing (this
   guide's §6.1 change). Reconcile against `.env.example` before deploying.
2. **No separate Spring `prod` profile exists anymore.** There was an `application.prod.yml`, but
   Spring Boot's actual profile-file convention is `application-prod.yml` (dash, not dot) — this
   file was never loaded by Spring Boot regardless of any `SPRING_PROFILES_ACTIVE` setting, and
   nothing in this repo ever set that variable either. It's been removed rather than fixed-in-place:
   diffing it against `application.yml` showed it was stale (missing the `rabbitmq`, `admin-jwt`,
   `storage`, `stripe`, and most of the `management` blocks entirely) and in one place actively
   wrong — it set a separate `management.server.port: 8081`, which would have **bypassed the
   Spring Security protection on `/actuator/metrics` and `/actuator/prometheus`** (§7) had it ever
   actually been loaded. All environment-specific behavior is controlled purely by env vars against
   the single `application.yml` now. If you want prod-only Java-level differences (e.g. a lower
   log level) in the future, use `application-prod.yml` (correct naming) and explicitly set
   `SPRING_PROFILES_ACTIVE=prod` in Dokploy — neither exists today.
3. **`STORAGE_PATH_STYLE_ACCESS` needs to be verified against your actual S3 provider before first
   traffic** — defaults to `true` (MinIO-safe), likely needs to be `false` for real AWS S3. Test
   an actual upload → complete → playback round-trip against production storage before calling
   the migration done.
4. **No documented CI/CD pipeline** for the "build locally, deploy the JAR" workflow (§2) — this
   guide describes the manual steps; if deploys become frequent, scripting this (or getting CI
   runners with GitHub access) is worth prioritizing.
5. **`STRIPE_ROLE_KEY`** appears in `.env.prod` but isn't referenced anywhere in the current
   codebase — likely vestigial, safe to drop unless something outside this repo depends on it.
