package com.tinniestudio.api.modules.analytics.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.analytics.dto.AdminRevenueAnalyticsResponse;
import com.tinniestudio.api.modules.analytics.dto.AnalyticsSummaryResponse;
import com.tinniestudio.api.modules.analytics.dto.WeeklyAnalyticsSummaryResponse;
import com.tinniestudio.api.modules.analytics.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "Analytics")
@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Operation(summary = "Get content analytics")
    @GetMapping("/contents/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PARTNER')")
    public ResponseEntity<?> getContentAnalytics(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "json") String format,
            @AuthenticationPrincipal UserDetails principal) {

        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        UUID userId = CurrentUser.id(principal);
        boolean isAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        if ("csv".equalsIgnoreCase(format)) {
            String csv = analyticsService.exportContentAnalyticsCsv(id, effectiveFrom, effectiveTo, userId, isAdmin);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"analytics-" + id + ".csv\"")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(csv);
        }

        AnalyticsSummaryResponse data =
                analyticsService.getContentAnalytics(id, effectiveFrom, effectiveTo, userId, isAdmin);
        return ResponseEntity.ok(data);
    }

    @Operation(summary = "Get partner's own analytics across all content")
    @GetMapping("/partners/me")
    @PreAuthorize("hasRole('PARTNER') or hasRole('ADMIN')")
    public ResponseEntity<?> getPartnerAnalytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "json") String format,
            @AuthenticationPrincipal UserDetails principal) {

        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        UUID userId = CurrentUser.id(principal);

        if ("csv".equalsIgnoreCase(format)) {
            String csv = analyticsService.exportPartnerAnalyticsCsv(userId, effectiveFrom, effectiveTo);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"partner-analytics.csv\"")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(csv);
        }

        AnalyticsSummaryResponse data =
                analyticsService.getPartnerAnalytics(userId, effectiveFrom, effectiveTo);
        return ResponseEntity.ok(data);
    }

    @Operation(summary = "Get weekly (Mon-Sun) analytics for a content item")
    @GetMapping("/contents/{id}/weekly")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PARTNER')")
    public ResponseEntity<WeeklyAnalyticsSummaryResponse> getContentAnalyticsWeekly(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal UserDetails principal) {

        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusWeeks(12);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        UUID userId = CurrentUser.id(principal);
        boolean isAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        return ResponseEntity.ok(
                analyticsService.getContentAnalyticsWeekly(id, effectiveFrom, effectiveTo, userId, isAdmin));
    }

    @Operation(summary = "Get partner's own weekly (Mon-Sun) analytics across all content")
    @GetMapping("/partners/me/weekly")
    @PreAuthorize("hasRole('PARTNER') or hasRole('ADMIN')")
    public ResponseEntity<WeeklyAnalyticsSummaryResponse> getPartnerAnalyticsWeekly(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal UserDetails principal) {

        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusWeeks(12);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        UUID userId = CurrentUser.id(principal);

        return ResponseEntity.ok(analyticsService.getPartnerAnalyticsWeekly(userId, effectiveFrom, effectiveTo));
    }

    @Operation(summary = "Get platform-wide revenue analytics (strictly admin-only, Batch 13 #5/#6)")
    @GetMapping("/admin/revenue")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminRevenueAnalyticsResponse> getAdminRevenueAnalytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();

        return ResponseEntity.ok(analyticsService.getAdminRevenueAnalytics(effectiveFrom, effectiveTo));
    }
}
