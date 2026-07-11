package com.tinniestudio.api.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.entity.DomainEnums.AuthProvider;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureTestEntityManager
@Testcontainers
@ActiveProfiles("test")
class UserRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("lamastudio_repo_test")
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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("findByEmail returns user when present")
    void findByEmail() {
        User user = new User();
        user.setEmail("repo@example.com");
        user.setProvider(AuthProvider.LOCAL);
        entityManager.persistAndFlush(user);

        assertThat(userRepository.findByEmail("repo@example.com")).isPresent();
    }

    @Test
    @DisplayName("existsByEmail returns true when email exists")
    void existsByEmail() {
        User user = new User();
        user.setEmail("exists@example.com");
        user.setProvider(AuthProvider.LOCAL);
        entityManager.persistAndFlush(user);

        assertThat(userRepository.existsByEmail("exists@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("missing@example.com")).isFalse();
    }

    @Test
    @DisplayName("findById returns saved user")
    void findById() {
        User user = new User();
        user.setEmail("id@example.com");
        user.setProvider(AuthProvider.LOCAL);
        user = entityManager.persistAndFlush(user);

        assertThat(userRepository.findById(user.getId())).isPresent();
    }
}
