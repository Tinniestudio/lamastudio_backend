# Batch 18 — Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the TinnieStudio platform for production: structured JSON logging for cloud log collection, Prometheus + Grafana monitoring stack, secured actuator endpoints, wildcard CORS for `*.tinniestudio.com`, rate limiting on non-auth endpoints, missing DB index audit, and k6 load testing script.

**Architecture:** All config changes in `api-service`. Prometheus scrapes via a separate internal management port (8081) so the public API port (8080) keeps admin-only access to `/actuator/metrics` and `/actuator/prometheus`. Grafana provisioned automatically from config files. k6 load test script lives at `load-test/smoke.js`.

**Tech Stack:** Spring Boot 3.3.5 Actuator, `micrometer-registry-prometheus`, `logstash-logback-encoder:7.4`, Spring Security (management security chain), `@RateLimit` (already exists), Flyway V42 for missing indexes, Prometheus + Grafana Docker images, k6 for load testing.

---

## Critical Context — Read Before Starting

- **Branch:** `staging` — never merge to `main`
- **`@RateLimit` annotation** already exists at `shared/ratelimit/RateLimit.java` and is backed by Redis. Already applied to all auth endpoints. Apply to remaining high-risk endpoints in this batch.
- **`SecurityConfig.java`** has two security chains and an existing `PUBLIC_ENDPOINTS` list that already includes `/actuator/health`. Read it fully before modifying.
- **`application.yml`** already exposes `health,info` via actuator. Add `prometheus,metrics` in this batch.
- **CORS** is configured via `AppProperties.cors.allowedOrigins` (a list). Spring's `setAllowedOrigins()` does NOT support wildcards — must switch to `setAllowedOriginPatterns()` for `*.tinniestudio.com`.
- **`docker-compose.yml`** already has api-service, media-worker, db, redis, rabbitmq, minio. Add prometheus and grafana services.
- **Management port separation:** expose actuator on `management.server.port=8081` internally only (not published to host in prod). Prometheus scrapes `api-service:8081`. The public API port (8080) still gates `/actuator/prometheus` and `/actuator/metrics` to ADMIN.
- **`video_assets`** has `idx_video_assets_status` but NO composite index on `(processing_status, updated_at)` — needed for stale job query.
- **`upload_sessions`** has no index on `expires_at` — needed for cleanup job.
- **`user_sessions`** — check V4 migration for `expires_at` index.
- No `AppException` — use `ResourceNotFoundException(String)` and `BadRequestException(String)`.

---

## File Map

**New files:**
- `api-service/src/main/resources/logback-spring.xml` — structured JSON for prod, plain for dev
- `api-service/src/main/resources/db/migration/V42__add_missing_indexes.sql` — composite indexes for background jobs
- `observability/prometheus.yml` — Prometheus scrape config
- `observability/grafana/provisioning/datasources/prometheus.yml` — Grafana auto-datasource
- `observability/grafana/provisioning/dashboards/dashboards.yml` — Grafana dashboard provider
- `load-test/k6-smoke.js` — k6 smoke test

**Modified:**
- `api-service/build.gradle` — add `micrometer-registry-prometheus` and `logstash-logback-encoder`
- `api-service/src/main/resources/application.yml` — expose prometheus endpoint, set management port
- `api-service/src/main/resources/application.prod.yml` — prod overrides (JSON logging, management port)
- `api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java` — secure metrics/prometheus endpoints
- `api-service/src/main/java/com/tinniestudio/api/shared/config/AppProperties.java` — add allowedOriginPatterns
- `api-service/src/main/java/com/tinniestudio/api/modules/partner/controller/PartnerController.java` — add `@RateLimit` on apply
- `api-service/src/main/java/com/tinniestudio/api/modules/upload/controller/UploadController.java` — add `@RateLimit` on initiate
- `docker-compose.yml` — add prometheus and grafana services

---

## Task 1: Dependencies + JSON Logging

**Files:**
- Modify: `api-service/build.gradle`
- Create: `api-service/src/main/resources/logback-spring.xml`

- [ ] **Step 1: Add dependencies to build.gradle**

In `api-service/build.gradle`, inside the `dependencies` block, add:

```groovy
implementation 'io.micrometer:micrometer-registry-prometheus'
implementation 'net.logstash.logback:logstash-logback-encoder:7.4'
```

- [ ] **Step 2: Create logback-spring.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <!-- Development profile: human-readable output -->
    <springProfile name="dev,default">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="DEBUG">
            <appender-ref ref="CONSOLE"/>
        </root>
        <logger name="org.springframework.security" level="INFO"/>
        <logger name="org.hibernate.SQL" level="DEBUG"/>
        <logger name="com.tinniestudio" level="DEBUG"/>
    </springProfile>

    <!-- Production profile: structured JSON for cloud log collection -->
    <springProfile name="prod">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>traceId</includeMdcKeyName>
                <includeMdcKeyName>userId</includeMdcKeyName>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
        <logger name="com.tinniestudio" level="INFO"/>
        <logger name="org.springframework.security" level="WARN"/>
    </springProfile>

</configuration>
```

- [ ] **Step 3: Compile to verify no conflicts**

```bash
./gradlew :api-service:compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add api-service/build.gradle \
        api-service/src/main/resources/logback-spring.xml
git commit -m "feat(b18): add prometheus micrometer dependency and structured JSON logging"
```

---

## Task 2: Actuator + Prometheus Configuration

**Files:**
- Modify: `api-service/src/main/resources/application.yml`
- Modify: `api-service/src/main/resources/application.prod.yml`
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java`

- [ ] **Step 1: Read current SecurityConfig**

```bash
cat api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java
```

Note where `PUBLIC_ENDPOINTS` is defined and how the two security chains are ordered. You need to understand the exact structure before modifying.

- [ ] **Step 2: Update application.yml management config**

Find the `management:` section in `application.yml` and replace it with:

```yaml
management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
      base-path: /actuator
  endpoint:
    health:
      show-details: when-authorized
      probes:
        enabled: true
    prometheus:
      enabled: true
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: tinniestudio-api
```

- [ ] **Step 3: Update application.prod.yml management config**

In `application.prod.yml`, ensure the management section matches:

```yaml
management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: when-authorized
```

- [ ] **Step 4: Secure metrics/prometheus in SecurityConfig**

Read the current SecurityConfig fully, then:

In `PUBLIC_ENDPOINTS` array, keep `/actuator/health` and `/actuator/info` but do NOT add metrics or prometheus there.

Add a dedicated security chain for the management port (port 8081) that allows all actuator endpoints without authentication (since management port is only accessible inside the Docker network — Prometheus scrapes it directly):

Add a new `@Bean` method before the existing main chain:

```java
@Bean
@Order(0)
public SecurityFilterChain managementSecurityChain(HttpSecurity http) throws Exception {
    http
        .securityMatcher(new org.springframework.security.web.util.matcher.AntPathRequestMatcher("/actuator/**"))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(
            org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
    return http.build();
}
```

This chain only applies when requests come via the management port (Spring Boot routes management port requests through a separate dispatcher). The main API port (8080) does not expose `/actuator/prometheus` at all since `management.server.port` separates them.

**Important:** When `management.server.port` is set, Spring Boot's actuator endpoints are ONLY available on port 8081 — they are NOT accessible on port 8080 at all. So the security chain on 8080 doesn't need to handle actuator. The management port itself is only internal (not published in docker-compose). This is the cleanest approach.

- [ ] **Step 5: Compile**

```bash
./gradlew :api-service:compileJava 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/resources/application.yml \
        api-service/src/main/resources/application.prod.yml \
        api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java
git commit -m "feat(b18): expose prometheus on internal management port 8081, secure metrics"
```

---

## Task 3: CORS Wildcard Subdomain

**Files:**
- Modify: `api-service/src/main/java/com/tinniestudio/api/shared/config/SecurityConfig.java`
- Modify: `api-service/src/main/resources/application.yml`

Spring's `CorsConfiguration.setAllowedOrigins()` does NOT support wildcards. Must use `setAllowedOriginPatterns()` instead.

- [ ] **Step 1: Read AppProperties to understand Cors class**

```bash
cat api-service/src/main/java/com/tinniestudio/api/shared/config/AppProperties.java
```

Check if `Cors` class has `allowedOrigins` as a `List<String>`. If so, we keep `allowedOrigins` for exact origins and add an `allowedOriginPatterns` field.

- [ ] **Step 2: Add allowedOriginPatterns to AppProperties.Cors**

In `AppProperties.java`, in the `Cors` inner class, add:

```java
private List<String> allowedOriginPatterns = new ArrayList<>();
```

Make sure `import java.util.ArrayList;` is present.

- [ ] **Step 3: Update SecurityConfig.corsConfigurationSource()**

In `SecurityConfig.java`, find the `corsConfigurationSource()` method. Replace `configuration.setAllowedOrigins(cors.getAllowedOrigins())` with:

```java
configuration.setAllowedOrigins(cors.getAllowedOrigins());
if (!cors.getAllowedOriginPatterns().isEmpty()) {
    configuration.setAllowedOriginPatterns(cors.getAllowedOriginPatterns());
}
```

- [ ] **Step 4: Update application.yml CORS config**

Find the `cors:` section under `app:` in `application.yml` and update:

```yaml
app:
  cors:
    allowed-origins:
      - ${FRONTEND_URL:http://localhost:3000}
      - ${APP_BASE_URL:http://localhost:8080}
    allowed-origin-patterns:
      - https://*.tinniestudio.com
      - https://tinniestudio.com
    allowed-methods:
      - GET
      - POST
      - PUT
      - PATCH
      - DELETE
      - OPTIONS
    allowed-headers:
      - "*"
    allow-credentials: true
    max-age: 3600
```

- [ ] **Step 5: Compile**

```bash
./gradlew :api-service:compileJava 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/shared/config/ \
        api-service/src/main/resources/application.yml
git commit -m "feat(b18): configure CORS wildcard for *.tinniestudio.com via allowedOriginPatterns"
```

---

## Task 4: Rate Limits on Critical Endpoints

**Files:**
- Modify: `modules/partner/controller/PartnerController.java`
- Modify: `modules/upload/controller/UploadController.java`
- Modify: `modules/content/controller/ContentController.java` (or wherever public content browse/search is)
- Modify: `modules/notification/controller/NotificationController.java`

Auth endpoints are already rate-limited. Gaps:

| Endpoint | Rate | Strategy | Risk |
|----------|------|----------|------|
| `POST /partners/applications` | 3/hour | USER_OR_IP | Spam applications |
| `POST /upload/sessions` (initiate upload) | 10/15min | USER_OR_IP | Storage abuse |
| `GET /contents` (public browse) | 60/min | IP_ONLY | Scraping |
| `POST /notifications/{id}/read` | 30/min | USER_OR_IP | Unnecessary load |

- [ ] **Step 1: Read PartnerController, UploadController, ContentController**

```bash
grep -n "@PostMapping\|@GetMapping\|@RateLimit" \
  api-service/src/main/java/com/tinniestudio/api/modules/partner/controller/PartnerController.java \
  api-service/src/main/java/com/tinniestudio/api/modules/upload/controller/UploadController.java \
  api-service/src/main/java/com/tinniestudio/api/modules/content/controller/ContentController.java
```

- [ ] **Step 2: Add @RateLimit to PartnerController.apply()**

Find `POST /partners/applications` method in `PartnerController.java`. Add before the method:

```java
@RateLimit(maxRequests = 3, windowMinutes = 60, keyStrategy = "USER_OR_IP",
           errorMessage = "Too many applications. Please try again later.")
```

Make sure `import com.tinniestudio.api.shared.ratelimit.RateLimit;` is present.

- [ ] **Step 3: Add @RateLimit to UploadController session initiation**

Find the method in `UploadController.java` that creates/initiates an upload session (`POST /upload/sessions` or similar). Add:

```java
@RateLimit(maxRequests = 10, windowMinutes = 15, keyStrategy = "USER_OR_IP",
           errorMessage = "Too many upload requests. Please wait before trying again.")
```

- [ ] **Step 4: Add @RateLimit to public content listing**

Find `ContentController.java` (public content browse endpoint). Add to the `GET /contents` or equivalent public list method:

```java
@RateLimit(maxRequests = 60, windowMinutes = 1, keyStrategy = "IP_ONLY")
```

- [ ] **Step 5: Compile**

```bash
./gradlew :api-service:compileJava 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
git add api-service/src/main/java/com/tinniestudio/api/modules/partner/controller/PartnerController.java \
        api-service/src/main/java/com/tinniestudio/api/modules/upload/controller/ \
        api-service/src/main/java/com/tinniestudio/api/modules/content/controller/ContentController.java
git commit -m "feat(b18): add rate limits to partner applications, upload initiation, public content browse"
```

---

## Task 5: Missing Database Indexes

**Files:**
- Create: `api-service/src/main/resources/db/migration/V42__add_missing_indexes.sql`

Existing gaps (from reviewing migrations and background job queries):

1. **`upload_sessions`** — no index on `expires_at` (cleanup job: `WHERE expires_at < now() AND upload_status = 'PENDING'`)
2. **`video_assets`** — no composite index on `(processing_status, updated_at)` (stale job: `WHERE processing_status = 'PROCESSING' AND updated_at < cutoff`)
3. **`user_sessions`** — no index on `expires_at` (session cleanup job)
4. **`notifications`** — `created_at DESC` index already in V39
5. **`partner_applications`** — `status` index already in V36
6. **`audit_logs`** — `created_at DESC` index already in V37
7. **`watch_history`** — check if indexed for `user_id` queries
8. **`content_reviews`** — check for `content_id` index for aggregate queries

- [ ] **Step 1: Audit existing indexes**

```bash
grep -h "CREATE INDEX\|CREATE UNIQUE INDEX" \
  api-service/src/main/resources/db/migration/V25__add_upload_sessions.sql \
  api-service/src/main/resources/db/migration/V27__add_video_assets.sql \
  api-service/src/main/resources/db/migration/V4__add_user_sessions.sql \
  api-service/src/main/resources/db/migration/V32__add_watch_history.sql \
  api-service/src/main/resources/db/migration/V33__add_content_reviews.sql 2>/dev/null
```

- [ ] **Step 2: Write V42 migration**

```sql
-- V42__add_missing_indexes.sql
-- Indexes for background job queries and analytics

-- Cleanup job: find expired pending upload sessions
CREATE INDEX IF NOT EXISTS idx_upload_sessions_expires_at
    ON upload_sessions(expires_at)
    WHERE upload_status = 'PENDING';

-- Stale video asset recovery: PROCESSING assets older than 60 min
CREATE INDEX IF NOT EXISTS idx_video_assets_status_updated
    ON video_assets(processing_status, updated_at)
    WHERE processing_status IN ('PROCESSING', 'FAILED');

-- Session cleanup: expired but not-yet-revoked sessions
CREATE INDEX IF NOT EXISTS idx_user_sessions_expires_at
    ON user_sessions(expires_at)
    WHERE revoked = false;

-- Watch history user lookups (if missing)
CREATE INDEX IF NOT EXISTS idx_watch_history_user_id
    ON watch_history(user_id);

CREATE INDEX IF NOT EXISTS idx_watch_history_content_id
    ON watch_history(content_id);

-- Content reviews aggregate query support (if missing)
CREATE INDEX IF NOT EXISTS idx_content_reviews_content_id
    ON content_reviews(content_id);

-- Partner profiles lookup by user (already unique, but explicit index for clarity)
CREATE INDEX IF NOT EXISTS idx_partner_profiles_user_id
    ON partner_profiles(user_id);
```

- [ ] **Step 3: Verify SQL syntax**

```bash
grep -c "CREATE INDEX" api-service/src/main/resources/db/migration/V42__add_missing_indexes.sql
```
Expected: 7

- [ ] **Step 4: Commit**

```bash
git add api-service/src/main/resources/db/migration/V42__add_missing_indexes.sql
git commit -m "feat(b18): add missing composite and partial indexes for background jobs and analytics"
```

---

## Task 6: Prometheus + Grafana Docker Stack

**Files:**
- Create: `observability/prometheus.yml`
- Create: `observability/grafana/provisioning/datasources/prometheus.yml`
- Create: `observability/grafana/provisioning/dashboards/dashboards.yml`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Create Prometheus config**

```yaml
# observability/prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'tinniestudio-api'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['api-service:8081']
    scrape_interval: 30s

  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']
```

- [ ] **Step 2: Create Grafana datasource provisioning**

```yaml
# observability/grafana/provisioning/datasources/prometheus.yml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
```

- [ ] **Step 3: Create Grafana dashboard provider**

```yaml
# observability/grafana/provisioning/dashboards/dashboards.yml
apiVersion: 1

providers:
  - name: default
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    options:
      path: /etc/grafana/provisioning/dashboards
```

- [ ] **Step 4: Add prometheus and grafana to docker-compose.yml**

Read the current `docker-compose.yml` fully, then add to `services:`:

```yaml
  prometheus:
    image: prom/prometheus:latest
    container_name: tinniestudio-prometheus
    restart: always
    volumes:
      - ./observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports:
      - "9090:9090"
    networks:
      - tinniestudio-network
    depends_on:
      - api-service

  grafana:
    image: grafana/grafana:latest
    container_name: tinniestudio-grafana
    restart: always
    ports:
      - "3001:3000"
    environment:
      - GF_SECURITY_ADMIN_USER=admin
      - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_PASSWORD:-admin}
      - GF_USERS_ALLOW_SIGN_UP=false
    volumes:
      - grafana_data:/var/lib/grafana
      - ./observability/grafana/provisioning:/etc/grafana/provisioning:ro
    networks:
      - tinniestudio-network
    depends_on:
      - prometheus
```

Also add `grafana_data:` to the `volumes:` section at the bottom of `docker-compose.yml`.

Also update `api-service` service to expose the management port internally (add to `ports:` only if needed for local dev — in prod it should NOT be published):

```yaml
  api-service:
    # ... existing config ...
    ports:
      - "8080:8080"
      - "8081:8081"   # management/prometheus port — remove in prod
```

- [ ] **Step 5: Verify files exist**

```bash
ls observability/
ls observability/grafana/provisioning/datasources/
```

- [ ] **Step 6: Commit**

```bash
git add observability/ docker-compose.yml
git commit -m "feat(b18): add Prometheus + Grafana to docker-compose with auto-provisioning"
```

---

## Task 7: k6 Load Testing Script

**Files:**
- Create: `load-test/k6-smoke.js`
- Create: `load-test/k6-api-load.js`

k6 is the recommended tool — pure JavaScript, no JVM, detailed metrics, simple CLI: `k6 run load-test/k6-smoke.js`.

Install: `brew install k6` or `docker run -it grafana/k6 run -`.

- [ ] **Step 1: Create k6-smoke.js (basic health check)**

```javascript
// load-test/k6-smoke.js
// Smoke test: verify the API is up and responding
// Run: k6 run load-test/k6-smoke.js
// Run with custom URL: BASE_URL=https://api.tinniestudio.com k6 run load-test/k6-smoke.js

import http from 'k6/http';
import { sleep, check } from 'k6';

export let options = {
  vus: 5,
  duration: '30s',
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  // Health check
  let health = http.get(`${BASE_URL}/actuator/health`);
  check(health, {
    'health is 200': (r) => r.status === 200,
    'health response time < 200ms': (r) => r.timings.duration < 200,
  });

  // Public content browse
  let contents = http.get(`${BASE_URL}/contents?page=0&size=10`);
  check(contents, {
    'contents status 200 or 401': (r) => r.status === 200 || r.status === 401,
  });

  sleep(1);
}
```

- [ ] **Step 2: Create k6-api-load.js (sustained load test)**

```javascript
// load-test/k6-api-load.js
// Sustained load test for authenticated endpoints
// Run: BASE_URL=http://localhost:8080 TOKEN=<jwt> k6 run load-test/k6-api-load.js

import http from 'k6/http';
import { sleep, check } from 'k6';

export let options = {
  stages: [
    { duration: '1m', target: 20 },   // ramp up
    { duration: '3m', target: 20 },   // sustained
    { duration: '1m', target: 0 },    // ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    http_req_failed: ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';

const headers = {
  'Authorization': `Bearer ${TOKEN}`,
  'Content-Type': 'application/json',
};

export default function () {
  // Authenticated content browse
  let res = http.get(`${BASE_URL}/contents?page=0&size=10`, { headers });
  check(res, { 'contents 200': (r) => r.status === 200 });

  // Notifications unread count
  if (TOKEN) {
    let notif = http.get(`${BASE_URL}/notifications/unread-count`, { headers });
    check(notif, { 'unread-count 200': (r) => r.status === 200 });
  }

  sleep(Math.random() * 2 + 1); // 1-3 second pause
}
```

- [ ] **Step 3: Verify scripts exist**

```bash
ls load-test/
node --input-type=module <<'EOF'
// Basic syntax check — just parse, don't run
import { readFileSync } from 'fs';
const src = readFileSync('load-test/k6-smoke.js', 'utf8');
console.log('Lines:', src.split('\n').length);
EOF
```

If node is not available, just verify the files are created:
```bash
wc -l load-test/k6-smoke.js load-test/k6-api-load.js
```

- [ ] **Step 4: Commit**

```bash
git add load-test/
git commit -m "feat(b18): add k6 smoke and load test scripts"
```

---

## Task 8: Full Test Suite + Final Verification

- [ ] **Step 1: Run full api-service test suite**

```bash
./gradlew :api-service:test 2>&1 | tail -40
```
Expected: all tests pass, no regressions.

- [ ] **Step 2: Compile everything including media-worker**

```bash
./gradlew compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify all migration files are in order**

```bash
ls api-service/src/main/resources/db/migration/ | sort -V | tail -10
```
Expected: V39 through V42 present.

- [ ] **Step 4: Verify observability files**

```bash
ls observability/
ls load-test/
```

- [ ] **Step 5: Fix any regressions**

Common issues after this batch:
- `logback-spring.xml` conflicts with existing Logback config — check if `logback.xml` also exists and remove any duplicate
- Management port security chain ordering issues — ensure `@Order(0)` on management chain is lower than main chains
- `AppProperties.Cors.getAllowedOriginPatterns()` null if not set — initialize as `new ArrayList<>()` in the field declaration
- `@RateLimit` on public endpoints with `keyStrategy = "IP_ONLY"` may fail if the IP extraction logic doesn't handle proxied requests — read `RateLimitAspect.java` to confirm it reads `X-Forwarded-For`

- [ ] **Step 6: Final commit for any fixes**

```bash
git add -A
git status
# Only commit files that need fixing
git commit -m "fix(b18): resolve regressions after observability batch"
```

---

## Self-Review Checklist

**Spec coverage:**
- [x] Structured JSON logging for cloud log collection (logstash-logback-encoder, prod profile only) — B18 item 1
- [x] Health actuator is public, metrics/prometheus require internal management port — B18 item 2
- [x] Domain `tinniestudio.com` with `*.tinniestudio.com` CORS via `allowedOriginPatterns` — B18 item 3
- [x] Rate limits on partner applications, upload initiation, public content browse — B18 item 4
- [x] Missing composite and partial indexes for job queries — B18 item 5
- [x] k6 smoke + load test scripts (best and simple) — B18 item 6
- [x] Prometheus + Grafana added to docker-compose with auto-provisioning — B18 item 7

**Not in scope:**
- Grafana dashboard JSON files (content depends on business requirements — admin can import Spring Boot JVM dashboard from Grafana marketplace, ID 4701)
- Distributed tracing (Zipkin/Jaeger) — deferred
- Log aggregation service (ELK/CloudWatch) — infrastructure concern outside codebase
- Alert rules (Prometheus alertmanager) — operations concern
