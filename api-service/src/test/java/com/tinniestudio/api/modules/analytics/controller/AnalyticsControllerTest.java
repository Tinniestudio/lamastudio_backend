package com.tinniestudio.api.modules.analytics.controller;

import com.tinniestudio.api.modules.analytics.dto.AdminRevenueAnalyticsResponse;
import com.tinniestudio.api.modules.analytics.dto.AnalyticsSummaryResponse;
import com.tinniestudio.api.modules.analytics.dto.WeeklyAnalyticsSummaryResponse;
import com.tinniestudio.api.modules.analytics.service.AnalyticsService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AnalyticsController.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsControllerTest {

    static final String USER_ID = "00000000-0000-0000-0000-000000000003";

    @Autowired MockMvc mockMvc;
    @MockBean AnalyticsService analyticsService;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    AnalyticsSummaryResponse sampleSummary() {
        return new AnalyticsSummaryResponse(100L, 50L, 80L, BigDecimal.valueOf(120.50), List.of());
    }

    @Test
    @WithMockUser(username = USER_ID, roles = "ADMIN")
    void getContentAnalytics_admin_returns200() throws Exception {
        when(analyticsService.getContentAnalytics(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(sampleSummary());
        mockMvc.perform(get("/analytics/contents/{id}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalViews").value(100));
    }

    @Test
    @WithMockUser(username = USER_ID, roles = "PARTNER")
    void getContentAnalytics_partner_returns200() throws Exception {
        when(analyticsService.getContentAnalytics(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(sampleSummary());
        mockMvc.perform(get("/analytics/contents/{id}", UUID.randomUUID()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = USER_ID, roles = "ADMIN")
    void getContentAnalytics_csvFormat_returnsTextCsv() throws Exception {
        when(analyticsService.exportContentAnalyticsCsv(any(), any(), any(), any(), anyBoolean()))
                .thenReturn("content_id,analytics_date,views\n");
        mockMvc.perform(get("/analytics/contents/{id}", UUID.randomUUID())
                        .param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")));
    }

    @Test
    @WithMockUser(username = USER_ID, roles = "PARTNER")
    void getPartnerAnalytics_returns200() throws Exception {
        when(analyticsService.getPartnerAnalytics(any(), any(), any()))
                .thenReturn(sampleSummary());
        mockMvc.perform(get("/analytics/partners/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCompletions").value(50));
    }

    @Test
    @WithMockUser(username = USER_ID, roles = "PARTNER")
    void getPartnerAnalytics_csvFormat_returnsTextCsv() throws Exception {
        when(analyticsService.exportPartnerAnalyticsCsv(any(), any(), any()))
                .thenReturn("content_id,analytics_date,views\n");
        mockMvc.perform(get("/analytics/partners/me").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")));
    }

    // ── Gap 1: admin-only revenue ──────────────────────────────────────────

    @Test
    @WithMockUser(username = USER_ID, roles = "ADMIN")
    void getAdminRevenueAnalytics_admin_returns200WithRevenue() throws Exception {
        when(analyticsService.getAdminRevenueAnalytics(any(), any()))
                .thenReturn(new AdminRevenueAnalyticsResponse(
                        LocalDate.now().minusDays(30), LocalDate.now(), BigDecimal.valueOf(1234.56), 7L));

        mockMvc.perform(get("/analytics/admin/revenue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRevenue").value(1234.56))
                .andExpect(jsonPath("$.data.successfulPaymentCount").value(7));
    }

    @Test
    @DisplayName("getAdminRevenueAnalytics is annotated strictly ADMIN-only — not ADMIN-or-PARTNER " +
            "like every other endpoint in this controller (Batch 13 #5/#6 enforcement point). " +
            "This is a structural check on the security annotation itself: @WebMvcTest doesn't load " +
            "SecurityConfig's @EnableMethodSecurity, so @PreAuthorize isn't actually evaluated in this " +
            "slice — an end-to-end 403 assertion here would silently pass regardless of role.")
    void getAdminRevenueAnalytics_isAnnotatedAdminOnly() throws Exception {
        var method = AnalyticsController.class.getMethod(
                "getAdminRevenueAnalytics", java.time.LocalDate.class, java.time.LocalDate.class);
        var preAuthorize = method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);

        org.assertj.core.api.Assertions.assertThat(preAuthorize).isNotNull();
        org.assertj.core.api.Assertions.assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
    }

    // ── Gap 2: weekly (Mon-Sun) rollup ──────────────────────────────────────

    @Test
    @WithMockUser(username = USER_ID, roles = "ADMIN")
    void getContentAnalyticsWeekly_admin_returns200() throws Exception {
        when(analyticsService.getContentAnalyticsWeekly(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new WeeklyAnalyticsSummaryResponse(100L, 50L, 80L, BigDecimal.valueOf(120.50), List.of()));

        mockMvc.perform(get("/analytics/contents/{id}/weekly", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalViews").value(100));
    }

    @Test
    @WithMockUser(username = USER_ID, roles = "PARTNER")
    void getPartnerAnalyticsWeekly_returns200() throws Exception {
        when(analyticsService.getPartnerAnalyticsWeekly(any(), any(), any()))
                .thenReturn(new WeeklyAnalyticsSummaryResponse(10L, 5L, 8L, BigDecimal.ZERO, List.of()));

        mockMvc.perform(get("/analytics/partners/me/weekly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalViews").value(10));
    }
}
