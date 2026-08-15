package com.tinniestudio.api.modules.analytics.service;

import com.tinniestudio.api.modules.analytics.dto.AdminRevenueAnalyticsResponse;
import com.tinniestudio.api.modules.analytics.dto.AnalyticsSummaryResponse;
import com.tinniestudio.api.modules.analytics.dto.WeeklyAnalyticsSummaryResponse;
import com.tinniestudio.api.modules.analytics.entity.ContentAnalyticsWeekly;
import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsDailyRepository;
import com.tinniestudio.api.modules.analytics.repository.ContentAnalyticsWeeklyRepository;
import com.tinniestudio.api.modules.billing.repository.PaymentRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.shared.entity.Content;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnalyticsServiceImpl — Gap 1 (admin revenue) and Gap 2 (weekly rollup)")
class AnalyticsServiceImplTest {

    @Mock ContentAnalyticsDailyRepository dailyRepo;
    @Mock ContentAnalyticsWeeklyRepository weeklyRepo;
    @Mock ContentRepository contentRepo;
    @Mock PaymentRepository paymentRepo;

    @InjectMocks AnalyticsServiceImpl service;

    UUID partnerId;

    @BeforeEach
    void setUp() {
        partnerId = UUID.randomUUID();
    }

    // ── Gap 1: admin-only revenue ──────────────────────────────────────────

    @Test
    @DisplayName("getAdminRevenueAnalytics sums Payment amounts via the payments table")
    void getAdminRevenueAnalytics_sourcesFromPaymentTable() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);

        when(paymentRepo.sumAmountBetween(any(), any())).thenReturn(BigDecimal.valueOf(4999.99));
        when(paymentRepo.countSuccessfulBetween(any(), any())).thenReturn(42L);

        AdminRevenueAnalyticsResponse result = service.getAdminRevenueAnalytics(from, to);

        assertThat(result.totalRevenue()).isEqualByComparingTo("4999.99");
        assertThat(result.successfulPaymentCount()).isEqualTo(42L);
        assertThat(result.from()).isEqualTo(from);
        assertThat(result.to()).isEqualTo(to);
    }

    @Test
    @DisplayName("getAdminRevenueAnalytics defaults null sum to zero (no payments in range)")
    void getAdminRevenueAnalytics_nullSum_defaultsToZero() {
        when(paymentRepo.sumAmountBetween(any(), any())).thenReturn(null);
        when(paymentRepo.countSuccessfulBetween(any(), any())).thenReturn(0L);

        AdminRevenueAnalyticsResponse result =
                service.getAdminRevenueAnalytics(LocalDate.now().minusDays(7), LocalDate.now());

        assertThat(result.totalRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("revenue field never appears on the partner-facing AnalyticsSummaryResponse " +
            "or WeeklyAnalyticsSummaryResponse types (Batch 13 #5/#6): gross revenue stays admin-only")
    void partnerFacingResponses_haveNoRevenueField() {
        for (RecordComponent c : AnalyticsSummaryResponse.class.getRecordComponents()) {
            assertThat(c.getName().toLowerCase()).doesNotContain("revenue");
        }
        for (RecordComponent c : WeeklyAnalyticsSummaryResponse.class.getRecordComponents()) {
            assertThat(c.getName().toLowerCase()).doesNotContain("revenue");
        }
    }

    // ── Gap 2: weekly (Mon-Sun) rollup ──────────────────────────────────────

    @Test
    @DisplayName("getContentAnalyticsWeekly reads from content_analytics_weekly and aggregates totals")
    void getContentAnalyticsWeekly_aggregatesFromWeeklyTable() {
        UUID contentId = UUID.randomUUID();
        Content content = new Content();
        content.setId(contentId);
        content.setCreatedBy(partnerId);

        ContentAnalyticsWeekly w1 = weeklyRow(contentId, LocalDate.of(2026, 7, 6), 100, 10, 5, 3600L);
        ContentAnalyticsWeekly w2 = weeklyRow(contentId, LocalDate.of(2026, 7, 13), 50, 5, 2, 1800L);

        when(contentRepo.findById(contentId)).thenReturn(java.util.Optional.of(content));
        when(weeklyRepo.findByContentIdAndWeekStartDateBetweenOrderByWeekStartDateAsc(any(), any(), any()))
                .thenReturn(List.of(w1, w2));

        WeeklyAnalyticsSummaryResponse result = service.getContentAnalyticsWeekly(
                contentId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), partnerId, false);

        assertThat(result.totalViews()).isEqualTo(150L);
        assertThat(result.totalCompletions()).isEqualTo(7L);
        assertThat(result.totalUniqueViewers()).isEqualTo(15L);
        assertThat(result.weekly()).hasSize(2);
        assertThat(result.weekly().get(0).weekStartDate()).isEqualTo(LocalDate.of(2026, 7, 6));
    }

    @Test
    @DisplayName("getContentAnalyticsWeekly enforces ownership — non-owner non-admin gets 404 (enumeration-safe)")
    void getContentAnalyticsWeekly_nonOwnerNonAdmin_throws404() {
        UUID contentId = UUID.randomUUID();
        Content content = new Content();
        content.setId(contentId);
        content.setCreatedBy(UUID.randomUUID()); // different owner

        when(contentRepo.findById(contentId)).thenReturn(java.util.Optional.of(content));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.tinniestudio.api.shared.exception.ResourceNotFoundException.class,
                () -> service.getContentAnalyticsWeekly(
                        contentId, LocalDate.now().minusWeeks(4), LocalDate.now(), partnerId, false));
    }

    // ── Gap 3: partner analytics batch-fetch (regression guard against N+1) ──

    @Test
    @DisplayName("getPartnerAnalytics fetches all of a partner's content analytics in one batch query, not one-per-content")
    void getPartnerAnalytics_usesSingleBatchQuery_notPerContentLoop() {
        UUID content1 = UUID.randomUUID();
        UUID content2 = UUID.randomUUID();
        Content c1 = new Content(); c1.setId(content1);
        Content c2 = new Content(); c2.setId(content2);
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);

        when(contentRepo.findByCreatedBy(partnerId)).thenReturn(List.of(c1, c2));
        when(dailyRepo.findByContentIdInAndAnalyticsDateBetweenOrderByAnalyticsDateAsc(
                List.of(content1, content2), from, to)).thenReturn(List.of());

        AnalyticsSummaryResponse result = service.getPartnerAnalytics(partnerId, from, to);

        assertThat(result).isNotNull();
        // The old N+1 implementation called the single-content-id repo method once per content;
        // that method must never be invoked from getPartnerAnalytics anymore.
        org.mockito.Mockito.verify(dailyRepo, org.mockito.Mockito.never())
                .findByContentIdAndAnalyticsDateBetweenOrderByAnalyticsDateAsc(any(), any(), any());
    }

    @Test
    @DisplayName("getPartnerAnalyticsWeekly fetches all of a partner's content analytics in one batch query, not one-per-content")
    void getPartnerAnalyticsWeekly_usesSingleBatchQuery_notPerContentLoop() {
        UUID content1 = UUID.randomUUID();
        Content c1 = new Content(); c1.setId(content1);
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);

        when(contentRepo.findByCreatedBy(partnerId)).thenReturn(List.of(c1));
        when(weeklyRepo.findByContentIdInAndWeekStartDateBetweenOrderByWeekStartDateAsc(
                List.of(content1), from, to)).thenReturn(List.of());

        WeeklyAnalyticsSummaryResponse result = service.getPartnerAnalyticsWeekly(partnerId, from, to);

        assertThat(result).isNotNull();
        org.mockito.Mockito.verify(weeklyRepo, org.mockito.Mockito.never())
                .findByContentIdAndWeekStartDateBetweenOrderByWeekStartDateAsc(any(), any(), any());
    }

    @Test
    @DisplayName("getPartnerAnalytics returns empty summary without querying analytics tables when partner has no content")
    void getPartnerAnalytics_noContent_returnsEmptyWithoutQuery() {
        when(contentRepo.findByCreatedBy(partnerId)).thenReturn(List.of());

        AnalyticsSummaryResponse result = service.getPartnerAnalytics(partnerId, LocalDate.now(), LocalDate.now());

        assertThat(result.totalViews()).isZero();
        org.mockito.Mockito.verifyNoInteractions(dailyRepo);
    }

    private ContentAnalyticsWeekly weeklyRow(UUID contentId, LocalDate weekStart,
                                              int views, int uniqueViewers, int completions, long watchTime) {
        ContentAnalyticsWeekly w = new ContentAnalyticsWeekly();
        w.setContentId(contentId);
        w.setWeekStartDate(weekStart);
        w.setViews(views);
        w.setUniqueViewers(uniqueViewers);
        w.setCompletions(completions);
        w.setWatchTimeSeconds(watchTime);
        return w;
    }
}
