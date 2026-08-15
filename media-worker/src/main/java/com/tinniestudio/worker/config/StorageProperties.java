package com.tinniestudio.worker.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.storage")
@Getter @Setter
public class StorageProperties {
    private String endpoint;
    private String bucket;
    private String region = "us-east-1";
    private String accessKey;
    private String secretKey;
    // MinIO (and most S3-compatible providers) require path-style bucket
    // addressing; real AWS S3 generally prefers virtual-hosted style and has
    // deprecated path-style for many bucket/region combos. Default true for
    // local/MinIO dev, override to false via STORAGE_PATH_STYLE_ACCESS in prod.
    private boolean pathStyleAccess = true;
}
