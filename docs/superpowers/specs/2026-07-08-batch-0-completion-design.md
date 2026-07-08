# Batch 0 Completion — Design Spec
> Date: 2026-07-08
> Status: Approved
> Scope: Complete the two remaining Batch 0 infrastructure gaps before Batch 3 begins.

---

## Context

TinnieStudio is a Netflix-like streaming platform (Spring Boot 3 / Java 21 / Gradle multi-project).
The following Batch 0 items were deferred and must be completed before any further batches proceed:

1. **RabbitMQ publisher infrastructure** — api-service can publish to queues (needed by Batch 6 Upload + Batch 7 Worker)
2. **MinIO StorageService** — real presigned URL generation for local dev (needed by Batch 6 Upload)

These are independent tasks executed sequentially (Task 1 then Task 2). Each gets its own subagent + two-stage review.

---

## Architecture Constraints (from BATCH-PLAN.md)

- No `RabbitTemplate` outside `QueuePublisher` — all queue publishing goes through the interface
- No storage SDK (`S3Client`) outside `StorageService` — all storage access goes through the interface
- No `@Value` in service/use-case classes — all config via `@ConfigurationProperties` beans
- No `System.getenv()` anywhere in application code
- Existing `NoOpStorageService` must remain functional (tests must not break)

---

## Task 1 — RabbitMQ Publisher in api-service

### Goal
Wire RabbitMQ into the api-service so it can publish messages to any queue via a `QueuePublisher` interface. The media-worker already has partial RabbitMQ config (consumer-side only); this task adds the full topology declaration and a publisher abstraction to the api-service.

### Files to create / modify

| File | Action | Notes |
|------|--------|-------|
| `api-service/build.gradle` | Modify | Add `spring-boot-starter-amqp` dependency |
| `api-service/src/main/resources/application.yml` | Modify | Add `spring.rabbitmq.*` block (host/port/user/pass from env vars) |
| `shared/queue/QueueMessage.java` | Create | Generic envelope for all queue messages |
| `shared/queue/RabbitConfig.java` | Create | Exchange + all 5 queues + DLX bindings |
| `shared/queue/QueuePublisher.java` | Create | Interface: `publish` + `publishWithDelay` |
| `shared/queue/RabbitQueuePublisher.java` | Create | `RabbitTemplate`-backed implementation, JSON serialization |

### QueueMessage envelope
```java
public class QueueMessage<T> {
    String messageId;    // UUID
    String type;
    Instant publishedAt;
    int attempt;
    int version;
    T payload;
}
```

### RabbitMQ topology
```
Exchange: tinniestudio.direct (direct, durable, non-auto-delete)

Queues:
  media.video.process   durable, DLX → tinniestudio.direct, DLR key → media.video.failed
  media.video.retry     durable, TTL-based re-route → media.video.process
  media.video.failed    durable (dead letter sink)
  notifications.send    durable
  analytics.ingest      durable

Bindings: each queue bound to tinniestudio.direct with routing key = queue name
```

### QueuePublisher interface
```java
public interface QueuePublisher {
    void publish(String queue, Object payload);
    void publishWithDelay(String queue, Object payload, Duration delay);
}
```

### application.yml additions
```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
```

### Completion criteria
- [ ] `./gradlew :api-service:build` passes with no errors
- [ ] `RabbitQueuePublisher.publish()` sends a message with the full `QueueMessage` envelope (verified via unit test with mock `RabbitTemplate`)
- [ ] All 5 queues declared as Spring beans
- [ ] Exchange bound to all queues
- [ ] No `RabbitTemplate` usage outside `RabbitQueuePublisher`

---

## Task 2 — MinIO StorageService

### Goal
Replace the `NoOpStorageService` with a real `MinioStorageService` for local development. Uses AWS SDK v2 (S3-compatible) pointed at a MinIO endpoint. `NoOpStorageService` remains available as a fallback; provider is selected via `@ConditionalOnProperty`.

### Files to create / modify

| File | Action | Notes |
|------|--------|-------|
| `api-service/build.gradle` | Modify | Add `software.amazon.awssdk:s3` (includes S3Client + S3Presigner) |
| `shared/config/StorageProperties.java` | Create | `@ConfigurationProperties(prefix = "app.storage")` |
| `api-service/src/main/resources/application.yml` | Modify | Add `app.storage.*` block |
| `shared/storage/MinioStorageService.java` | Create | `StorageService` impl using `S3Client` + `S3Presigner` |
| `shared/storage/StorageServiceConfig.java` | Create | `@Configuration` with conditional bean selection |
| `.env.example` (root) | Create/Modify | Document new storage env vars |

### StorageProperties
```java
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {
    String provider;              // MINIO | S3 | R2 | NOOP (default: NOOP)
    String bucket;
    String region;
    String endpoint;              // e.g. http://localhost:9000 for MinIO
    String accessKey;
    String secretKey;
    long presignedUrlTtlSeconds;  // default: 3600
}
```

### Bean selection
```java
@Bean
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "MINIO")
StorageService minioStorageService(StorageProperties props) { ... }

@Bean
@ConditionalOnMissingBean(StorageService.class)
StorageService noOpStorageService() { return new NoOpStorageService(); }
```

### MinioStorageService responsibilities
- `generateUploadUrl(key, mimeType, maxBytes, ttl)` — S3Presigner PUT presigned URL
- `generateDownloadUrl(key, ttl)` — S3Presigner GET presigned URL
- `objectExists(key)` — `HeadObjectRequest`
- `deleteObject(key)` — `DeleteObjectRequest`
- `copyObject(src, dest)` — `CopyObjectRequest`
- `getMetadata(key)` — `HeadObjectResponse` → `ObjectMetadata`

### application.yml additions
```yaml
app:
  storage:
    provider: ${STORAGE_PROVIDER:NOOP}
    bucket: ${STORAGE_BUCKET:tinniestudio}
    region: ${STORAGE_REGION:us-east-1}
    endpoint: ${STORAGE_ENDPOINT:http://localhost:9000}
    access-key: ${STORAGE_ACCESS_KEY:minioadmin}
    secret-key: ${STORAGE_SECRET_KEY:minioadmin}
    presigned-url-ttl-seconds: ${STORAGE_PRESIGNED_TTL:3600}
```

### Completion criteria
- [ ] `./gradlew :api-service:build` passes with no errors
- [ ] `StorageProperties` binds correctly from `application.yml`
- [ ] `MinioStorageService` generates valid presigned PUT URL (unit test with mock S3Presigner or integration test against MinIO)
- [ ] `NoOpStorageService` still loads when `STORAGE_PROVIDER` is unset or `NOOP`
- [ ] No `S3Client` or `S3Presigner` usage outside `MinioStorageService`

---

## Non-Goals (deferred)

- `S3StorageService` for AWS prod — deferred to Batch 6
- `R2StorageService` for Cloudflare R2 — deferred to Batch 18
- Worker-side `StorageService` — media-worker will get its own impl in Batch 7
- RabbitMQ consumers in api-service — deferred to Batch 15 (notifications) and Batch 16 (analytics)

---

## Execution Order

1. Task 1 (RabbitMQ) → spec review → quality review → ✅
2. Task 2 (MinIO Storage) → spec review → quality review → ✅
3. Final code review across both tasks
4. Finish branch
