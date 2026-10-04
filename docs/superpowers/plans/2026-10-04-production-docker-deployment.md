# Production Docker Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce production-ready Docker images for `server` (api-service + media-worker), `tinniestudio-partner-web`, and `tinniestudio-client-web`, each built and pushed to GHCR by GitHub Actions, plus a production RabbitMQ `docker-compose.prod.yml` on the server.

**Architecture:** One multi-stage `Dockerfile` per repo (two named final stages — `api-service` and `worker` — in the server repo, selected via `docker build --target`). Each repo gets its own `.github/workflows/docker-build.yml` that builds on push to `main` and pushes `:<sha>` / `:latest` tags to `ghcr.io`. A new `docker-compose.prod.yml` in `server/` runs RabbitMQ standalone for production. Full design rationale: `server/docs/superpowers/specs/2026-10-04-production-docker-deployment-design.md`.

**Tech Stack:** Docker multi-stage builds, GitHub Actions (`docker/build-push-action@v6`), GHCR, Gradle (api-service/media-worker), pnpm (partner-web), npm (client-web, unchanged).

**Repos (3 separate git repos, not one monorepo):**
- `/home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server`
- `/home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-partner-web`
- `/home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web`

**Out of scope / do not do:** admin-web, Postgres/Redis production provisioning, pushing any commit to a GitHub remote (`git push`) or triggering any Actions run — those require separate explicit permission per the global GitHub-remote-safety rule, and are called out again in Task 10.

---

### Task 1: Server — root multi-stage `Dockerfile`

**Files:**
- Create: `server/Dockerfile`
- Delete: `server/api-service/Dockerfile`
- Delete: `server/media-worker/Dockerfile`
- Modify: `server/.dockerignore` (does not exist yet — create it)

- [ ] **Step 1: Create `server/.dockerignore`**

```
.git
.gradle
build
*/build
*/bin
docker-compose*.yml
docs
*.md
target
node_modules
```

- [ ] **Step 2: Create `server/Dockerfile`**

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

- [ ] **Step 3: Delete the two old per-app Dockerfiles**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
rm api-service/Dockerfile media-worker/Dockerfile
```

- [ ] **Step 4: Build both targets and verify they succeed**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
docker build --target api-service -t tinniestudio-api-service:test .
docker build --target worker -t tinniestudio-worker:test .
```
Expected: both builds complete with `Successfully tagged` (or buildkit's equivalent final `naming to ...` line), no errors. If `./gradlew help` fails with a network error, the Docker daemon itself has no internet access on this machine — expected on the Dokploy host, not expected here; if it fails here too, stop and report rather than treating it as "normal" (this build is meant to run where Gradle *can* reach the network).

- [ ] **Step 5: Sanity-check each image actually contains the right jar**

```bash
docker run --rm --entrypoint sh tinniestudio-api-service:test -c "ls /app"
docker run --rm --entrypoint sh tinniestudio-worker:test -c "ls /app && ffmpeg -version | head -1"
```
Expected: first command lists `app.jar`; second lists `app.jar` and prints an ffmpeg version line.

- [ ] **Step 6: Commit**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
git add Dockerfile .dockerignore
git add api-service/Dockerfile media-worker/Dockerfile
git commit -m "feat: replace per-app Dockerfiles with one multi-stage build

api-service and worker are now targets of a single root Dockerfile
built by CI (which has internet access), instead of each copying a
JAR built locally on a host that can't reach Gradle's mirrors."
```

---

### Task 2: Server — production RabbitMQ compose file

**Files:**
- Create: `server/docker-compose.prod.yml`

- [ ] **Step 1: Create `server/docker-compose.prod.yml`**

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

- [ ] **Step 2: Verify it starts and passes its healthcheck**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
RABBITMQ_USER=testuser RABBITMQ_PASSWORD=testpass docker compose -f docker-compose.prod.yml up -d
sleep 5
docker inspect --format '{{.State.Health.Status}}' tinniestudio-rabbitmq
```
Expected: prints `healthy` (may print `starting` immediately after `sleep 5` on a slow machine — re-run the `docker inspect` line after a few more seconds if so, don't just assume success).

- [ ] **Step 3: Tear down the test run**

```bash
docker compose -f docker-compose.prod.yml down -v
```

- [ ] **Step 4: Commit**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
git add docker-compose.prod.yml
git commit -m "feat: add production docker-compose for standalone RabbitMQ"
```

---

### Task 3: Server — update `docs/deployment.md`

**Files:**
- Modify: `server/docs/deployment.md`

- [ ] **Step 1: Replace §2 (lines 52–81, "Host constraint: build the JAR locally") with the CI-built-image description**

Replace the full section (from `## 2. Host constraint: build the JAR locally, not in the deploy pipeline` through the line before `## 3. Dokploy application setup`) with:

```markdown
## 2. Image build: GitHub Actions, not the Dokploy host

`api-service` and `worker` are both built from the single root `Dockerfile` (`docker build
--target api-service` / `--target worker`), by each repo's `.github/workflows/docker-build.yml`
on push to `main`. GitHub Actions has full internet access, so Gradle runs inside the image build
there — this is no longer done on the Dokploy host, which still cannot reach GitHub/Gradle's
mirrors.

Images are pushed to `ghcr.io/tinniestudio/lamastudio_backend-api-service` and
`ghcr.io/tinniestudio/lamastudio_backend-worker`, tagged `:<git-sha>` and `:latest`. Dokploy pulls
the finished image — it never builds from source.

---
```

- [ ] **Step 2: Replace §3 step 1–3 (Dokploy application setup, "Create a new Dokploy Application (Dockerfile-based...)") to describe image-based deployment**

Find this block (original lines 86–98):
```markdown
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
```

Replace with:
```markdown
1. Create a new Dokploy **Application** (image-based, not Dockerfile/docker-compose-based).
2. Point it at `ghcr.io/tinniestudio/lamastudio_backend-api-service:latest` /
   `ghcr.io/tinniestudio/lamastudio_backend-worker:latest` respectively (or a specific `:<sha>` tag
   to pin a release).
3. Configure Dokploy's own GHCR registry credentials so it can pull these images — see §2.
```

- [ ] **Step 3: Replace §9 ("RabbitMQ — not yet a managed production service")**

Find the full section starting `## 9. RabbitMQ — not yet a managed production service` through
the line before `## 10. First deploy: admin bootstrap`, replace with:

```markdown
## 9. RabbitMQ

RabbitMQ runs from `docker-compose.prod.yml` in this repo — a standalone production compose file
(not the dev-oriented root `docker-compose.yml`), with a persistent volume and credentials from a
local `.env` (`RABBITMQ_USER`, `RABBITMQ_PASSWORD` — never commit this file). Deploy it with:

```bash
RABBITMQ_USER=... RABBITMQ_PASSWORD=... docker compose -f docker-compose.prod.yml up -d
```

Set `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` on both the
`api-service` and `worker` Dokploy applications, attached to the same network as this compose
stack so they can resolve `rabbitmq` by hostname. No manual queue/exchange setup needed —
`RabbitConfig` in both services declares the exchange (`tinniestudio.direct`) and all queues
(`media.video.process`, `media.video.retry`, `media.video.failed`, `notifications.send`,
`analytics.ingest`) as Spring beans on startup.

---
```

- [ ] **Step 4: Remove the now-stale "Known Gaps" items 1 and 4**

Item 1 (`.env.prod` in this repo is missing variables...`) and item 4 (`No documented CI/CD
pipeline...`) at the end of the file are resolved by this change — item 4 specifically, since a
documented CI/CD pipeline now exists. Delete both list items (renumber the remaining ones: old
item 2 → 1, old item 3 → 2, old item 5 → 3).

- [ ] **Step 5: Commit**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
git add docs/deployment.md
git commit -m "docs: update deployment guide for CI-built images and RabbitMQ compose"
```

---

### Task 4: Server — GitHub Actions workflow

**Files:**
- Create: `server/.github/workflows/docker-build.yml`

- [ ] **Step 1: Create the workflow**

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

- [ ] **Step 2: Validate the YAML parses**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
python3 -c "import yaml, sys; yaml.safe_load(open('.github/workflows/docker-build.yml'))" && echo "valid YAML"
```
Expected: prints `valid YAML`. (This only checks syntax — the workflow itself isn't run; see Task 10.)

- [ ] **Step 3: Commit**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/server
git add .github/workflows/docker-build.yml
git commit -m "ci: build and push api-service/worker images to GHCR"
```

---

### Task 5: partner-web — fix `next.config.ts` for standalone output

**Files:**
- Modify: `tinniestudio-partner-web/next.config.ts`

- [ ] **Step 1: Add `output: 'standalone'`**

Current content:
```typescript
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  images: {
```

Change to:
```typescript
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: 'standalone',
  images: {
```

- [ ] **Step 2: Verify the build still produces a standalone bundle**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-partner-web
pnpm run build
ls .next/standalone/server.js
```
Expected: build succeeds, `ls` prints the path with no "No such file" error.

- [ ] **Step 3: Commit**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-partner-web
git add next.config.ts
git commit -m "fix: enable standalone output for Docker production builds"
```

---

### Task 6: partner-web — Dockerfile and `.dockerignore`

**Files:**
- Create: `tinniestudio-partner-web/Dockerfile`
- Create: `tinniestudio-partner-web/.dockerignore`

- [ ] **Step 1: Create `.dockerignore`**

```
node_modules
.next
.git
Dockerfile
.env
.env.local
.env.example
npm-debug.log
```

- [ ] **Step 2: Create `Dockerfile`**

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

- [ ] **Step 3: Build the image and verify it runs**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-partner-web
docker build --build-arg API_BASE_URL=http://localhost:8080/api/v1 \
  --build-arg NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1 \
  -t tinniestudio-partner-web:test .
docker run --rm -d --name partner-web-test -p 3100:3000 tinniestudio-partner-web:test
sleep 3
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3100
docker stop partner-web-test
```
Expected: the `docker build` completes successfully; the `curl` line prints an HTTP status code (`200` or a Next.js redirect/`307` are both fine — the point is the container served *something*, not 000/connection-refused).

- [ ] **Step 4: Commit**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-partner-web
git add Dockerfile .dockerignore
git commit -m "feat: add production Dockerfile"
```

---

### Task 7: partner-web — GitHub Actions workflow

**Files:**
- Create: `tinniestudio-partner-web/.github/workflows/docker-build.yml`

- [ ] **Step 1: Create the workflow**

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
          push: true
          build-args: |
            API_BASE_URL=${{ vars.API_BASE_URL }}
            NEXT_PUBLIC_API_BASE_URL=${{ vars.NEXT_PUBLIC_API_BASE_URL }}
          tags: |
            ghcr.io/techitcheap-com/tinniestudio-partner-web:${{ github.sha }}
            ghcr.io/techitcheap-com/tinniestudio-partner-web:latest
```

- [ ] **Step 2: Validate the YAML parses**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-partner-web
python3 -c "import yaml, sys; yaml.safe_load(open('.github/workflows/docker-build.yml'))" && echo "valid YAML"
```
Expected: prints `valid YAML`.

- [ ] **Step 3: Commit**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-partner-web
git add .github/workflows/docker-build.yml
git commit -m "ci: build and push image to GHCR"
```

---

### Task 8: client-web — verify existing Dockerfile still builds (regression check, no code change)

**Files:**
- None modified — verification only.

- [ ] **Step 1: Build the existing production target**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
docker build --target production \
  --build-arg API_BASE_URL=http://localhost:8080/api/v1 \
  --build-arg NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1 \
  -t tinniestudio-client-web:test .
```
Expected: build completes successfully with no changes needed. If it fails, stop and report the
failure — do not modify the Dockerfile to "fix" it without checking with the user first, since the
spec says this file is unchanged by design.

- [ ] **Step 2: Verify it runs**

```bash
docker run --rm -d --name client-web-test -p 3101:3000 tinniestudio-client-web:test
sleep 3
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3101
docker stop client-web-test
```
Expected: prints an HTTP status code, not a connection error.

(No commit — nothing changed in this repo's working tree from this task.)

---

### Task 9: client-web — GitHub Actions workflow

**Files:**
- Create: `tinniestudio-client-web/.github/workflows/docker-build.yml`

- [ ] **Step 1: Create the workflow**

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
          target: production
          push: true
          build-args: |
            API_BASE_URL=${{ vars.API_BASE_URL }}
            NEXT_PUBLIC_API_BASE_URL=${{ vars.NEXT_PUBLIC_API_BASE_URL }}
          tags: |
            ghcr.io/techitcheap-com/tinniestudio-client-web:${{ github.sha }}
            ghcr.io/techitcheap-com/tinniestudio-client-web:latest
```

Note `target: production` here — client-web's Dockerfile has `development`/`builder`/`production`
stages (unlike partner-web's new one, which has no `development` stage), so the target must be
named explicitly or buildx will build the last stage in the file, which happens to also be
`production` today but shouldn't be relied on implicitly.

- [ ] **Step 2: Validate the YAML parses**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
python3 -c "import yaml, sys; yaml.safe_load(open('.github/workflows/docker-build.yml'))" && echo "valid YAML"
```
Expected: prints `valid YAML`.

- [ ] **Step 3: Commit**

```bash
cd /home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/tinniestudio-client-web
git add .github/workflows/docker-build.yml
git commit -m "ci: build and push image to GHCR"
```

---

### Task 10: Handoff — what's left outside this plan

**Files:** None — this task is a checklist, not code.

- [ ] **Step 1: Confirm nothing was pushed to a remote**

```bash
for d in server tinniestudio-partner-web tinniestudio-client-web; do
  echo "=== $d ==="
  git -C "/home/ultimate/Desktop/TechItCheap.org/TinnieStudio.com/$d" status -sb
done
```
Expected: each shows local commits ahead of `origin/<branch>` — nothing pushed. Pushing, and
anything that triggers an Actions run (`workflow_dispatch` or a push to `main`), requires separate
explicit user permission per the global GitHub-remote-safety rule — do not do either as part of
executing this plan.

- [ ] **Step 2: Report the remaining manual setup to the user**

Summarize for the user (this is a reporting step, not a code change):
1. Set GitHub repo Variables `API_BASE_URL` and `NEXT_PUBLIC_API_BASE_URL` on
   `tinniestudio-partner-web` and `tinniestudio-client-web` (Settings → Secrets and variables →
   Actions → Variables).
2. Configure Dokploy with its own GHCR registry credentials so it can pull all four images
   (`api-service`, `worker`, `tinniestudio-partner-web`, `tinniestudio-client-web`).
3. Create a local, gitignored `.env` next to `server/docker-compose.prod.yml` with
   `RABBITMQ_USER`/`RABBITMQ_PASSWORD` before running it on the actual server.
4. When ready, push each repo's branch and merge to `main` (or use `workflow_dispatch`) to trigger
   the first real CI build — with the user's explicit go-ahead at that time.

---

## Self-Review Notes

- **Spec coverage:** §1 (server Dockerfile) → Task 1. §2 (compose) → Task 2. §3 (partner-web
  Dockerfile + next.config fix) → Tasks 5–6. §4 (client-web unchanged) → Task 8. §5 (GH Actions ×3)
  → Tasks 4, 7, 9. §6 (credentials/handoff) → Task 10. §7 (docs update) → Task 3. Verification plan
  → each task's build/run steps.
- **Placeholder scan:** no TBD/TODO; every step has literal file contents and literal commands.
- **Type/naming consistency:** jar glob patterns (`tinniestudio-api-service-*.jar`,
  `tinniestudio-media-worker-*.jar`) match `archivesBaseName` set in each `build.gradle` (verified
  directly, not assumed). GHCR image names match the design doc exactly. `target: production` is
  called out explicitly in Task 9 to avoid an implicit-last-stage bug when client-web's multi-stage
  Dockerfile is built in CI (unlike the ad-hoc local builds the current Dockerfile was written for).
