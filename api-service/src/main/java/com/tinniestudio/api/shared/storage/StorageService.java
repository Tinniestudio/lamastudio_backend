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

    /**
     * The actual size, in bytes, of the stored object — a server-side measurement, not the
     * client-declared size from a presigned upload request. Callers that need to record how many
     * bytes an upload actually used (quota, storage accounting) must use this rather than trusting
     * a client-supplied value, which a caller could otherwise declare arbitrarily.
     */
    long getObjectSize(String key);

    void deleteObject(String key);

    /**
     * Upload raw bytes to object storage and return the public URL of the stored object.
     * Used for direct server-side uploads (e.g. category poster via multipart form).
     */
    String uploadFile(String key, byte[] content, String contentType);
}
