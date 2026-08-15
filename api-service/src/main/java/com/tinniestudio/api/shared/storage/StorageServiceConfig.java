package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Wires the single AWS-SDK-backed StorageService implementation whenever app.storage.provider is
 * MINIO or S3 — both values activate identical beans; there's no behavioral difference, only a
 * naming one so production config reads as "S3" rather than "MINIO" pointed at a real bucket.
 * Deliberately no fallback bean for any other provider value (including unset): a misconfigured
 * deployment must fail to start, not silently swap in fake storage URLs that write nothing.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageServiceConfig {

    private static final String PROVIDER_CONDITION =
        "'${app.storage.provider:}' == 'MINIO' or '${app.storage.provider:}' == 'S3'";

    @Bean
    @ConditionalOnExpression(PROVIDER_CONDITION)
    public S3Client storageS3Client(StorageProperties props) {
        log.info("Configuring S3Client (provider={}) at endpoint={}", props.getProvider(), props.getEndpoint());
        return S3Client.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .region(Region.of(props.getRegion()))
                .serviceConfiguration(pathStyleS3Config(props))
                .build();
    }

    // Deliberately built against the PUBLIC endpoint, not props.getEndpoint(). AWS SigV4 signs
    // the Host header as part of a presigned URL's signature — you cannot generate a URL against
    // one host and hand it to a caller expecting to hit a different one; the signature simply
    // won't validate (confirmed empirically: MinIO returns "MissingFields"/signature errors on a
    // presigned URL whose host was swapped post-signing). Presigning is pure local cryptography
    // with no network call, so the presigner never actually needs to reach this endpoint — it
    // only needs to match what the eventual caller (a browser, outside this container in the
    // Docker case) will use to reach storage.
    @Bean
    @ConditionalOnExpression(PROVIDER_CONDITION)
    public S3Presigner storageS3Presigner(StorageProperties props) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.getEffectivePublicEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .region(Region.of(props.getRegion()))
                .serviceConfiguration(pathStyleS3Config(props))
                .build();
    }

    @Bean
    @ConditionalOnExpression(PROVIDER_CONDITION)
    public StorageService storageService(S3Client storageS3Client, S3Presigner storageS3Presigner,
                                          StorageProperties props) {
        log.info("Using MinioStorageService (provider={}) for bucket={}", props.getProvider(), props.getBucket());
        return new MinioStorageService(storageS3Client, storageS3Presigner, props);
    }

    private S3Configuration pathStyleS3Config(StorageProperties props) {
        return S3Configuration.builder().pathStyleAccessEnabled(props.isPathStyleAccess()).build();
    }
}
