package com.tinniestudio.api.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
@Getter
@Setter
public class StorageProperties {

    private String provider = "NOOP";
    private String bucket;
    private String region = "us-east-1";
    // The endpoint the API server itself uses to reach storage (S3Client/S3Presigner
    // connection + signing target). In Docker this is typically an internal-only hostname
    // (e.g. host.docker.internal) that a browser can never resolve.
    private String endpoint;
    // The endpoint returned to CLIENTS in generated URLs (presigned PUT/GET, direct upload
    // results) — must be reachable from wherever the browser/consumer actually runs. Falls back
    // to `endpoint` when unset, which is correct for any setup where the API and its callers
    // share the same network view of storage (bare-metal dev, same-VPC prod).
    private String publicEndpoint;
    private String accessKey;
    private String secretKey;
    private long presignedUrlTtlSeconds = 3600L;

    /**
     * The endpoint to hand back in URLs given to callers (browsers, other services outside the
     * API's own container). AWS SigV4 path-style presigned URLs don't sign the Host header, so
     * swapping scheme+host+port after signing while keeping path+query intact is safe and is
     * exactly how a split internal/external endpoint setup (e.g. host.docker.internal vs
     * localhost) is meant to be handled.
     */
    public String getEffectivePublicEndpoint() {
        return (publicEndpoint != null && !publicEndpoint.isBlank()) ? publicEndpoint : endpoint;
    }
}
