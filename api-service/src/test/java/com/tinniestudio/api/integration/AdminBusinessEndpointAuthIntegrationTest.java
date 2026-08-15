package com.tinniestudio.api.integration;

import com.tinniestudio.api.modules.auth.admin.entity.Admin;
import com.tinniestudio.api.modules.auth.admin.entity.AdminRoleName;
import com.tinniestudio.api.modules.auth.admin.repository.AdminRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test: a real admin login (via /auth/admin/login, which sets the admin_access_token
 * cookie) previously could not authenticate against ANY /admin/** business endpoint (partner
 * applications, users, audit log, content moderation, dashboard) — those all sit behind the
 * chain whose JwtAuthenticationFilter only recognized the user access_token cookie, signed with
 * a completely different secret. Every @WithMockUser-based controller test masked this because
 * it bypasses real JWT cookie resolution entirely. This test exercises the real login → cookie →
 * business-endpoint path end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@ExtendWith(SpringExtension.class)
class AdminBusinessEndpointAuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_admin_auth_test")
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
    @Autowired private AdminRepository adminRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String EMAIL = "admin-auth-test@tinniestudio.com";
    private static final String PASSWORD = "AdminPass123!";

    @BeforeEach
    void seedAdmin() {
        if (adminRepository.findByEmail(EMAIL).isEmpty()) {
            Admin admin = new Admin();
            admin.setEmail(EMAIL);
            admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
            admin.getRoles().add(AdminRoleName.SUPER_ADMIN);
            adminRepository.saveAndFlush(admin);
        }
    }

    @Test
    void adminLogin_thenCallsRealAdminBusinessEndpoint_succeeds() throws Exception {
        MvcResult loginResult = mockMvc.perform(
                        post("/api/v1/auth/admin/login").contextPath("/api/v1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        var adminAccessTokenCookie = loginResult.getResponse().getCookie("admin_access_token");
        assertThat(adminAccessTokenCookie).as("admin login must set admin_access_token cookie").isNotNull();

        // Business endpoint, not the /auth/admin/** login/refresh surface — this is exactly the
        // path that was previously unreachable with a real admin cookie.
        mockMvc.perform(get("/api/v1/admin/partner-applications").contextPath("/api/v1")
                        .cookie(adminAccessTokenCookie))
                .andExpect(status().isOk());
    }

    @Test
    void noCookie_adminBusinessEndpointRejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/admin/partner-applications").contextPath("/api/v1"))
                .andExpect(status().isUnauthorized());
    }
}
