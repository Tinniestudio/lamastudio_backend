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
}
