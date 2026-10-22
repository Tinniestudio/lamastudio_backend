package com.tinniestudio.api.billing.repository;

import com.tinniestudio.api.modules.billing.repository.UserSubscriptionRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.SubscriptionStatus;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.entity.UserSubscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-level tests for the queries the daily renewal job (a later task) relies on:
 * finding subscriptions due for renewal, resolving a subscription by its Stripe customer id,
 * and taking a pessimistic row lock immediately before charging a card so a concurrent
 * "disable auto-renew" request can't race the job.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class UserSubscriptionRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_user_subscription_repo_test")
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

    @Autowired private UserSubscriptionRepository repository;
    @Autowired private UserRepository userRepo;

    private UUID newUser() {
        User user = new User();
        user.setEmail("repo-test-" + System.nanoTime() + "@example.com");
        user.setPasswordHash("irrelevant");
        return userRepo.saveAndFlush(user).getId();
    }

    private UserSubscription newSubscription(boolean autoRenew, Instant nextRenewalAt) {
        UserSubscription sub = new UserSubscription();
        sub.setUserId(newUser());
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setAutoRenew(autoRenew);
        sub.setNextRenewalAt(nextRenewalAt);
        return sub;
    }

    @Test
    @DisplayName("findByAutoRenewTrueAndNextRenewalAtLessThanEqual only returns auto-renewing subscriptions due at or before the cutoff")
    void findByAutoRenewTrueAndNextRenewalAtLessThanEqual_findsDueSubscriptions() {
        UserSubscription due = repository.saveAndFlush(
                newSubscription(true, Instant.now().minusSeconds(60)));

        repository.saveAndFlush(newSubscription(true, Instant.now().plusSeconds(3600)));
        repository.saveAndFlush(newSubscription(false, Instant.now().minusSeconds(60)));

        List<UserSubscription> result = repository
                .findByAutoRenewTrueAndNextRenewalAtLessThanEqual(Instant.now());

        assertThat(result).extracting(UserSubscription::getId).containsExactly(due.getId());
    }

    @Test
    @DisplayName("findByStripeCustomerId resolves the subscription owning that Stripe customer id")
    void findByStripeCustomerId_returnsMatchingSubscription() {
        UserSubscription sub = newSubscription(true, Instant.now());
        sub.setStripeCustomerId("cus_abc123");
        repository.saveAndFlush(sub);

        Optional<UserSubscription> found = repository.findByStripeCustomerId("cus_abc123");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(sub.getId());
    }

    @Test
    @DisplayName("findByStripeCustomerId returns empty when no subscription has that Stripe customer id")
    void findByStripeCustomerId_returnsEmptyWhenNoMatch() {
        Optional<UserSubscription> found = repository.findByStripeCustomerId("cus_does_not_exist");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findByIdForUpdate loads the subscription by id under a row lock")
    void findByIdForUpdate_returnsTheSubscription() {
        UserSubscription sub = repository.saveAndFlush(newSubscription(true, Instant.now()));

        Optional<UserSubscription> found = repository.findByIdForUpdate(sub.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(sub.getId());
    }

    @Test
    @DisplayName("findByIdForUpdate returns empty for an unknown id")
    void findByIdForUpdate_returnsEmptyWhenMissing() {
        Optional<UserSubscription> found = repository.findByIdForUpdate(UUID.randomUUID());

        assertThat(found).isEmpty();
    }
}
