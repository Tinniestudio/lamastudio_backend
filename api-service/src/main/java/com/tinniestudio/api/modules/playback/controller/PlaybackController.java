package com.tinniestudio.api.modules.playback.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.playback.dto.*;
import com.tinniestudio.api.modules.playback.service.PlaybackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
            @PathVariable UUID contentId) {
        return ResponseEntity.ok(playbackService.getContentManifest(principal, contentId));
    }

    @Operation(summary = "Get HLS manifest for a specific episode")
    @GetMapping("/manifest/episode/{episodeId}")
    public ResponseEntity<PlaybackManifestResponse> getEpisodeManifest(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID episodeId) {
        return ResponseEntity.ok(playbackService.getEpisodeManifest(principal, episodeId));
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
}
