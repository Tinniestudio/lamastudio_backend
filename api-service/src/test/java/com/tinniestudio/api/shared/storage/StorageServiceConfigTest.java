package com.tinniestudio.api.shared.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StorageServiceConfig")
class StorageServiceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StorageServiceConfig.class);

    @Test
    @DisplayName("loads NoOpStorageService when STORAGE_PROVIDER is not set")
    void loadsNoOpWhenProviderNotSet() {
        runner.run(ctx ->
            assertThat(ctx.getBean(StorageService.class)).isInstanceOf(NoOpStorageService.class)
        );
    }

    @Test
    @DisplayName("loads NoOpStorageService when STORAGE_PROVIDER=NOOP")
    void loadsNoOpWhenProviderIsNoop() {
        runner.withPropertyValues("app.storage.provider=NOOP")
              .run(ctx ->
                  assertThat(ctx.getBean(StorageService.class)).isInstanceOf(NoOpStorageService.class)
              );
    }

    @Test
    @DisplayName("loads MinioStorageService when STORAGE_PROVIDER=MINIO")
    void loadsMinioWhenProviderIsMinio() {
        runner.withPropertyValues(
                "app.storage.provider=MINIO",
                "app.storage.bucket=test-bucket",
                "app.storage.region=us-east-1",
                "app.storage.endpoint=http://localhost:9000",
                "app.storage.access-key=minioadmin",
                "app.storage.secret-key=minioadmin"
        ).run(ctx ->
            assertThat(ctx.getBean(StorageService.class)).isInstanceOf(MinioStorageService.class)
        );
    }

    @Test
    @DisplayName("only one StorageService bean exists regardless of provider")
    void exactlyOneBeanExists() {
        runner.run(ctx ->
            assertThat(ctx).hasSingleBean(StorageService.class)
        );
    }
}
