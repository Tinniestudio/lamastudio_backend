package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

@Slf4j
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageServiceConfig {

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "MINIO")
    public S3Client minioS3Client(StorageProperties props) {
        log.info("Configuring MinIO S3Client at endpoint={}", props.getEndpoint());
        return S3Client.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .region(Region.of(props.getRegion()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "MINIO")
    public S3Presigner minioS3Presigner(StorageProperties props) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .region(Region.of(props.getRegion()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "MINIO")
    public StorageService minioStorageService(S3Client minioS3Client, S3Presigner minioS3Presigner,
                                              StorageProperties props) {
        log.info("Using MinioStorageService for bucket={}", props.getBucket());
        return new MinioStorageService(minioS3Client, minioS3Presigner, props);
    }

    @Bean
    @ConditionalOnMissingBean(StorageService.class)
    public StorageService noOpStorageService() {
        log.warn("Using NoOpStorageService — set app.storage.provider=MINIO to enable real object storage.");
        return new NoOpStorageService();
    }
}
