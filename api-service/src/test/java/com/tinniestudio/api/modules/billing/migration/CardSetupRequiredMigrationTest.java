package com.tinniestudio.api.modules.billing.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CardSetupRequiredMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_migration_test")
            .withUsername("test")
            .withPassword("test");

    private Flyway flywayTo(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void existingUsersAreBackfilledExempt_newUsersDefaultRequired() throws Exception {
        // Migrate to the version immediately before the one under test, then seed data that
        // represents accounts which already existed when V60 ships.
        flywayTo("59").migrate();

        UUID legacyWithProfile = UUID.randomUUID();
        UUID legacyWithoutProfile = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, email) VALUES ('" + legacyWithProfile + "', 'legacy-with-profile@example.com')");
            stmt.execute("INSERT INTO user_profiles (user_id) VALUES ('" + legacyWithProfile + "')");
            stmt.execute("INSERT INTO users (id, email) VALUES ('" + legacyWithoutProfile + "', 'legacy-without-profile@example.com')");
            // legacyWithoutProfile intentionally gets no user_profiles row — simulates the lazy-creation gap.
        }

        // Now apply V60.
        flywayTo("60").migrate();

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            ResultSet rs1 = stmt.executeQuery(
                "SELECT card_setup_required FROM user_profiles WHERE user_id = '" + legacyWithProfile + "'");
            assertThat(rs1.next()).isTrue();
            assertThat(rs1.getBoolean("card_setup_required")).isFalse();

            ResultSet rs2 = stmt.executeQuery(
                "SELECT card_setup_required FROM user_profiles WHERE user_id = '" + legacyWithoutProfile + "'");
            assertThat(rs2.next()).isTrue();
            assertThat(rs2.getBoolean("card_setup_required")).isFalse();

            // A brand-new user created after V60 ships gets a profile with no explicit value —
            // the column default (TRUE) must apply, proving new signups still require a card.
            UUID newUser = UUID.randomUUID();
            stmt.execute("INSERT INTO users (id, email) VALUES ('" + newUser + "', 'new-user@example.com')");
            stmt.execute("INSERT INTO user_profiles (user_id) VALUES ('" + newUser + "')");
            ResultSet rs3 = stmt.executeQuery(
                "SELECT card_setup_required FROM user_profiles WHERE user_id = '" + newUser + "'");
            assertThat(rs3.next()).isTrue();
            assertThat(rs3.getBoolean("card_setup_required")).isTrue();
        }
    }
}
