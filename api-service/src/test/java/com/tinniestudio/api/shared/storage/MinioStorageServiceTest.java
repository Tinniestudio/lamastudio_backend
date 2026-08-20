package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.StorageProperties;
import com.tinniestudio.api.shared.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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

            assertThat(result.uploadUrl()).contains("raw/abc/original.mp4");
            assertThat(result.storageKey()).isEqualTo("raw/abc/original.mp4");
            assertThat(result.expiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("passes correct bucket, key, and content-type to presigner")
        void passesCorrectBucketAndKey() throws MalformedURLException {
            PresignedPutObjectRequest presignedRequest = mock(PresignedPutObjectRequest.class);
            when(presignedRequest.url()).thenReturn(new URL("http://localhost:9000/tinniestudio/test-key"));
            when(presignedRequest.expiration()).thenReturn(Instant.now().plusSeconds(3600));
            when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedRequest);

            service.generateUploadUrl("test-key", "image/jpeg", 5 * 1024 * 1024L, Duration.ofMinutes(30));

            ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
            verify(presigner).presignPutObject(captor.capture());

            assertThat(captor.getValue().putObjectRequest().bucket()).isEqualTo("tinniestudio");
            assertThat(captor.getValue().putObjectRequest().key()).isEqualTo("test-key");
            assertThat(captor.getValue().putObjectRequest().contentType()).isEqualTo("image/jpeg");
        }

        @Test
        @DisplayName("wraps SdkException as StorageException")
        void wrapsPresignException() {
            when(presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenThrow(SdkException.builder().message("connection refused").build());

            assertThatThrownBy(() ->
                service.generateUploadUrl("raw/abc/original.mp4", "video/mp4", 1024L, Duration.ofHours(1))
            ).isInstanceOf(StorageException.class)
             .hasMessageContaining("raw/abc/original.mp4");
        }

        @Test
        @DisplayName("returns the presigner's URL verbatim — never rewritten post-signing")
        void returnsPresignerUrlUnmodified() throws MalformedURLException {
            // AWS SigV4 signs the Host header into a presigned URL's signature, so the URL must
            // come back exactly as the presigner produced it. The internal-vs-public endpoint
            // split (see StorageServiceConfig.storageS3Presigner) is handled by configuring the
            // S3Presigner itself against the public endpoint — not by rewriting its output here.
            PresignedPutObjectRequest presignedRequest = mock(PresignedPutObjectRequest.class);
            when(presignedRequest.url()).thenReturn(
                new URL("http://localhost:9000/tinniestudio/raw/abc/original.mp4?X-Amz-Signature=sig123"));
            when(presignedRequest.expiration()).thenReturn(Instant.now().plusSeconds(3600));
            when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedRequest);

            PresignedUploadResult result = service.generateUploadUrl(
                "raw/abc/original.mp4", "video/mp4", 1024L, Duration.ofHours(1)
            );

            assertThat(result.uploadUrl())
                .isEqualTo("http://localhost:9000/tinniestudio/raw/abc/original.mp4?X-Amz-Signature=sig123");
        }
    }

    @Nested
    @DisplayName("generateDownloadUrl()")
    class GenerateDownloadUrlTests {

        @Test
        @DisplayName("returns presigned GET URL string")
        void returnsPresignedGetUrl() throws MalformedURLException {
            PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
            when(presignedRequest.url()).thenReturn(new URL("http://localhost:9000/tinniestudio/processed/abc/master.m3u8?sig=test"));
            when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);

            String url = service.generateDownloadUrl("processed/abc/master.m3u8", Duration.ofMinutes(5));

            assertThat(url).contains("master.m3u8");
        }

        @Test
        @DisplayName("passes correct bucket and key to presigner")
        void passesCorrectBucketAndKey() throws MalformedURLException {
            PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
            when(presignedRequest.url()).thenReturn(new URL("http://localhost:9000/tinniestudio/processed/abc/master.m3u8?sig=test"));
            when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);

            service.generateDownloadUrl("processed/abc/master.m3u8", Duration.ofMinutes(5));

            ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
            verify(presigner).presignGetObject(captor.capture());

            assertThat(captor.getValue().getObjectRequest().bucket()).isEqualTo("tinniestudio");
            assertThat(captor.getValue().getObjectRequest().key()).isEqualTo("processed/abc/master.m3u8");
        }
    }

    @Nested
    @DisplayName("objectExists()")
    class ObjectExistsTests {

        @Test
        @DisplayName("returns true when object found and passes correct bucket and key")
        void returnsTrueWhenFound() {
            when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

            assertThat(service.objectExists("some/key.mp4")).isTrue();

            ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
            verify(s3Client).headObject(captor.capture());
            assertThat(captor.getValue().bucket()).isEqualTo("tinniestudio");
            assertThat(captor.getValue().key()).isEqualTo("some/key.mp4");
        }

        @Test
        @DisplayName("returns false when object not found (NoSuchKeyException)")
        void returnsFalseWhenNotFound() {
            when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());

            assertThat(service.objectExists("missing/key.mp4")).isFalse();
        }

        @Test
        @DisplayName("returns false when MinIO returns 403 (ACL-based not-found)")
        void returnsFalseOnForbidden() {
            when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).build());

            assertThat(service.objectExists("some/key.mp4")).isFalse();
        }

        @Test
        @DisplayName("wraps non-403 S3Exception as StorageException")
        void wrapsS3ExceptionAsStorageException() {
            when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).build());

            assertThatThrownBy(() -> service.objectExists("some/key.mp4"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("some/key.mp4");
        }
    }

    @Nested
    @DisplayName("getObjectSize()")
    class GetObjectSizeTests {

        @Test
        @DisplayName("returns the server-measured content length, not a caller-supplied value")
        void returnsContentLength() {
            when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(123_456L).build());

            long size = service.getObjectSize("raw/abc/original.mp4");

            assertThat(size).isEqualTo(123_456L);
            ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
            verify(s3Client).headObject(captor.capture());
            assertThat(captor.getValue().bucket()).isEqualTo("tinniestudio");
            assertThat(captor.getValue().key()).isEqualTo("raw/abc/original.mp4");
        }

        @Test
        @DisplayName("wraps SdkException as StorageException")
        void wrapsHeadObjectException() {
            when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(SdkException.builder().message("timeout").build());

            assertThatThrownBy(() -> service.getObjectSize("raw/abc/original.mp4"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("raw/abc/original.mp4");
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

        @Test
        @DisplayName("wraps SdkException as StorageException")
        void wrapsDeleteException() {
            doThrow(SdkException.builder().message("timeout").build())
                .when(s3Client).deleteObject(any(DeleteObjectRequest.class));

            assertThatThrownBy(() -> service.deleteObject("raw/abc/original.mp4"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("raw/abc/original.mp4");
        }
    }

    @Nested
    @DisplayName("uploadFile()")
    class UploadFileTests {

        @Test
        @DisplayName("uploads bytes and returns public URL with correct bucket and key")
        void returnsPublicUrl() {
            String url = service.uploadFile("posters/categories/action.jpg", new byte[]{1, 2, 3}, "image/jpeg");

            assertThat(url).isEqualTo("http://localhost:9000/tinniestudio/posters/categories/action.jpg");

            ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
            assertThat(captor.getValue().bucket()).isEqualTo("tinniestudio");
            assertThat(captor.getValue().key()).isEqualTo("posters/categories/action.jpg");
            assertThat(captor.getValue().contentType()).isEqualTo("image/jpeg");
            assertThat(captor.getValue().contentLength()).isEqualTo(3L);
        }

        @Test
        @DisplayName("wraps SdkException as StorageException on upload failure")
        void wrapsUploadException() {
            doThrow(SdkException.builder().message("timeout").build())
                .when(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));

            assertThatThrownBy(() ->
                service.uploadFile("posters/categories/action.jpg", new byte[]{1}, "image/jpeg")
            ).isInstanceOf(StorageException.class)
             .hasMessageContaining("posters/categories/action.jpg");
        }

        @Test
        @DisplayName("returns a URL built from publicEndpoint, not the internal endpoint, when publicEndpoint is set")
        void returnsPublicEndpointUrlWhenSet() {
            props.setEndpoint("http://host.docker.internal:9000");
            props.setPublicEndpoint("http://localhost:9000");
            MinioStorageService svc = new MinioStorageService(s3Client, presigner, props);

            String url = svc.uploadFile("partner-logos/abc/logo.png", new byte[]{1, 2, 3}, "image/png");

            assertThat(url).isEqualTo("http://localhost:9000/tinniestudio/partner-logos/abc/logo.png");
        }
    }

    @Nested
    @DisplayName("multipart upload")
    class MultipartUploadTests {

        @Test
        @DisplayName("initiateMultipartUpload returns the uploadId from S3's response")
        void initiatesMultipartUpload() {
            when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-123").build());

            MultipartUploadHandle handle = service.initiateMultipartUpload("raw/uuid/original.mp4", "video/mp4");

            assertThat(handle.uploadId()).isEqualTo("upload-123");
        }

        @Test
        @DisplayName("initiateMultipartUpload wraps SdkException as StorageException")
        void wrapsInitiateException() {
            when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenThrow(SdkException.builder().message("timeout").build());

            assertThatThrownBy(() -> service.initiateMultipartUpload("raw/uuid/original.mp4", "video/mp4"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("raw/uuid/original.mp4");
        }

        @Test
        @DisplayName("generatePartUploadUrl returns a presigned URL for the given part number")
        void generatesPartUploadUrl() throws MalformedURLException {
            PresignedUploadPartRequest presigned = mock(PresignedUploadPartRequest.class);
            when(presigned.url()).thenReturn(new URL("https://minio/part-url"));
            when(presigner.presignUploadPart(any(UploadPartPresignRequest.class))).thenReturn(presigned);

            String url = service.generatePartUploadUrl("raw/uuid/original.mp4", "upload-123", 3, Duration.ofMinutes(30));

            assertThat(url).isEqualTo("https://minio/part-url");
        }

        @Test
        @DisplayName("generatePartUploadUrl passes correct bucket, key, uploadId, and part number to presigner")
        void passesCorrectPartUploadArgs() throws MalformedURLException {
            PresignedUploadPartRequest presigned = mock(PresignedUploadPartRequest.class);
            when(presigned.url()).thenReturn(new URL("https://minio/part-url"));
            when(presigner.presignUploadPart(any(UploadPartPresignRequest.class))).thenReturn(presigned);

            service.generatePartUploadUrl("raw/uuid/original.mp4", "upload-123", 3, Duration.ofMinutes(30));

            ArgumentCaptor<UploadPartPresignRequest> captor = ArgumentCaptor.forClass(UploadPartPresignRequest.class);
            verify(presigner).presignUploadPart(captor.capture());
            assertThat(captor.getValue().uploadPartRequest().bucket()).isEqualTo("tinniestudio");
            assertThat(captor.getValue().uploadPartRequest().key()).isEqualTo("raw/uuid/original.mp4");
            assertThat(captor.getValue().uploadPartRequest().uploadId()).isEqualTo("upload-123");
            assertThat(captor.getValue().uploadPartRequest().partNumber()).isEqualTo(3);
        }

        @Test
        @DisplayName("generatePartUploadUrl wraps SdkException as StorageException")
        void wrapsGeneratePartUploadUrlException() {
            when(presigner.presignUploadPart(any(UploadPartPresignRequest.class)))
                .thenThrow(SdkException.builder().message("timeout").build());

            assertThatThrownBy(() ->
                service.generatePartUploadUrl("raw/uuid/original.mp4", "upload-123", 3, Duration.ofMinutes(30))
            ).isInstanceOf(StorageException.class)
             .hasMessageContaining("raw/uuid/original.mp4");
        }

        @Test
        @DisplayName("listUploadedParts maps S3's Part list to UploadedPart records")
        void listsUploadedParts() {
            Part part1 = Part.builder().partNumber(1).eTag("etag-1").size(1000L).build();
            Part part2 = Part.builder().partNumber(2).eTag("etag-2").size(2000L).build();
            when(s3Client.listParts(any(ListPartsRequest.class)))
                .thenReturn(ListPartsResponse.builder().parts(part1, part2).build());

            List<UploadedPart> result = service.listUploadedParts("raw/uuid/original.mp4", "upload-123");

            assertThat(result).containsExactly(
                new UploadedPart(1, "etag-1", 1000L),
                new UploadedPart(2, "etag-2", 2000L)
            );
        }

        @Test
        @DisplayName("listUploadedParts wraps SdkException as StorageException")
        void wrapsListUploadedPartsException() {
            when(s3Client.listParts(any(ListPartsRequest.class)))
                .thenThrow(SdkException.builder().message("timeout").build());

            assertThatThrownBy(() -> service.listUploadedParts("raw/uuid/original.mp4", "upload-123"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("raw/uuid/original.mp4");
        }

        @Test
        @DisplayName("completeMultipartUpload sends S3 the parts in the order given")
        void completesMultipartUpload() {
            service.completeMultipartUpload("raw/uuid/original.mp4", "upload-123",
                List.of(new CompletedPartInfo(1, "etag-1"), new CompletedPartInfo(2, "etag-2")));

            ArgumentCaptor<CompleteMultipartUploadRequest> captor = ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
            verify(s3Client).completeMultipartUpload(captor.capture());
            assertThat(captor.getValue().bucket()).isEqualTo("tinniestudio");
            assertThat(captor.getValue().key()).isEqualTo("raw/uuid/original.mp4");
            assertThat(captor.getValue().uploadId()).isEqualTo("upload-123");
            assertThat(captor.getValue().multipartUpload().parts()).extracting("partNumber", "eTag")
                .containsExactly(tuple(1, "etag-1"), tuple(2, "etag-2"));
        }

        @Test
        @DisplayName("completeMultipartUpload sorts parts by partNumber regardless of input order")
        void completeMultipartUploadSortsPartsByNumber() {
            service.completeMultipartUpload("raw/uuid/original.mp4", "upload-123",
                List.of(new CompletedPartInfo(2, "etag-2"), new CompletedPartInfo(1, "etag-1")));

            ArgumentCaptor<CompleteMultipartUploadRequest> captor = ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
            verify(s3Client).completeMultipartUpload(captor.capture());
            assertThat(captor.getValue().multipartUpload().parts()).extracting("partNumber", "eTag")
                .containsExactly(tuple(1, "etag-1"), tuple(2, "etag-2"));
        }

        @Test
        @DisplayName("completeMultipartUpload wraps SdkException as StorageException")
        void wrapsCompleteMultipartUploadException() {
            doThrow(SdkException.builder().message("timeout").build())
                .when(s3Client).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));

            assertThatThrownBy(() ->
                service.completeMultipartUpload("raw/uuid/original.mp4", "upload-123",
                    List.of(new CompletedPartInfo(1, "etag-1")))
            ).isInstanceOf(StorageException.class)
             .hasMessageContaining("raw/uuid/original.mp4");
        }

        @Test
        @DisplayName("abortMultipartUpload calls S3's abort endpoint with correct bucket, key and uploadId")
        void abortsMultipartUpload() {
            service.abortMultipartUpload("raw/uuid/original.mp4", "upload-123");

            ArgumentCaptor<AbortMultipartUploadRequest> captor = ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
            verify(s3Client).abortMultipartUpload(captor.capture());
            assertThat(captor.getValue().bucket()).isEqualTo("tinniestudio");
            assertThat(captor.getValue().key()).isEqualTo("raw/uuid/original.mp4");
            assertThat(captor.getValue().uploadId()).isEqualTo("upload-123");
        }

        @Test
        @DisplayName("abortMultipartUpload wraps SdkException as StorageException")
        void wrapsAbortMultipartUploadException() {
            doThrow(SdkException.builder().message("timeout").build())
                .when(s3Client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));

            assertThatThrownBy(() -> service.abortMultipartUpload("raw/uuid/original.mp4", "upload-123"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("raw/uuid/original.mp4");
        }
    }
}
