package com.tinniestudio.api.shared.storage;

import java.time.Duration;
import java.util.List;

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

    /**
     * Begins a multipart upload for a large object (RAW_VIDEO/TRAILER) and returns S3's
     * upload id — the handle every subsequent part-URL/list/complete/abort call must reference.
     */
    MultipartUploadHandle initiateMultipartUpload(String key, String mimeType);

    /**
     * A freshly-signed presigned PUT URL for one specific part of an in-progress multipart
     * upload. Called lazily, per part, right before the client uploads it — not all upfront —
     * so a multi-hour upload of a large file never runs into the TTL a single giant presigned PUT
     * would have hit.
     */
    String generatePartUploadUrl(String key, String uploadId, int partNumber, Duration ttl);

    /**
     * Parts S3 already has recorded for this upload id — the resume mechanism: a client that
     * reconnects calls this first and skips re-uploading anything already listed here.
     */
    List<UploadedPart> listUploadedParts(String key, String uploadId);

    /**
     * Finalizes a multipart upload into a single object, given the full set of completed parts
     * (partNumber + eTag, both required by S3 to assemble them in order and verify integrity).
     */
    void completeMultipartUpload(String key, String uploadId, List<CompletedPartInfo> parts);

    /**
     * Cancels an in-progress multipart upload and releases any parts already stored for it.
     * Not called anywhere in this plan yet (no abandoned-multipart-session cleanup job exists
     * yet) — added now because it's part of the same S3 API surface and trivial to implement
     * alongside the rest; wiring a cleanup job to call it is a future concern.
     */
    void abortMultipartUpload(String key, String uploadId);
}
