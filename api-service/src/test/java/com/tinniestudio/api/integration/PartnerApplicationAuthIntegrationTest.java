package com.tinniestudio.api.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test: a new "public" endpoint can be coded with no @AuthenticationPrincipal
 * requirement and still 401 in the real app if SecurityConfig.PUBLIC_ENDPOINTS doesn't list it
 * (the filter chain's trailing .anyRequest().authenticated() catches it first). WebMvcTest-based
 * controller tests use addFilters=false and can't catch this — only a real filter chain can.
 *
 * This test exercises the FULL anonymous-apply flow end to end, so it depends on BOTH the
 * auth-gate change (this task) AND the service-layer change that lets apply() actually handle
 * a null principal by creating an account (the next task in this plan) — it is expected to
 * still fail with 401 (not the 500 this whole feature guards against, but also not yet the
 * 201 it ultimately proves) until that second change lands. Don't expect this test to go green
 * from this commit's changes alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@ExtendWith(SpringExtension.class)
class PartnerApplicationAuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_partner_app_auth_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired private MockMvc mockMvc;

    @Test
    void noCookie_applicationEndpointIsReachable_notUnauthorized() throws Exception {
        String body = """
            {"companyName":"Acme Corp","email":"anon-apply-test@example.com",
             "password":"Str0ng!Pass","firstName":"Anon","lastName":"Applicant"}
            """;

        mockMvc.perform(post("/api/v1/partners/applications").contextPath("/api/v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }
}
