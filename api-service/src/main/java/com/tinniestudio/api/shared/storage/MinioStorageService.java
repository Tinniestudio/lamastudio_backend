package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.StorageProperties;
import com.tinniestudio.api.shared.exception.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

@Slf4j
@RequiredArgsConstructor
public class MinioStorageService implements StorageService {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final StorageProperties props;

    // maxBytes is declared on the StorageService interface for caller validation.
    // Presigned PUT URLs cannot enforce a server-side content-length-range — that
    // requires presigned POST (PostObjectRequest). Callers must validate file size
    // before issuing the presigned URL.
    @Override
    public PresignedUploadResult generateUploadUrl(String key, String mimeType, long maxBytes, Duration ttl) {
        try {
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
        } catch (SdkException e) {
            throw new StorageException("Failed to generate upload URL for key=" + key, e);
        }
    }

    @Override
    public String generateDownloadUrl(String key, Duration ttl) {
        try {
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
        } catch (SdkException e) {
            throw new StorageException("Failed to generate download URL for key=" + key, e);
        }
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
        } catch (S3Exception e) {
            // MinIO can return 403 for missing objects depending on bucket ACL config.
            // Treat 403 as not-found; all other S3Exception codes are storage failures.
            if (e.statusCode() == 403) {
                log.warn("objectExists: 403 on key={} (possible permission or missing-object on MinIO) — treating as not found", key);
                return false;
            }
            throw new StorageException("Failed to check object existence for key=" + key, e);
        } catch (SdkException e) {
            throw new StorageException("Failed to check object existence for key=" + key, e);
        }
    }

    @Override
    public void deleteObject(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .build());
            log.debug("Deleted object key={}", key);
        } catch (SdkException e) {
            throw new StorageException("Failed to delete object for key=" + key, e);
        }
    }
}
