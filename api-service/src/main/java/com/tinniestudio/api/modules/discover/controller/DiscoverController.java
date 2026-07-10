package com.tinniestudio.api.modules.discover.controller;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.discover.dto.HomeSectionDto;
import com.tinniestudio.api.modules.discover.dto.HomeResponse;
import com.tinniestudio.api.modules.discover.service.DiscoverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Discover", description = "Discovery and curated content feeds")
@RestController
@RequestMapping("/discover")
@RequiredArgsConstructor
public class DiscoverController {

    private final DiscoverService discoverService;

    @Operation(summary = "Homepage — all active sections with their content")
    @GetMapping("/home")
    public ResponseEntity<HomeResponse> home() {
        return ResponseEntity.ok(new HomeResponse(discoverService.home()));
    }

    @Operation(summary = "Trending content (highest view count)")
    @GetMapping("/trending")
    public ResponseEntity<List<ContentSummaryResponse>> trending(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(discoverService.trending(Math.min(limit, 50)));
    }

    @Operation(summary = "Featured content")
    @GetMapping("/featured")
    public ResponseEntity<List<ContentSummaryResponse>> featured(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(discoverService.featured(Math.min(limit, 50)));
    }

    @Operation(summary = "New releases (most recently published)")
    @GetMapping("/new-releases")
    public ResponseEntity<List<ContentSummaryResponse>> newReleases(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(discoverService.newReleases(Math.min(limit, 50)));
    }

    @Operation(summary = "Coming soon content")
    @GetMapping("/coming-soon")
    public ResponseEntity<List<ContentSummaryResponse>> comingSoon(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(discoverService.comingSoon(Math.min(limit, 50)));
    }

    @Operation(summary = "Content by category slug")
    @GetMapping("/category/{slug}")
    public ResponseEntity<List<ContentSummaryResponse>> byCategory(
            @PathVariable String slug,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(discoverService.byCategory(slug, Math.min(limit, 50)));
    }
}
