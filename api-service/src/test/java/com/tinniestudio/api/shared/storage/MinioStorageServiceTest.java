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
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
            // split (see StorageServiceConfig.minioS3Presigner) is handled by configuring the
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
}
