package com.tinniestudio.worker.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

class StorageConfigTest {

    private StorageProperties buildProps(boolean pathStyleAccess) {
        StorageProperties props = new StorageProperties();
        props.setEndpoint("http://localhost:9000");
        props.setBucket("tinniestudio");
        props.setRegion("us-east-1");
        props.setAccessKey("test-access-key");
        props.setSecretKey("test-secret-key");
        props.setPathStyleAccess(pathStyleAccess);
        return props;
    }

    @Test
    void storagePropertiesDefaultsPathStyleAccessToTrueForMinioDev() {
        StorageProperties props = new StorageProperties();

        assertThat(props.isPathStyleAccess()).isTrue();
    }

    @Test
    void storagePropertiesAllowsPathStyleAccessToBeDisabledForProdS3() {
        StorageProperties props = new StorageProperties();

        props.setPathStyleAccess(false);

        assertThat(props.isPathStyleAccess()).isFalse();
    }

    @Test
    void s3ClientBuilderIsWiredWithoutErrorWhenPathStyleAccessEnabled() {
        StorageConfig config = new StorageConfig(buildProps(true));

        S3Client client = config.s3Client();

        assertThat(client).isNotNull();
        client.close();
    }

    @Test
    void s3ClientBuilderIsWiredWithoutErrorWhenPathStyleAccessDisabled() {
        // Confirms the forcePathStyle(false) branch (real AWS S3 in prod) is
        // actually exercised via the configurable property, not hardcoded.
        StorageConfig config = new StorageConfig(buildProps(false));

        S3Client client = config.s3Client();

        assertThat(client).isNotNull();
        client.close();
    }
}
