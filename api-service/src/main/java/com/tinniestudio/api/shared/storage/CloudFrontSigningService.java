package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.cookie.CookiesForCustomPolicy;
import software.amazon.awssdk.services.cloudfront.model.CustomSignerRequest;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Issues CloudFront signed cookies gating access to {@code processed/main/*} (every main-video
 * HLS manifest/segment, across all videos) to viewers who've just passed the existing
 * subscription check — see PlaybackController. Deliberately excludes {@code processed/trailers/*}
 * (VideoProcessingService segregates trailer output there specifically so it stays reachable
 * with no cookie at all — see that class's assetPrefix comment). Pure local RSA signing, no
 * network call, same reasoning as StorageServiceConfig's S3Presigner: the private key material
 * never leaves this process.
 */
@Service
@RequiredArgsConstructor
public class CloudFrontSigningService {

    private final AppProperties appProperties;
    private final CloudFrontUtilities cloudFrontUtilities = CloudFrontUtilities.create();

    public Optional<CookiesForCustomPolicy> signVideoAccess() {
        AppProperties.Cdn cdn = appProperties.getCdn();
        if (!cdn.isSigningEnabled()) {
            return Optional.empty();
        }
        CustomSignerRequest request = CustomSignerRequest.builder()
            .resourceUrl(cdn.getBaseUrl() + "/processed/main/*")
            .privateKey(loadPrivateKey(cdn.getCloudfrontPrivateKey()))
            .keyPairId(cdn.getCloudfrontKeyPairId())
            .expirationDate(Instant.now().plusSeconds(cdn.getSignedCookieTtlSeconds()))
            .build();
        return Optional.of(cloudFrontUtilities.getCookiesForCustomPolicy(request));
    }

    public String cookieDomain() {
        return appProperties.getCdn().getCookieDomain();
    }

    public long cookieMaxAgeSeconds() {
        return appProperties.getCdn().getSignedCookieTtlSeconds();
    }

    private PrivateKey loadPrivateKey(String pem) {
        try {
            String sanitized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(sanitized);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to parse CLOUDFRONT_PRIVATE_KEY — expected a PKCS#8 PEM-encoded RSA private key " +
                "(convert a traditional OpenSSL key with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem)",
                e);
        }
    }
}
