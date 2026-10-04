# Production Docker Deployment — Design

**Date:** 2026-10-04
**Scope:** server (api-service + media-worker), tinniestudio-partner-web, tinniestudio-client-web.
**Explicitly out of scope:** tinniestudio-admin-web (left out for now, per request). Postgres/Redis
stay Dokploy-managed services, not part of this change. The existing `server/docker-compose.yml`
(dev/staging, host.docker.internal-based, bundles redis+rabbitmq+prometheus+grafana) is untouched.

## Context

`server/docs/deployment.md` documents the current production setup: Dokploy + Traefik, and a hard
constraint that the Dokploy host cannot reach GitHub/Gradle mirrors, so the existing
`api-service/Dockerfile` and `media-worker/Dockerfile` each just `COPY` a JAR that must be built
*locally* first (`./gradlew bootJar`). The doc explicitly flags this as a workaround: "if a CI
environment with full internet access becomes available, the Dockerfiles can be switched back to
a multi-stage build that runs Gradle inside the image."

That CI environment is what this change introduces: GitHub Actions (which has full internet
access) builds every image and pushes it to GHCR; Dokploy pulls the finished image rather than
building from source on the restricted host. RabbitMQ — called out in the same doc as "not yet a
managed production service" — gets a dedicated production compose file.

Decisions already made during brainstorming:
- Images are built in CI (GitHub Actions) and pushed to GHCR, not built on the Dokploy host.
- `partner-web` and `client-web` images are pulled by **Dokploy using its own registry
  credentials** — no manual PAT/login management on the server, no need to make GHCR packages
  public.
- RabbitMQ gets its own `docker-compose.prod.yml`, containing only RabbitMQ (not Redis, not
  observability).

Each app lives in its own GitHub repo, so this is three independent CI setups, not one:

| Repo | Remote | Builds |
|---|---|---|
| `server` | `git@github.com:Tinniestudio/lamastudio_backend.git` | `api-service`, `worker` images |
| `tinniestudio-partner-web` | `git@github.com:techitcheap-com/tinniestudio-partner-web.git` | one image |
| `tinniestudio-client-web` | `git@github.com:techitcheap-com/tinniestudio-client-web.git` | one image |

## 1. `server/Dockerfile` (new, root-level, replaces the two existing Dockerfiles)

Single multi-stage file. `settings.gradle` already declares both subprojects
(`include 'api-service'`, `include 'media-worker'`), so one builder stage produces both jars; two
named final stages consume them independently via `docker build --target`.

```dockerfile
# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY settings.gradle build.gradle ./
COPY api-service/build.gradle api-service/build.gradle
COPY media-worker/build.gradle media-worker/build.gradle
RUN ./gradlew --no-daemon help

COPY api-service/src api-service/src
COPY media-worker/src media-worker/src
RUN ./gradlew --no-daemon :api-service:bootJar :media-worker:bootJar -x test

FROM eclipse-temurin:21-jre-jammy AS api-service
WORKDIR /app
RUN useradd -m springuser
COPY --from=builder /workspace/api-service/build/libs/tinniestudio-api-service-*.jar app.jar
USER springuser
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]

FROM mwader/static-ffmpeg:latest AS ffmpeg

FROM eclipse-temurin:21-jre-jammy AS worker
WORKDIR /app
COPY --from=ffmpeg /ffmpeg  /usr/local/bin/ffmpeg
COPY --from=ffmpeg /ffprobe /usr/local/bin/ffprobe
RUN useradd -m workeruser
COPY --from=builder /workspace/media-worker/build/libs/tinniestudio-media-worker-*.jar app.jar
RUN mkdir -p /tmp/tinniestudio && chown workeruser /tmp/tinniestudio
USER workeruser
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
```

`api-service/Dockerfile` and `media-worker/Dockerfile` are deleted. `.dockerignore` at repo root
needs `build/`, `.gradle/`, `*/build/`, `*/bin/` so stale local build output never gets copied into
the build context.

## 2. `server/docker-compose.prod.yml` (new)

Production-oriented RabbitMQ only — no `host.docker.internal`, no `restart: no`, persistent volume,
and credentials from a local `.env` (not committed) rather than hardcoded.

```yaml
services:
  rabbitmq:
    image: rabbitmq:3-management-alpine
    container_name: tinniestudio-rabbitmq
    restart: unless-stopped
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER:?RABBITMQ_USER must be set}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:?RABBITMQ_PASSWORD must be set}
    ports:
      - "5672:5672"
      - "127.0.0.1:15672:15672"
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq
    networks:
      - tinniestudio-prod

networks:
  tinniestudio-prod:
    driver: bridge

volumes:
  rabbitmq_data:
```

The management UI (`15672`) is bound to `127.0.0.1` only — not meant to be internet-facing. `api-service`
and `media-worker` (deployed as separate Dokploy applications, per the existing doc) need
`RABBITMQ_HOST`/`PORT`/`USER`/`PASSWORD` set to reach this, attached to the same Dokploy/Docker
network. `docs/deployment.md` §9 gets updated to point at this file instead of describing RabbitMQ
as unprovisioned.

## 3. `tinniestudio-partner-web/Dockerfile` (new) + `next.config.ts` fix

Same stage shape as `client-web`'s existing Dockerfile (`deps` → `builder` → `production`,
standalone output), but using `pnpm` — partner-web has only `pnpm-lock.yaml`, no
`package-lock.json`.

**Prerequisite fix:** `next.config.ts` is missing `output: 'standalone'`. Without it, `.next/standalone`
never gets generated and the production stage's `COPY --from=builder /app/.next/standalone ./` fails.
This one-line addition is part of this change.

```dockerfile
FROM node:20-alpine AS base
WORKDIR /app
RUN corepack enable

FROM base AS deps
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

FROM base AS builder
ARG API_BASE_URL
ARG NEXT_PUBLIC_API_BASE_URL
ENV API_BASE_URL=$API_BASE_URL
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN pnpm run build

FROM node:20-alpine AS production
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000
ENV HOSTNAME=0.0.0.0
RUN addgroup -S nextjs && adduser -S nextjs -G nextjs
COPY --from=builder /app/public ./public
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
```

New `.dockerignore` (partner-web doesn't have one): `node_modules`, `.next`, `.git`, `Dockerfile`,
`.env*`.

## 4. `tinniestudio-client-web/Dockerfile` — no change

Already multi-stage, already `output: 'standalone'`, already non-root. CI just builds it as-is.

## 5. GitHub Actions — one workflow per repo, images pushed to GHCR

All three follow the same shape: trigger on push to `main` + manual `workflow_dispatch`, log in to
GHCR with the automatic `GITHUB_TOKEN` (no PAT needed — just `permissions: packages: write` on the
job), tag `:<short-sha>` and `:latest`.

**`server/.github/workflows/docker-build.yml`** — matrix over the two Dockerfile targets:

```yaml
name: docker-build
on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read
  packages: write

jobs:
  build:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        target: [api-service, worker]
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v6
        with:
          context: .
          target: ${{ matrix.target }}
          push: true
          tags: |
            ghcr.io/tinniestudio/lamastudio_backend-${{ matrix.target }}:${{ github.sha }}
            ghcr.io/tinniestudio/lamastudio_backend-${{ matrix.target }}:latest
```

**`tinniestudio-partner-web/.github/workflows/docker-build.yml`** and
**`tinniestudio-client-web/.github/workflows/docker-build.yml`** — single-image version of the
same workflow, with build ARGs pulled from repo Variables. Identical except for the image tag,
which is this repo's own name in each case:

```yaml
      - uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          build-args: |
            API_BASE_URL=${{ vars.API_BASE_URL }}
            NEXT_PUBLIC_API_BASE_URL=${{ vars.NEXT_PUBLIC_API_BASE_URL }}
          tags: |
            ghcr.io/techitcheap-com/tinniestudio-partner-web:${{ github.sha }}
            ghcr.io/techitcheap-com/tinniestudio-partner-web:latest
```

(`tinniestudio-client-web`'s workflow is the same, with `tinniestudio-client-web` in place of
`tinniestudio-partner-web` in both tags.)

## 6. What needs to be configured outside this repo (not part of the code change, but required for CI to work)

- **GitHub repo Variables** (Settings → Secrets and variables → Actions → Variables), on
  `tinniestudio-partner-web` and `tinniestudio-client-web` only: `API_BASE_URL`,
  `NEXT_PUBLIC_API_BASE_URL`. Not secrets — these are not sensitive and end up in the public bundle
  anyway.
- **Dokploy**: configured with its own GHCR registry credentials to pull `partner-web`/`client-web`/
  `api-service`/`worker` images (per the "partner and client will make use of Dokploy credentials"
  decision — this applies to all four images, not just the two frontends, since all are now
  CI-built and pushed to GHCR the same way).
- **Server**: `.env` next to `docker-compose.prod.yml` with `RABBITMQ_USER`/`RABBITMQ_PASSWORD`,
  gitignored, not committed.
- No new GitHub Actions secrets anywhere — the server repo's Gradle build needs no credentials at
  all (no private dependency registry), and runtime secrets (DB, JWT, Stripe, S3) are injected by
  Dokploy at container start, never touched by CI.

## 7. Documentation updates

`server/docs/deployment.md` gets amended (not rewritten) to:
- Replace §2 ("build the JAR locally") with: images are built by GitHub Actions and pulled by
  Dokploy from GHCR.
- Replace §9 ("RabbitMQ — not yet a managed production service") to point at
  `docker-compose.prod.yml`.
- Update §3 (Dokploy application setup) to say "point at the GHCR image" as an alternative to
  "Dockerfile-based" builds, since Dokploy no longer needs to build from source at all.

## Verification plan

- `docker build --target api-service -t test-api .` and `--target worker -t test-worker .` succeed
  locally from `server/`.
- `docker build -t test-partner .` succeeds from `tinniestudio-partner-web/` after the
  `next.config.ts` fix; confirm `.next/standalone/server.js` exists in the image.
- `docker build -t test-client .` succeeds from `tinniestudio-client-web/` (unchanged, regression
  check only).
- `docker compose -f docker-compose.prod.yml up` starts RabbitMQ and passes its healthcheck.
- Each GitHub Actions workflow is validated by push (to a throwaway branch first via
  `workflow_dispatch`, not `main`, to avoid triggering a real production tag before everything
  else is reviewed) — pushing/triggering requires explicit permission at that time, separate from
  writing the files now.
