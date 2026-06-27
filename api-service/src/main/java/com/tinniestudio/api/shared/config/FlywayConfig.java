package com.tinniestudio.api.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Slf4j
@Configuration
@Profile("!prod")
public class FlywayConfig {

    // Non-production only: repair before migrate.
    //
    // What repair() does:
    //   - Removes FAILED migration entries from flyway_schema_history → allows retry.
    //   - Recalculates checksums for applied migrations whose files changed → resolves mismatch errors.
    //
    // Why this is safe in dev but excluded from prod:
    //   - In development, migrations are sometimes modified before they fully succeed (failed mid-run).
    //   - In production, no applied migration should ever be modified; if it is, that must be a
    //     conscious manual repair decision, not an automatic one.
    @Bean
    public FlywayMigrationStrategy repairAndMigrate() {
        return flyway -> {
            log.info("Flyway [dev]: running repair before migrate");
            flyway.repair();
            flyway.migrate();
        };
    }
}
