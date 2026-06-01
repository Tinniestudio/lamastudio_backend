package com.lamastudio.backend.shared.storage;

import java.time.Duration;

/**
 * Abstraction for object storage (S3 / R2 / MinIO).
 * All storage access in domain services must go through this interface (constitution §VI).
 * Implementations live in the infra layer and are swapped via @ConditionalOnProperty.
 */
public interface StorageService {

    PresignedUploadResult generateUploadUrl(String key, String mimeType, long maxBytes, Duration ttl);

    boolean objectExists(String key);

    void deleteObject(String key);
}
