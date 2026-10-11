package com.tinniestudio.api.shared.storage;

import com.tinniestudio.api.shared.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudfront.cookie.CookiesForCustomPolicy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudFrontSigningService")
class CloudFrontSigningServiceTest {

    private String generateTestPrivateKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String base64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
    }

    @Test
    @DisplayName("returns empty when signing is disabled (dev default)")
    void returnsEmptyWhenSigningDisabled() {
        AppProperties appProperties = new AppProperties();
        appProperties.getCdn().setSigningEnabled(false);
        CloudFrontSigningService service = new CloudFrontSigningService(appProperties);

        Optional<CookiesForCustomPolicy> result = service.signVideoAccess();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns three signed cookie header values when signing is enabled")
    void returnsSignedCookiesWhenSigningEnabled() throws Exception {
        AppProperties appProperties = new AppProperties();
        appProperties.getCdn().setBaseUrl("https://cdn.tinniestudio.com");
        appProperties.getCdn().setSigningEnabled(true);
        appProperties.getCdn().setCloudfrontKeyPairId("APKATEST123");
        appProperties.getCdn().setCloudfrontPrivateKey(generateTestPrivateKeyPem());
        appProperties.getCdn().setSignedCookieTtlSeconds(21_600L);
        CloudFrontSigningService service = new CloudFrontSigningService(appProperties);

        Optional<CookiesForCustomPolicy> result = service.signVideoAccess();

        assertThat(result).isPresent();
        CookiesForCustomPolicy cookies = result.get();
        assertThat(cookies.policyHeaderValue()).startsWith("CloudFront-Policy=");
        assertThat(cookies.signatureHeaderValue()).startsWith("CloudFront-Signature=");
        assertThat(cookies.keyPairIdHeaderValue()).isEqualTo("CloudFront-Key-Pair-Id=APKATEST123");
    }

    @Test
    @DisplayName("exposes configured cookie domain and TTL for the caller to apply as cookie attributes")
    void exposesCookieDomainAndMaxAge() {
        AppProperties appProperties = new AppProperties();
        appProperties.getCdn().setCookieDomain(".tinniestudio.com");
        appProperties.getCdn().setSignedCookieTtlSeconds(21_600L);
        CloudFrontSigningService service = new CloudFrontSigningService(appProperties);

        assertThat(service.cookieDomain()).isEqualTo(".tinniestudio.com");
        assertThat(service.cookieMaxAgeSeconds()).isEqualTo(21_600L);
    }
}
