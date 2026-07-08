# Batch 0 Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire RabbitMQ publisher infrastructure into api-service and replace NoOpStorageService with a real MinioStorageService for local dev.

**Architecture:** Two independent tasks: Task 1 adds `QueuePublisher` to api-service (Spring AMQP, the full 5-queue topology, JSON envelope); Task 2 adds `MinioStorageService` using AWS SDK v2 S3-compatible client. All infrastructure access stays behind interfaces (`QueuePublisher`, `StorageService`) — no `RabbitTemplate` or `S3Client` may appear outside their respective implementations.

**Tech Stack:** Spring Boot 3.3.5 / Java 21 / Spring AMQP (RabbitMQ) / AWS SDK v2 (`software.amazon.awssdk:s3`) / Lombok / JUnit 5 + Mockito / Gradle

---

## File Map

### Task 1 — RabbitMQ Publisher

| Action | Path |
|--------|------|
| Modify | `api-service/build.gradle` |
| Modify | `api-service/src/main/resources/application.yml` |
| Create | `api-service/src/main/java/com/tinniestudio/api/shared/queue/QueueMessage.java` |
| Create | `api-service/src/main/java/com/tinniestudio/api/shared/queue/QueuePublisher.java` |
| Create | `api-service/src/main/java/com/tinniestudio/api/shared/queue/RabbitQueuePublisher.java` |
| Create | `api-service/src/main/java/com/tinniestudio/api/shared/queue/RabbitConfig.java` |
| Create | `api-service/src/test/java/com/tinniestudio/api/shared/queue/RabbitQueuePublisherTest.java` |

### Task 2 — MinIO StorageService

| Action | Path |
|--------|------|
| Modify | `api-service/build.gradle` |
| Modify | `api-service/src/main/resources/application.yml` |
| Modify | `api-service/src/main/java/com/tinniestudio/api/shared/storage/StorageService.java` |
| Modify | `api-service/src/main/java/com/tinniestudio/api/shared/storage/NoOpStorageService.java` |
| Create | `api-service/src/main/java/com/tinniestudio/api/shared/config/StorageProperties.java` |
| Create | `api-service/src/main/java/com/tinniestudio/api/shared/storage/MinioStorageService.java` |
| Create | `api-service/src/main/java/com/tinniestudio/api/shared/storage/StorageServiceConfig.java` |
| Create | `.env.example` (server repo root) |
| Create | `api-service/src/test/java/com/tinniestudio/api/shared/storage/MinioStorageServiceTest.java` |
| Create | `api-service/src/test/java/com/tinniestudio/api/shared/storage/StorageServiceConfigTest.java` |

---

## Task 1 — RabbitMQ Publisher in api-service

### Context
The media-worker already has a partial `RabbitConfig` (consumer side, 3 queues). The api-service has no RabbitMQ configuration at all. This task adds the full 5-queue topology declaration and a `QueuePublisher` abstraction to the api-service. No consumers are added here — publishing only.

---

### Step 1.1 — Add Spring AMQP dependency

Modify `api-service/build.gradle`. Add after the `spring-boot-starter-actuator` line:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-amqp'
```

- [ ] Add the line
- [ ] Run `./gradlew :api-service:compileJava` — expect: BUILD SUCCESSFUL

---

### Step 1.2 — Add RabbitMQ config to application.yml

Modify `api-service/src/main/resources/application.yml`. Add this block under the top-level `spring:` key (after the `data:` block):

```yaml
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
```

- [ ] Add the block
- [ ] Run `./gradlew :api-service:compileJava` — expect: BUILD SUCCESSFUL

---

### Step 1.3 — Write the failing test for QueueMessage

Create `api-service/src/test/java/com/tinniestudio/api/shared/queue/RabbitQueuePublisherTest.java`:

```java
package com.tinniestudio.api.shared.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RabbitQueuePublisher")
class RabbitQueuePublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private RabbitQueuePublisher publisher;

    @Nested
    @DisplayName("publish()")
    class PublishTests {

        @Test
        @DisplayName("sends to correct exchange with queue name as routing key")
        void sendsToCorrectExchangeAndRoutingKey() {
            publisher.publish("media.video.process", Map.of("videoAssetId", "abc-123"));

            ArgumentCaptor<QueueMessage<?>> captor = ArgumentCaptor.forClass(QueueMessage.class);
            verify(rabbitTemplate).convertAndSend(
                eq("tinniestudio.direct"),
                eq("media.video.process"),
                captor.capture()
            );

            QueueMessage<?> sent = captor.getValue();
            assertThat(sent.getMessageId()).isNotNull();
            assertThat(sent.getPublishedAt()).isNotNull();
            assertThat(sent.getAttempt()).isEqualTo(1);
            assertThat(sent.getVersion()).isEqualTo(1);
            assertThat(sent.getPayload()).isEqualTo(Map.of("videoAssetId", "abc-123"));
        }

        @Test
        @DisplayName("each call generates a unique messageId")
        void eachCallGeneratesUniqueMessageId() {
            publisher.publish("notifications.send", Map.of("type", "WELCOME"));
            publisher.publish("notifications.send", Map.of("type", "WELCOME"));

            ArgumentCaptor<QueueMessage<?>> captor = ArgumentCaptor.forClass(QueueMessage.class);
            verify(rabbitTemplate, org.mockito.Mockito.times(2))
                .convertAndSend(eq("tinniestudio.direct"), eq("notifications.send"), captor.capture());

            var ids = captor.getAllValues().stream().map(QueueMessage::getMessageId).toList();
            assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
        }
    }
}
```

- [ ] Create the file
- [ ] Run `./gradlew :api-service:test --tests "com.tinniestudio.api.shared.queue.RabbitQueuePublisherTest"` — expect: FAIL (class not found)

---

### Step 1.4 — Create QueueMessage

Create `api-service/src/main/java/com/tinniestudio/api/shared/queue/QueueMessage.java`:

```java
package com.tinniestudio.api.shared.queue;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class QueueMessage<T> {

    private String messageId;
    private String type;
    private Instant publishedAt;
    private int attempt;
    private int version;
    private T payload;

    public static <T> QueueMessage<T> of(String type, T payload) {
        QueueMessage<T> msg = new QueueMessage<>();
        msg.messageId = UUID.randomUUID().toString();
        msg.type = type;
        msg.publishedAt = Instant.now();
        msg.attempt = 1;
        msg.version = 1;
        msg.payload = payload;
        return msg;
    }
}
```

- [ ] Create the file

---

### Step 1.5 — Create QueuePublisher interface

Create `api-service/src/main/java/com/tinniestudio/api/shared/queue/QueuePublisher.java`:

```java
package com.tinniestudio.api.shared.queue;

import java.time.Duration;

public interface QueuePublisher {
    void publish(String queue, Object payload);
    void publishWithDelay(String queue, Object payload, Duration delay);
}
```

- [ ] Create the file

---

### Step 1.6 — Create RabbitQueuePublisher

Create `api-service/src/main/java/com/tinniestudio/api/shared/queue/RabbitQueuePublisher.java`:

```java
package com.tinniestudio.api.shared.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitQueuePublisher implements QueuePublisher {

    static final String EXCHANGE = "tinniestudio.direct";

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(String queue, Object payload) {
        QueueMessage<Object> message = QueueMessage.of(queue, payload);
        log.debug("Publishing message to queue={} messageId={}", queue, message.getMessageId());
        rabbitTemplate.convertAndSend(EXCHANGE, queue, message);
    }

    @Override
    public void publishWithDelay(String queue, Object payload, Duration delay) {
        QueueMessage<Object> message = QueueMessage.of(queue, payload);
        rabbitTemplate.convertAndSend(EXCHANGE, queue, message, msg -> {
            msg.getMessageProperties().setExpiration(String.valueOf(delay.toMillis()));
            return msg;
        });
    }
}
```

- [ ] Create the file
- [ ] Run `./gradlew :api-service:test --tests "com.tinniestudio.api.shared.queue.RabbitQueuePublisherTest"` — expect: PASS

---

### Step 1.7 — Create RabbitConfig (full 5-queue topology)

Create `api-service/src/main/java/com/tinniestudio/api/shared/queue/RabbitConfig.java`:

```java
package com.tinniestudio.api.shared.queue;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "tinniestudio.direct";

    public static final String QUEUE_VIDEO_PROCESS    = "media.video.process";
    public static final String QUEUE_VIDEO_RETRY      = "media.video.retry";
    public static final String QUEUE_VIDEO_FAILED     = "media.video.failed";
    public static final String QUEUE_NOTIFICATIONS    = "notifications.send";
    public static final String QUEUE_ANALYTICS_INGEST = "analytics.ingest";

    @Bean
    public DirectExchange tinniestudioExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue videoProcessQueue() {
        return QueueBuilder.durable(QUEUE_VIDEO_PROCESS)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_VIDEO_FAILED)
                .build();
    }

    @Bean
    public Queue videoRetryQueue() {
        return QueueBuilder.durable(QUEUE_VIDEO_RETRY)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_VIDEO_PROCESS)
                .build();
    }

    @Bean
    public Queue videoFailedQueue() {
        return QueueBuilder.durable(QUEUE_VIDEO_FAILED).build();
    }

    @Bean
    public Queue notificationsQueue() {
        return QueueBuilder.durable(QUEUE_NOTIFICATIONS).build();
    }

    @Bean
    public Queue analyticsIngestQueue() {
        return QueueBuilder.durable(QUEUE_ANALYTICS_INGEST).build();
    }

    @Bean
    public Binding videoProcessBinding(Queue videoProcessQueue, DirectExchange tinniestudioExchange) {
        return BindingBuilder.bind(videoProcessQueue).to(tinniestudioExchange).with(QUEUE_VIDEO_PROCESS);
    }

    @Bean
    public Binding videoRetryBinding(Queue videoRetryQueue, DirectExchange tinniestudioExchange) {
        return BindingBuilder.bind(videoRetryQueue).to(tinniestudioExchange).with(QUEUE_VIDEO_RETRY);
    }

    @Bean
    public Binding videoFailedBinding(Queue videoFailedQueue, DirectExchange tinniestudioExchange) {
        return BindingBuilder.bind(videoFailedQueue).to(tinniestudioExchange).with(QUEUE_VIDEO_FAILED);
    }

    @Bean
    public Binding notificationsBinding(Queue notificationsQueue, DirectExchange tinniestudioExchange) {
        return BindingBuilder.bind(notificationsQueue).to(tinniestudioExchange).with(QUEUE_NOTIFICATIONS);
    }

    @Bean
    public Binding analyticsIngestBinding(Queue analyticsIngestQueue, DirectExchange tinniestudioExchange) {
        return BindingBuilder.bind(analyticsIngestQueue).to(tinniestudioExchange).with(QUEUE_ANALYTICS_INGEST);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
```

- [ ] Create the file
- [ ] Run `./gradlew :api-service:build` — expect: BUILD SUCCESSFUL
- [ ] Run `./gradlew :api-service:test --tests "com.tinniestudio.api.shared.queue.RabbitQueuePublisherTest"` — expect: PASS (2 tests)

---

### Step 1.8 — Commit Task 1

```bash
git add api-service/build.gradle \
        api-service/src/main/resources/application.yml \
        api-service/src/main/java/com/tinniestudio/api/shared/queue/ \
        api-service/src/test/java/com/tinniestudio/api/shared/queue/
git commit -m "feat(infra): add RabbitMQ publisher infrastructure to api-service

- QueueMessage<T> envelope with messageId, type, publishedAt, attempt, version
- QueuePublisher interface with publish() and publishWithDelay()
- RabbitQueuePublisher: RabbitTemplate-backed, JSON serialized via Jackson2JsonMessageConverter
- RabbitConfig: declares full 5-queue topology (media.video.process/retry/failed, notifications.send, analytics.ingest) with DLX wiring
- application.yml: spring.rabbitmq.* from env vars with localhost defaults"
```

- [ ] Run command above

---

## Task 2 — MinIO StorageService

### Context
The current `StorageService` interface has 3 methods. `NoOpStorageService` is the only implementation and uses a fragile `@ConditionalOnMissingBean(name = "s3StorageService")` that references a bean name that doesn't exist. This task:
1. Adds `generateDownloadUrl` to `StorageService` (needed by Batch 8 Playback)
2. Fixes `NoOpStorageService` conditional to use type-safe `@ConditionalOnMissingBean(StorageService.class)`
3. Adds `MinioStorageService` using AWS SDK v2
4. Adds `StorageProperties` config bean
5. Moves all conditional wiring to `StorageServiceConfig`

---

### Step 2.1 — Add AWS SDK v2 dependency

Modify `api-service/build.gradle`. Add after the Stripe line:

```groovy
// AWS SDK v2 (S3-compatible — works with MinIO, S3, R2)
implementation platform('software.amazon.awssdk:bom:2.25.0')
implementation 'software.amazon.awssdk:s3'
```

- [ ] Add the two lines
- [ ] Run `./gradlew :api-service:compileJava` — expect: BUILD SUCCESSFUL

---

### Step 2.2 — Write failing tests

Create `api-service/src/test/java/com/tinniestudio/api/shared/storage/MinioStorageServiceTest.java`:

```java
package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MinioStorageService")
class MinioStorageServiceTest {

    @Mock private S3Client s3Client;
    @Mock private S3Presigner presigner;

    private StorageProperties props;
    private MinioStorageService service;

    @BeforeEach
    void setUp() {
        props = new StorageProperties();
        props.setBucket("tinniestudio");
        props.setRegion("us-east-1");
        props.setEndpoint("http://localhost:9000");
        props.setAccessKey("minioadmin");
        props.setSecretKey("minioadmin");
        props.setPresignedUrlTtlSeconds(3600L);

        service = new MinioStorageService(s3Client, presigner, props);
    }

    @Nested
    @DisplayName("generateUploadUrl()")
    class GenerateUploadUrlTests {

        @Test
        @DisplayName("returns presigned PUT URL with correct storage key and expiry")
        void returnsPresignedPutUrl() throws MalformedURLException {
            PresignedPutObjectRequest presignedRequest = mock(PresignedPutObjectRequest.class);
            when(presignedRequest.url()).thenReturn(new URL("http://localhost:9000/tinniestudio/raw/abc/original.mp4?sig=test"));
            when(presignedRequest.expiration()).thenReturn(Instant.now().plusSeconds(3600));
            when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedRequest);

            PresignedUploadResult result = service.generateUploadUrl(
                "raw/abc/original.mp4", "video/mp4", 1024L * 1024 * 1024, Duration.ofHours(1)
            );

            assertThat(result.uploadUrl()).contains("tinniestudio").contains("raw/abc/original.mp4");
            assertThat(result.storageKey()).isEqualTo("raw/abc/original.mp4");
            assertThat(result.expiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("passes correct bucket and key to presigner")
        void passesCorrectBucketAndKey() throws MalformedURLException {
            PresignedPutObjectRequest presignedRequest = mock(PresignedPutObjectRequest.class);
            when(presignedRequest.url()).thenReturn(new URL("http://localhost:9000/tinniestudio/test-key"));
            when(presignedRequest.expiration()).thenReturn(Instant.now().plusSeconds(3600));
            when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedRequest);

            service.generateUploadUrl("test-key", "image/jpeg", 5 * 1024 * 1024L, Duration.ofMinutes(30));

            ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
            verify(presigner).presignPutObject(captor.capture());

            PutObjectPresignRequest captured = captor.getValue();
            assertThat(captured.putObjectRequest().bucket()).isEqualTo("tinniestudio");
            assertThat(captured.putObjectRequest().key()).isEqualTo("test-key");
            assertThat(captured.putObjectRequest().contentType()).isEqualTo("image/jpeg");
        }
    }

    @Nested
    @DisplayName("generateDownloadUrl()")
    class GenerateDownloadUrlTests {

        @Test
        @DisplayName("returns presigned GET URL")
        void returnsPresignedGetUrl() throws MalformedURLException {
            PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
            when(presignedRequest.url()).thenReturn(new URL("http://localhost:9000/tinniestudio/processed/abc/master.m3u8?sig=test"));
            when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);

            String url = service.generateDownloadUrl("processed/abc/master.m3u8", Duration.ofMinutes(5));

            assertThat(url).contains("master.m3u8");
        }
    }

    @Nested
    @DisplayName("objectExists()")
    class ObjectExistsTests {

        @Test
        @DisplayName("returns true when object found")
        void returnsTrueWhenFound() {
            when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

            assertThat(service.objectExists("some/key.mp4")).isTrue();
        }

        @Test
        @DisplayName("returns false when object not found")
        void returnsFalseWhenNotFound() {
            when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());

            assertThat(service.objectExists("missing/key.mp4")).isFalse();
        }
    }

    @Nested
    @DisplayName("deleteObject()")
    class DeleteObjectTests {

        @Test
        @DisplayName("sends DeleteObjectRequest with correct bucket and key")
        void sendsDeleteRequest() {
            service.deleteObject("raw/abc/original.mp4");

            ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3Client).deleteObject(captor.capture());

            assertThat(captor.getValue().bucket()).isEqualTo("tinniestudio");
            assertThat(captor.getValue().key()).isEqualTo("raw/abc/original.mp4");
        }
    }
}
```

Create `api-service/src/test/java/com/tinniestudio/api/shared/storage/StorageServiceConfigTest.java`:

```java
package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.StorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StorageServiceConfig")
class StorageServiceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StorageServiceConfig.class, StorageProperties.class);

    @Test
    @DisplayName("loads NoOpStorageService when STORAGE_PROVIDER is not set")
    void loadsNoOpWhenProviderNotSet() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(StorageService.class);
            assertThat(ctx.getBean(StorageService.class)).isInstanceOf(NoOpStorageService.class);
        });
    }

    @Test
    @DisplayName("loads NoOpStorageService when STORAGE_PROVIDER=NOOP")
    void loadsNoOpWhenProviderIsNoop() {
        runner.withPropertyValues("app.storage.provider=NOOP")
              .run(ctx -> {
                  assertThat(ctx).hasSingleBean(StorageService.class);
                  assertThat(ctx.getBean(StorageService.class)).isInstanceOf(NoOpStorageService.class);
              });
    }

    @Test
    @DisplayName("loads MinioStorageService when STORAGE_PROVIDER=MINIO")
    void loadsMinioWhenProviderIsMinio() {
        runner.withPropertyValues(
                "app.storage.provider=MINIO",
                "app.storage.bucket=test-bucket",
                "app.storage.region=us-east-1",
                "app.storage.endpoint=http://localhost:9000",
                "app.storage.access-key=minioadmin",
                "app.storage.secret-key=minioadmin"
        ).run(ctx -> {
            assertThat(ctx).hasSingleBean(StorageService.class);
            assertThat(ctx.getBean(StorageService.class)).isInstanceOf(MinioStorageService.class);
        });
    }
}
```

- [ ] Create both files
- [ ] Run `./gradlew :api-service:test --tests "com.tinniestudio.api.shared.storage.*"` — expect: FAIL (classes not found)

---

### Step 2.3 — Create StorageProperties

Create `api-service/src/main/java/com/tinniestudio/api/shared/config/StorageProperties.java`:

```java
package com.tinniestudio.api.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.storage")
@Getter
@Setter
public class StorageProperties {

    private String provider = "NOOP";
    private String bucket;
    private String region = "us-east-1";
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private long presignedUrlTtlSeconds = 3600L;
}
```

- [ ] Create the file

---

### Step 2.4 — Extend StorageService interface

Replace the contents of `api-service/src/main/java/com/tinniestudio/api/shared/storage/StorageService.java`:

```java
package com.tinniestudio.api.shared.storage;

import java.time.Duration;

/**
 * Abstraction for object storage (S3 / R2 / MinIO).
 * All storage access in domain services must go through this interface (constitution §VI).
 * Implementations live in the infra layer and are selected via @ConditionalOnProperty.
 */
public interface StorageService {

    PresignedUploadResult generateUploadUrl(String key, String mimeType, long maxBytes, Duration ttl);

    String generateDownloadUrl(String key, Duration ttl);

    boolean objectExists(String key);

    void deleteObject(String key);
}
```

- [ ] Replace the file contents

---

### Step 2.5 — Update NoOpStorageService

Replace the contents of `api-service/src/main/java/com/tinniestudio/api/shared/storage/NoOpStorageService.java`:

```java
package com.tinniestudio.api.shared.storage;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

/**
 * No-op StorageService for when no storage provider is configured.
 * Selected automatically when app.storage.provider is not MINIO/S3/R2.
 * Registered as a @Bean in StorageServiceConfig — not a @Service.
 */
@Slf4j
public class NoOpStorageService implements StorageService {

    @Override
    public PresignedUploadResult generateUploadUrl(String key, String mimeType, long maxBytes, Duration ttl) {
        log.warn("NoOpStorageService: generateUploadUrl called for key={}. Set app.storage.provider to enable real storage.", key);
        return new PresignedUploadResult(
            "http://localhost:9000/local-bucket/" + key + "?X-Amz-Signature=stub",
            key,
            Instant.now().plus(ttl)
        );
    }

    @Override
    public String generateDownloadUrl(String key, Duration ttl) {
        log.warn("NoOpStorageService: generateDownloadUrl called for key={}. Set app.storage.provider to enable real storage.", key);
        return "http://localhost:9000/local-bucket/" + key;
    }

    @Override
    public boolean objectExists(String key) {
        log.warn("NoOpStorageService: objectExists called for key={}. Returning true for development.", key);
        return true;
    }

    @Override
    public void deleteObject(String key) {
        log.warn("NoOpStorageService: deleteObject called for key={}. No-op in development.", key);
    }
}
```

- [ ] Replace the file contents

---

### Step 2.6 — Create MinioStorageService

Create `api-service/src/main/java/com/tinniestudio/api/shared/storage/MinioStorageService.java`:

```java
package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@RequiredArgsConstructor
public class MinioStorageService implements StorageService {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final StorageProperties props;

    @Override
    public PresignedUploadResult generateUploadUrl(String key, String mimeType, long maxBytes, Duration ttl) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(props.getBucket())
                .key(key)
                .contentType(mimeType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(putRequest)
                .build();

        var presigned = presigner.presignPutObject(presignRequest);
        log.debug("Generated presigned PUT URL for key={} ttl={}", key, ttl);

        return new PresignedUploadResult(
            presigned.url().toString(),
            key,
            presigned.expiration()
        );
    }

    @Override
    public String generateDownloadUrl(String key, Duration ttl) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(props.getBucket())
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(getRequest)
                .build();

        var presigned = presigner.presignGetObject(presignRequest);
        log.debug("Generated presigned GET URL for key={} ttl={}", key, ttl);
        return presigned.url().toString();
    }

    @Override
    public boolean objectExists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void deleteObject(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(props.getBucket())
                .key(key)
                .build());
        log.debug("Deleted object key={}", key);
    }
}
```

- [ ] Create the file

---

### Step 2.7 — Create StorageServiceConfig

Create `api-service/src/main/java/com/tinniestudio/api/shared/storage/StorageServiceConfig.java`:

```java
package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Slf4j
@Configuration
public class StorageServiceConfig {

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "MINIO")
    public S3Client minioS3Client(StorageProperties props) {
        log.info("Configuring MinIO S3Client at endpoint={}", props.getEndpoint());
        return S3Client.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .region(Region.of(props.getRegion()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "MINIO")
    public S3Presigner minioS3Presigner(StorageProperties props) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .region(Region.of(props.getRegion()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "MINIO")
    public StorageService minioStorageService(S3Client minioS3Client, S3Presigner minioS3Presigner,
                                              StorageProperties props) {
        log.info("Using MinioStorageService for bucket={}", props.getBucket());
        return new MinioStorageService(minioS3Client, minioS3Presigner, props);
    }

    @Bean
    @ConditionalOnMissingBean(StorageService.class)
    public StorageService noOpStorageService() {
        log.warn("Using NoOpStorageService — set app.storage.provider=MINIO to enable real object storage.");
        return new NoOpStorageService();
    }
}
```

- [ ] Create the file

---

### Step 2.8 — Add storage config to application.yml

Modify `api-service/src/main/resources/application.yml`. Add this block under the `app:` key (after the `password-reset:` block):

```yaml
  storage:
    provider: ${STORAGE_PROVIDER:NOOP}
    bucket: ${STORAGE_BUCKET:tinniestudio}
    region: ${STORAGE_REGION:us-east-1}
    endpoint: ${STORAGE_ENDPOINT:http://localhost:9000}
    access-key: ${STORAGE_ACCESS_KEY:minioadmin}
    secret-key: ${STORAGE_SECRET_KEY:minioadmin}
    presigned-url-ttl-seconds: ${STORAGE_PRESIGNED_TTL:3600}
```

- [ ] Add the block

---

### Step 2.9 — Create .env.example

Create `.env.example` at the server repo root:

```bash
# ─── Database ───────────────────────────────────────────────
DB_HOST=localhost
DB_PORT=5432
DB_NAME=tinniestudio_db
DB_USER=postgres
DB_PASSWORD=postgres

# ─── Redis ──────────────────────────────────────────────────
REDIS_URL=redis://localhost:6379

# ─── RabbitMQ ───────────────────────────────────────────────
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USER=guest
RABBITMQ_PASSWORD=guest

# ─── JWT (user) ─────────────────────────────────────────────
JWT_ACCESS_SECRET=change-me-access-secret-min-32-chars
JWT_ACCESS_EXPIRATION_MS=900000
JWT_REFRESH_SECRET=change-me-refresh-secret-min-32-chars
JWT_REFRESH_EXPIRATION_MS=604800000

# ─── JWT (admin) ────────────────────────────────────────────
JWT_ADMIN_ACCESS_SECRET=change-me-admin-access-secret-min-32-chars
JWT_ADMIN_ACCESS_EXPIRATION_MS=900000
JWT_ADMIN_REFRESH_SECRET=change-me-admin-refresh-secret-min-32-chars
JWT_ADMIN_REFRESH_EXPIRATION_MS=604800000

# ─── App ────────────────────────────────────────────────────
APP_BASE_URL=http://localhost:8080
FRONTEND_URL=http://localhost:3000
ADMIN_BOOTSTRAP_TOKEN=change-me-bootstrap-token
FREE_TIER_CONTENT_LIMIT=2
COOKIE_SECURE=false
COOKIE_SAME_SITE=Lax
COOKIE_DOMAIN=

# ─── OAuth2 ─────────────────────────────────────────────────
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret

# ─── Email (Resend) ─────────────────────────────────────────
RESEND_API_KEY=re_your_api_key
RESEND_BASE_URL=https://api.resend.com
RESEND_FROM_EMAIL=no-reply@yourdomain.com

# ─── Stripe ─────────────────────────────────────────────────
STRIPE_SECRET_KEY=sk_test_your_stripe_key
STRIPE_WEBHOOK_SECRET=whsec_your_webhook_secret
CDN_BASE_URL=http://localhost:9000

# ─── Object Storage ─────────────────────────────────────────
# STORAGE_PROVIDER options: NOOP (default, no real storage), MINIO (local dev), S3 (prod)
STORAGE_PROVIDER=MINIO
STORAGE_BUCKET=tinniestudio
STORAGE_REGION=us-east-1
STORAGE_ENDPOINT=http://localhost:9000
STORAGE_ACCESS_KEY=minioadmin
STORAGE_SECRET_KEY=minioadmin
STORAGE_PRESIGNED_TTL=3600
```

- [ ] Create the file

---

### Step 2.10 — Run tests and verify

- [ ] Run `./gradlew :api-service:test --tests "com.tinniestudio.api.shared.storage.*"` — expect: PASS (all 7 tests)
- [ ] Run `./gradlew :api-service:build` — expect: BUILD SUCCESSFUL (full build, all tests)

---

### Step 2.11 — Commit Task 2

```bash
git add api-service/build.gradle \
        api-service/src/main/resources/application.yml \
        api-service/src/main/java/com/tinniestudio/api/shared/config/StorageProperties.java \
        api-service/src/main/java/com/tinniestudio/api/shared/storage/ \
        api-service/src/test/java/com/tinniestudio/api/shared/storage/ \
        .env.example
git commit -m "feat(infra): add MinioStorageService and StorageProperties for local dev

- StorageProperties: @ConfigurationProperties(prefix=app.storage) with provider/bucket/region/endpoint/accessKey/secretKey/ttl
- MinioStorageService: AWS SDK v2 S3-compatible, constructor-injected S3Client+S3Presigner for testability
- StorageServiceConfig: @ConditionalOnProperty(MINIO) for real storage, @ConditionalOnMissingBean fallback to NoOp
- StorageService interface: add generateDownloadUrl() (needed by Batch 8 Playback)
- NoOpStorageService: removed @Service/@ConditionalOnMissingBean(name), now registered via StorageServiceConfig
- .env.example: documents all required env vars including new storage vars"
```

- [ ] Run command above

---

## Verification Checklist

After both tasks are committed:

- [ ] `./gradlew :api-service:build` — BUILD SUCCESSFUL, all tests pass
- [ ] `./gradlew :media-worker:build` — BUILD SUCCESSFUL (media-worker unchanged)
- [ ] No `RabbitTemplate` usage outside `RabbitQueuePublisher`
- [ ] No `S3Client` or `S3Presigner` usage outside `MinioStorageService` and `StorageServiceConfig`
- [ ] `NoOpStorageService` has no `@Service` or `@ConditionalOnMissingBean` annotation (moved to `StorageServiceConfig`)
