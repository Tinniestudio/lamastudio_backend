package com.tinniestudio.api.shared.storage;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

/**
 * No-op StorageService. Active when app.storage.provider is not set or not MINIO/S3/R2.
 * Registered as a @Bean in StorageServiceConfig — not a @Service.
 */
@Slf4j
public class NoOpStorageService implements StorageService {

    @Override
    public PresignedUploadResult generateUploadUrl(String key, String mimeType, long maxBytes, Duration ttl) {
        log.warn("NoOpStorageService: generateUploadUrl called for key={}. Set app.storage.provider=MINIO to enable real storage.", key);
        return new PresignedUploadResult(
            "http://localhost:9000/local-bucket/" + key + "?X-Amz-Signature=stub",
            key,
            Instant.now().plus(ttl)
        );
    }

    @Override
    public String generateDownloadUrl(String key, Duration ttl) {
        log.warn("NoOpStorageService: generateDownloadUrl called for key={}. Set app.storage.provider=MINIO to enable real storage.", key);
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

    @Override
    public String uploadFile(String key, byte[] content, String contentType) {
        return "https://noop.storage/" + key;
    }
}
