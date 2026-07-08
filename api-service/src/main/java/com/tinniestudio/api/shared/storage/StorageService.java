package com.tinniestudio.api.shared.storage;

import java.time.Duration;

/**
 * Abstraction for object storage (S3 / R2 / MinIO).
 * All storage access in domain services must go through this interface.
 * Implementations live in the infra layer and are selected via @ConditionalOnProperty.
 */
public interface StorageService {

    PresignedUploadResult generateUploadUrl(String key, String mimeType, long maxBytes, Duration ttl);

    String generateDownloadUrl(String key, Duration ttl);

    boolean objectExists(String key);

    void deleteObject(String key);
}
