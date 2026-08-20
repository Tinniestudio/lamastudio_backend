package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.StorageProperties;
import com.tinniestudio.api.shared.exception.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class MinioStorageService implements StorageService {

    private final S3Client s3Client;
    // Injected already configured against the PUBLIC endpoint (see StorageServiceConfig) — AWS
    // SigV4 signs the Host header into a presigned URL, so it must be built against the host the
    // eventual caller will actually use, not rewritten afterward (rewriting the host post-sign
    // invalidates the signature). presigned.url() below is therefore already the correct,
    // externally-usable URL as-is.
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
    public long getObjectSize(String key) {
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .build());
            return head.contentLength() != null ? head.contentLength() : 0L;
        } catch (SdkException e) {
            throw new StorageException("Failed to get object size for key=" + key, e);
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

    @Override
    public String uploadFile(String key, byte[] content, String contentType) {
        try {
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .contentType(contentType)
                    .contentLength((long) content.length)
                    .build(),
                RequestBody.fromBytes(content)
            );
            String endpoint = props.getEffectivePublicEndpoint().endsWith("/")
                ? props.getEffectivePublicEndpoint().substring(0, props.getEffectivePublicEndpoint().length() - 1)
                : props.getEffectivePublicEndpoint();
            return endpoint + "/" + props.getBucket() + "/" + key;
        } catch (SdkException e) {
            throw new StorageException("Failed to upload file for key=" + key, e);
        }
    }

    @Override
    public MultipartUploadHandle initiateMultipartUpload(String key, String mimeType) {
        try {
            var response = s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .contentType(mimeType)
                    .build());
            log.debug("Initiated multipart upload for key={} uploadId={}", key, response.uploadId());
            return new MultipartUploadHandle(response.uploadId());
        } catch (SdkException e) {
            throw new StorageException("Failed to initiate multipart upload for key=" + key, e);
        }
    }

    @Override
    public String generatePartUploadUrl(String key, String uploadId, int partNumber, Duration ttl) {
        try {
            UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .build();
            UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                    .signatureDuration(ttl)
                    .uploadPartRequest(uploadPartRequest)
                    .build();
            return presigner.presignUploadPart(presignRequest).url().toString();
        } catch (SdkException e) {
            throw new StorageException("Failed to generate part upload URL for key=" + key + " part=" + partNumber, e);
        }
    }

    // Real S3 caps a single ListParts response at 1000 parts (isTruncated/nextPartNumberMarker
    // for more) — not handled here, since it's currently unreachable: the largest upload type
    // (RAW_VIDEO, 10GB max per UploadConfig) at this class's 25MB part size tops out around 410
    // parts. Revisit with real pagination if either the max file size or part size ever changes
    // enough to cross that ~1000-part threshold.
    @Override
    public List<UploadedPart> listUploadedParts(String key, String uploadId) {
        try {
            var response = s3Client.listParts(ListPartsRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .build());
            return response.parts().stream()
                    .map(p -> new UploadedPart(p.partNumber(), p.eTag(), p.size()))
                    .toList();
        } catch (SdkException e) {
            throw new StorageException("Failed to list uploaded parts for key=" + key, e);
        }
    }

    @Override
    public void completeMultipartUpload(String key, String uploadId, List<CompletedPartInfo> parts) {
        try {
            // S3's CompleteMultipartUpload requires parts in strictly ascending partNumber
            // order and returns InvalidPartOrder otherwise — sort defensively rather than
            // trusting caller order.
            List<CompletedPart> completedParts = parts.stream()
                    .sorted(Comparator.comparingInt(CompletedPartInfo::partNumber))
                    .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.eTag()).build())
                    .toList();
            s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build());
            log.debug("Completed multipart upload for key={} uploadId={} parts={}", key, uploadId, parts.size());
        } catch (SdkException e) {
            throw new StorageException("Failed to complete multipart upload for key=" + key, e);
        }
    }

    @Override
    public void abortMultipartUpload(String key, String uploadId) {
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .build());
            log.debug("Aborted multipart upload for key={} uploadId={}", key, uploadId);
        } catch (SdkException e) {
            throw new StorageException("Failed to abort multipart upload for key=" + key, e);
        }
    }
}
