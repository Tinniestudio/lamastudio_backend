package com.tinniestudio.api.modules.billing.repository;

import com.tinniestudio.api.shared.entity.Coupon;
import com.tinniestudio.api.shared.entity.DomainEnums.DiscountType;
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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the coupon redemption lost-update race: CouponServiceImpl.redeemCoupon
 * used to read uses_count, check it against max_uses in Java, then save — allowing two
 * concurrent redemptions to both pass the check before either commit, over-redeeming a
 * coupon capped at N uses.
 *
 * The fix replaces that read-then-write with a single atomic conditional UPDATE
 * (CouponRepository.incrementUsesCountIfUnderLimit). This test hits a real Postgres instance
 * (not H2/mocks) and proves the SQL-level guard itself is correct: once uses_count reaches
 * max_uses, the conditional UPDATE affects zero rows and uses_count never exceeds the cap,
 * no matter how many times it's called. Atomicity of a single UPDATE statement is what
 * eliminates the race — this doesn't need literal concurrent threads to prove the guard works.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class CouponRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_coupon_test")
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
    private CouponRepository couponRepository;

    private Coupon seedCoupon(String code, Integer maxUses, int initialUsesCount) {
        Coupon coupon = new Coupon();
        coupon.setCode(code);
        coupon.setDiscountType(DiscountType.PERCENTAGE);
        coupon.setDiscountValue(BigDecimal.TEN);
        coupon.setCurrency("CAD");
        coupon.setMaxUses(maxUses);
        coupon.setUsesCount(initialUsesCount);
        coupon.setActive(true);
        return couponRepository.saveAndFlush(coupon);
    }

    @Test
    @DisplayName("incrementUsesCountIfUnderLimit succeeds while under the cap and stops exactly at max_uses")
    void incrementSucceedsUnderCapAndStopsAtLimit() {
        Coupon coupon = seedCoupon("CAP2-" + System.nanoTime(), 2, 0);

        int firstResult = couponRepository.incrementUsesCountIfUnderLimit(coupon.getId());
        int secondResult = couponRepository.incrementUsesCountIfUnderLimit(coupon.getId());
        int thirdResult = couponRepository.incrementUsesCountIfUnderLimit(coupon.getId());

        assertThat(firstResult).isEqualTo(1);
        assertThat(secondResult).isEqualTo(1);
        // Third call must be rejected at the SQL level: 0 rows affected, no lost update.
        assertThat(thirdResult).isEqualTo(0);

        Coupon reloaded = couponRepository.findById(coupon.getId()).orElseThrow();
        assertThat(reloaded.getUsesCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("incrementUsesCountIfUnderLimit returns 0 immediately once uses_count already equals max_uses")
    void incrementReturnsZeroWhenAlreadyAtLimit() {
        Coupon coupon = seedCoupon("ATCAP-" + System.nanoTime(), 1, 1);

        int result = couponRepository.incrementUsesCountIfUnderLimit(coupon.getId());

        assertThat(result).isEqualTo(0);
        Coupon reloaded = couponRepository.findById(coupon.getId()).orElseThrow();
        assertThat(reloaded.getUsesCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("incrementUsesCountIfUnderLimit allows unlimited redemptions when max_uses is null")
    void incrementAllowsUnlimitedUsesWhenMaxUsesIsNull() {
        Coupon coupon = seedCoupon("NOLIMIT-" + System.nanoTime(), null, 100);

        int result = couponRepository.incrementUsesCountIfUnderLimit(coupon.getId());

        assertThat(result).isEqualTo(1);
        Coupon reloaded = couponRepository.findById(coupon.getId()).orElseThrow();
        assertThat(reloaded.getUsesCount()).isEqualTo(101);
    }
}
