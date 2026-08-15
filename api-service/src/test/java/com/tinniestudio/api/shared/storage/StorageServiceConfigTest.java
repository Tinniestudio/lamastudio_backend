package com.tinniestudio.api.shared.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StorageServiceConfig")
class StorageServiceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StorageServiceConfig.class);

    private static final String[] VALID_PROPS = {
        "app.storage.bucket=test-bucket",
        "app.storage.region=us-east-1",
        "app.storage.endpoint=http://localhost:9000",
        "app.storage.access-key=minioadmin",
        "app.storage.secret-key=minioadmin"
    };

    @Test
    @DisplayName("no StorageService bean exists when STORAGE_PROVIDER is not set — fails fast, no silent fake fallback")
    void noBeanWhenProviderNotSet() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(StorageService.class));
    }

    @Test
    @DisplayName("no StorageService bean exists when STORAGE_PROVIDER is an unrecognized value")
    void noBeanWhenProviderUnrecognized() {
        runner.withPropertyValues("app.storage.provider=NOOP")
              .run(ctx -> assertThat(ctx).doesNotHaveBean(StorageService.class));
    }

    @Test
    @DisplayName("loads MinioStorageService when STORAGE_PROVIDER=MINIO")
    void loadsStorageServiceWhenProviderIsMinio() {
        runner.withPropertyValues(concat("app.storage.provider=MINIO"))
              .run(ctx -> assertThat(ctx.getBean(StorageService.class)).isInstanceOf(MinioStorageService.class));
    }

    @Test
    @DisplayName("loads MinioStorageService when STORAGE_PROVIDER=S3 (same client, prod-facing name)")
    void loadsStorageServiceWhenProviderIsS3() {
        runner.withPropertyValues(concat("app.storage.provider=S3"))
              .run(ctx -> assertThat(ctx.getBean(StorageService.class)).isInstanceOf(MinioStorageService.class));
    }

    @Test
    @DisplayName("only one StorageService bean exists when a valid provider is configured")
    void exactlyOneBeanExists() {
        runner.withPropertyValues(concat("app.storage.provider=MINIO"))
              .run(ctx -> assertThat(ctx).hasSingleBean(StorageService.class));
    }

    private static String[] concat(String provider) {
        String[] result = new String[VALID_PROPS.length + 1];
        result[0] = provider;
        System.arraycopy(VALID_PROPS, 0, result, 1, VALID_PROPS.length);
        return result;
    }
}
