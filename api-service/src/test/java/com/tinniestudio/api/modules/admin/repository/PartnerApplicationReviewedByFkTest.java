package com.tinniestudio.api.modules.admin.repository;

import com.tinniestudio.api.modules.auth.admin.entity.Admin;
import com.tinniestudio.api.modules.auth.admin.repository.AdminRepository;
import com.tinniestudio.api.modules.partner.repository.PartnerApplicationRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import com.tinniestudio.api.shared.entity.PartnerApplication;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression test for V43: partner_applications.reviewed_by used to reference users(id), but
 * approve()/reject() always persist the authenticated ADMIN's id (an admins.id, not a users.id
 * — admin auth is isolated from the users table per V3/V8). Every real approve/reject call was
 * hitting a foreign key violation. V43 repoints the constraint at admins(id).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PartnerApplicationReviewedByFkTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_fk_test")
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

    @Autowired PartnerApplicationRepository applicationRepo;
    @Autowired AdminRepository adminRepo;
    @Autowired UserRepository userRepo;

    private Admin persistAdmin() {
        Admin admin = new Admin();
        admin.setEmail("admin-" + UUID.randomUUID() + "@test.com");
        admin.setPasswordHash("hash");
        return adminRepo.saveAndFlush(admin);
    }

    private User persistUser() {
        User user = new User();
        user.setEmail("user-" + UUID.randomUUID() + "@test.com");
        user.setPasswordHash("hash");
        return userRepo.saveAndFlush(user);
    }

    private PartnerApplication pendingApp(UUID userId) {
        PartnerApplication app = new PartnerApplication();
        app.setUserId(userId);
        app.setCompanyName("Acme Corp");
        app.setStatus(PartnerApplicationStatus.PENDING);
        return app;
    }

    @Test
    void reviewedBy_acceptsAnAdminId_afterFix() {
        Admin admin = persistAdmin();
        User user = persistUser();
        PartnerApplication app = pendingApp(user.getId());
        app.setStatus(PartnerApplicationStatus.APPROVED);
        app.setReviewedBy(admin.getId());

        PartnerApplication saved = applicationRepo.saveAndFlush(app);

        assertThat(saved.getReviewedBy()).isEqualTo(admin.getId());
    }

    @Test
    void reviewedBy_rejectsAnUnknownId_constraintStillEnforced() {
        User user = persistUser();
        PartnerApplication app = pendingApp(user.getId());
        app.setStatus(PartnerApplicationStatus.REJECTED);
        app.setReviewedBy(UUID.randomUUID()); // no admins row with this id

        assertThatThrownBy(() -> applicationRepo.saveAndFlush(app))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
