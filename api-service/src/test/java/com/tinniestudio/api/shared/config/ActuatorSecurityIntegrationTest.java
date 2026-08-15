package com.tinniestudio.api.shared.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for Batch 18 #2: actuator health must be reachable without authentication,
 * metrics must require ADMIN authentication (enforced by the application itself, not merely by
 * infra port binding), and /actuator/prometheus must accept the dedicated static scrape
 * credentials used by Prometheus itself (a long-lived service identity, not the human Admin/User
 * JWT system).
 *
 * <p>{@code management.defaults.metrics.export.enabled=true} is set explicitly because
 * spring-boot-test-autoconfigure disables metrics export by default for every
 * {@code @SpringBootTest} (so tests don't accidentally push metrics to a real backend) — without
 * this override, PrometheusMetricsExportAutoConfiguration never activates in this test's context
 * and the PrometheusScrapeEndpoint bean (and therefore EndpointRequest.to(PrometheusScrapeEndpoint.class)
 * as a security matcher) doesn't exist at all. Production is unaffected; this override is test-only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@ExtendWith(SpringExtension.class)
class ActuatorSecurityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_actuator_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("app.metrics.scrape-username", () -> "test-scraper");
        registry.add("app.metrics.scrape-password", () -> "test-scrape-pass");
        registry.add("management.defaults.metrics.export.enabled", () -> true);
    }

    @Autowired
    private MockMvc mockMvc;

    private static final String CONTEXT_PATH = "/api/v1";

    @Test
    void healthEndpointIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get(CONTEXT_PATH + "/actuator/health").contextPath(CONTEXT_PATH))
                .andExpect(status().isOk());
    }

    @Test
    void metricsEndpointRejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get(CONTEXT_PATH + "/actuator/metrics").contextPath(CONTEXT_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "ROLE_USER")
    void metricsEndpointRejectsNonAdminAuthenticatedUsers() throws Exception {
        mockMvc.perform(get(CONTEXT_PATH + "/actuator/metrics").contextPath(CONTEXT_PATH))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void metricsEndpointAllowsAdmin() throws Exception {
        mockMvc.perform(get(CONTEXT_PATH + "/actuator/metrics").contextPath(CONTEXT_PATH))
                .andExpect(status().isOk());
    }

    @Test
    void prometheusEndpointRejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get(CONTEXT_PATH + "/actuator/prometheus").contextPath(CONTEXT_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheusEndpointRejectsWrongScrapeCredentials() throws Exception {
        mockMvc.perform(get(CONTEXT_PATH + "/actuator/prometheus").contextPath(CONTEXT_PATH)
                        .with(httpBasic("test-scraper", "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheusEndpointAcceptsCorrectScrapeCredentials() throws Exception {
        mockMvc.perform(get(CONTEXT_PATH + "/actuator/prometheus").contextPath(CONTEXT_PATH)
                        .with(httpBasic("test-scraper", "test-scrape-pass")))
                .andExpect(status().isOk());
    }

    @Test
    void prometheusScrapeCredentials_doNotGrantAccessToMetricsEndpoint() throws Exception {
        // ROLE_SCRAPER is scoped to /actuator/prometheus only — Basic auth isn't even the
        // configured mechanism on the /actuator/metrics chain (that one expects a JWT), so
        // scrape credentials sent there must not grant access.
        mockMvc.perform(get(CONTEXT_PATH + "/actuator/metrics").contextPath(CONTEXT_PATH)
                        .with(httpBasic("test-scraper", "test-scrape-pass")))
                .andExpect(status().isUnauthorized());
    }
}
