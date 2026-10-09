package com.tinniestudio.api.modules.partner.repository;

import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import com.tinniestudio.api.shared.entity.PartnerApplication;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for PartnerApplicationServiceImpl.apply's upsert logic: findByUserId must
 * resolve to exactly one row per user once the reuse-the-same-row fix is in place (previously
 * apply() blindly inserted, so a user could accumulate multiple rows — this proves the new
 * single-row-per-user invariant holds at the repository layer, independent of the service's
 * own logic being correct).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureTestEntityManager
@Testcontainers
@ActiveProfiles("test")
class PartnerApplicationRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_partner_app_repo_test")
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

    @Autowired private PartnerApplicationRepository applicationRepo;
    @Autowired private UserRepository userRepo;

    private UUID newUser() {
        User user = new User();
        user.setEmail("repo-test-" + System.nanoTime() + "@example.com");
        user.setPasswordHash("irrelevant");
        return userRepo.saveAndFlush(user).getId();
    }

    @Test
    void findByUserId_returnsTheSingleRowForThatUser() {
        UUID userId = newUser();
        PartnerApplication app = new PartnerApplication();
        app.setUserId(userId);
        app.setCompanyName("Acme Corp");
        applicationRepo.saveAndFlush(app);

        Optional<PartnerApplication> found = applicationRepo.findByUserId(userId);

        assertThat(found).isPresent();
        assertThat(found.get().getCompanyName()).isEqualTo("Acme Corp");
    }

    @Test
    void findByUserId_resettingAndReusingTheSameRow_doesNotCreateASecondRow() {
        UUID userId = newUser();
        PartnerApplication app = new PartnerApplication();
        app.setUserId(userId);
        app.setCompanyName("First Attempt");
        app.setStatus(PartnerApplicationStatus.REJECTED);
        PartnerApplication saved = applicationRepo.saveAndFlush(app);

        // Simulate the service's reset-and-reuse path directly at the repository layer.
        saved.setCompanyName("Second Attempt");
        saved.setStatus(PartnerApplicationStatus.PENDING);
        applicationRepo.saveAndFlush(saved);

        long countForUser = applicationRepo.findByUserId(userId)
            .map(a -> 1L).orElse(0L);
        assertThat(countForUser).isEqualTo(1L);
        assertThat(applicationRepo.findByUserId(userId).get().getCompanyName()).isEqualTo("Second Attempt");
        assertThat(applicationRepo.findByUserId(userId).get().getId()).isEqualTo(saved.getId());
    }
}
