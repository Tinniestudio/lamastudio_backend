package com.tinniestudio.api.modules.analytics.controller;

import com.tinniestudio.api.modules.analytics.dto.AnalyticsSummaryResponse;
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
        UUID userId = UUID.fromString(principal.getUsername());
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
        UUID userId = UUID.fromString(principal.getUsername());

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
}
