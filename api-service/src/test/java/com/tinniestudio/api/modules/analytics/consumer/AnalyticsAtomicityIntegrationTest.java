package com.tinniestudio.api.modules.analytics.consumer;

import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsDailyRepository;
import com.tinniestudio.api.modules.analytics.service.AnalyticsEventProcessor;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Proves that a VIEW_EVENT's two writes — the {@code contents.view_count} increment and the
 * {@code content_analytics_daily} upsert — form a genuine atomic unit: if the second write
 * fails, the first write must be rolled back too.
 *
 * <p>The test class disables the test-managed transaction ({@code Propagation.NOT_SUPPORTED})
 * so the only transactional boundary in play is whatever the production code itself
 * establishes. If the production code has no real shared transaction (e.g. because a
 * self-invocation bug defeats {@code @Transactional}), the first write commits independently
 * and survives the second write's failure — which this test catches.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureTestEntityManager
@Testcontainers
@ActiveProfiles("test")
@Import({AnalyticsConsumer.class, AnalyticsEventProcessor.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalyticsAtomicityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("tinniestudio_analytics_test")
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
    private ContentRepository contentRepo;

    @Autowired
    private ContentTypeRepository contentTypeRepo;

    @Autowired
    private AnalyticsConsumer consumer;

    @MockBean
    private ContentAnalyticsDailyRepository dailyRepo;

    private UUID contentId;

    @BeforeEach
    void setUp() {
        ContentType movieType = contentTypeRepo.findBySlug("movie")
            .orElseThrow(() -> new IllegalStateException("V53 seed row 'movie' not found"));
        Content content = new Content();
        content.setTitle("Atomicity Test Content " + UUID.randomUUID());
        content.setContentType(movieType);
        content.setStatus(ContentStatus.DRAFT);
        content.setCreatedBy(UUID.randomUUID());
        content = contentRepo.saveAndFlush(content);
        contentId = content.getId();
    }

    @AfterEach
    void tearDown() {
        contentRepo.deleteById(contentId);
    }

    @Test
    @DisplayName("VIEW_EVENT: if the daily upsert fails, the view_count increment must be rolled back")
    void viewCountIncrementIsRolledBackWhenDailyUpsertFails() {
        long viewCountBefore = contentRepo.findById(contentId).orElseThrow().getViewCount();

        doThrow(new RuntimeException("simulated failure in daily upsert"))
                .when(dailyRepo).upsertViewEvent(any(), any());

        // The consumer swallows exceptions internally (logs and returns), so this must not throw.
        consumer.handleAnalyticsEvent(Map.of(
                "type", "VIEW_EVENT",
                "contentId", contentId.toString()
        ));

        long viewCountAfter = contentRepo.findById(contentId).orElseThrow().getViewCount();

        assertThat(viewCountAfter)
                .as("view_count must remain unchanged: the increment and the daily upsert "
                        + "must be one atomic transaction, so a failure in the upsert rolls back the increment")
                .isEqualTo(viewCountBefore);
    }
}
