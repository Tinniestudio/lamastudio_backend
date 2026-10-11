package com.tinniestudio.api.modules.playback.controller;

import com.tinniestudio.api.shared.storage.CloudFrontSigningService;
import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.playback.dto.*;
import com.tinniestudio.api.modules.playback.service.PlaybackService;
import jakarta.servlet.http.HttpServletResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.services.cloudfront.cookie.CookiesForCustomPolicy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Playback", description = "Playback access, manifests, and progress tracking")
@RestController
@RequestMapping("/playback")
@RequiredArgsConstructor
public class PlaybackController {

    private final PlaybackService playbackService;
    private final CloudFrontSigningService cloudFrontSigningService;

    @Operation(summary = "Check if the authenticated user has access to a content item")
    @GetMapping("/access/{contentId}")
    public ResponseEntity<AccessCheckResponse> checkAccess(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId) {
        return ResponseEntity.ok(playbackService.checkAccess(principal, contentId));
    }

    @Operation(summary = "Get HLS manifest for a movie or standalone content")
    @GetMapping("/manifest/content/{contentId}")
    public ResponseEntity<PlaybackManifestResponse> getContentManifest(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId,
            HttpServletResponse response) {
        PlaybackManifestResponse manifest = playbackService.getContentManifest(principal, contentId);
        applySignedCookiesIfEnabled(response);
        return ResponseEntity.ok(manifest);
    }

    @Operation(summary = "Get HLS manifest for a content item's trailer — public, no auth or subscription required")
    @GetMapping("/manifest/content/{contentId}/trailer")
    public ResponseEntity<PlaybackManifestResponse> getTrailerManifest(@PathVariable UUID contentId) {
        return ResponseEntity.ok(playbackService.getTrailerManifest(contentId));
    }

    @Operation(summary = "Get HLS manifest for a season's trailer — public, no auth or subscription required")
    @GetMapping("/manifest/season/{seasonId}/trailer")
    public ResponseEntity<PlaybackManifestResponse> getSeasonTrailerManifest(@PathVariable UUID seasonId) {
        return ResponseEntity.ok(playbackService.getSeasonTrailerManifest(seasonId));
    }

    @Operation(summary = "Get HLS manifest for an episode's trailer — public, no auth or subscription required")
    @GetMapping("/manifest/episode/{episodeId}/trailer")
    public ResponseEntity<PlaybackManifestResponse> getEpisodeTrailerManifest(@PathVariable UUID episodeId) {
        return ResponseEntity.ok(playbackService.getEpisodeTrailerManifest(episodeId));
    }

    @Operation(summary = "Get HLS manifest for a specific episode")
    @GetMapping("/manifest/episode/{episodeId}")
    public ResponseEntity<PlaybackManifestResponse> getEpisodeManifest(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID episodeId,
            HttpServletResponse response) {
        PlaybackManifestResponse manifest = playbackService.getEpisodeManifest(principal, episodeId);
        applySignedCookiesIfEnabled(response);
        return ResponseEntity.ok(manifest);
    }

    @Operation(summary = "Record or update watch progress for a content item or episode")
    @PostMapping("/progress")
    public ResponseEntity<Object> recordProgress(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody ProgressRequest request) {
        playbackService.recordProgress(CurrentUser.id(principal), request);
        return ResponseEntity.ok(Map.of("message", "Progress recorded successfully"));
    }

    @Operation(summary = "Get the authenticated user's continue-watching list")
    @GetMapping("/continue-watching")
    public ResponseEntity<List<ContinueWatchingItem>> getContinueWatching(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(playbackService.getContinueWatching(CurrentUser.id(principal)));
    }

    private void applySignedCookiesIfEnabled(HttpServletResponse response) {
        cloudFrontSigningService.signVideoAccess().ifPresent(cookies -> applyCloudFrontCookies(response, cookies));
    }

    private void applyCloudFrontCookies(HttpServletResponse response, CookiesForCustomPolicy cookies) {
        String attributes = cookieAttributes();
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.policyHeaderValue() + attributes);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.signatureHeaderValue() + attributes);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.keyPairIdHeaderValue() + attributes);
    }

    private String cookieAttributes() {
        StringBuilder sb = new StringBuilder("; Path=/; Max-Age=")
            .append(cloudFrontSigningService.cookieMaxAgeSeconds())
            .append("; Secure; HttpOnly; SameSite=Lax");
        String domain = cloudFrontSigningService.cookieDomain();
        if (domain != null && !domain.isBlank()) {
            sb.append("; Domain=").append(domain);
        }
        return sb.toString();
    }
}
