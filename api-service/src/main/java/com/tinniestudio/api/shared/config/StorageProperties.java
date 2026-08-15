package com.tinniestudio.api.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
@Getter
@Setter
public class StorageProperties {

    // No default: an unset/unrecognized provider means no StorageService bean is created at all
    // (see StorageServiceConfig) and the app fails to start — deliberately, so a misconfigured
    // deployment fails loudly instead of silently serving fake storage URLs. Valid values: MINIO
    // (local dev / any S3-compatible endpoint) or S3 (production) — both activate the same
    // AWS-SDK-backed client, the distinction exists for config clarity, not different code paths.
    private String provider;
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
    // MinIO (and path-based local setups) need path-style requests (host/bucket/key); real AWS S3
    // generally expects virtual-hosted style (bucket.host/key) for buckets created since 2020,
    // though path-style still works for many regions/providers. Mirrors media-worker's
    // StorageProperties.pathStyleAccess — override per-environment via STORAGE_PATH_STYLE_ACCESS.
    private boolean pathStyleAccess = true;

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
