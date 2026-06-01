package com.lamastudio.backend.shared.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Stub StorageService for local development without S3/MinIO configured.
 * Replace with S3StorageService or MinioStorageService in Batch 6.
 */
@Slf4j
@Service
@ConditionalOnMissingBean(name = "s3StorageService")
public class NoOpStorageService implements StorageService {

    @Override
    public PresignedUploadResult generateUploadUrl(String key, String mimeType, long maxBytes, Duration ttl) {
        log.warn("NoOpStorageService: generateUploadUrl called for key={}. Configure a real StorageService for production.", key);
        return new PresignedUploadResult(
            "http://localhost:9000/local-bucket/" + key + "?X-Amz-Signature=stub",
            key,
            Instant.now().plus(ttl)
        );
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
