package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
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
        @DisplayName("passes correct bucket and key to presigner")
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
    }

    @Nested
    @DisplayName("objectExists()")
    class ObjectExistsTests {

        @Test
        @DisplayName("returns true when object found")
        void returnsTrueWhenFound() {
            when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

            assertThat(service.objectExists("some/key.mp4")).isTrue();
        }

        @Test
        @DisplayName("returns false when object not found")
        void returnsFalseWhenNotFound() {
            when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());

            assertThat(service.objectExists("missing/key.mp4")).isFalse();
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
    }
}
