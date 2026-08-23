package com.tinniestudio.api.modules.upload.controller;

import com.tinniestudio.api.modules.upload.dto.PartnerSubtitleResponse;
import com.tinniestudio.api.modules.upload.service.SubtitleService;
import com.tinniestudio.api.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Partner Subtitles", description = "Self-service list/delete for a video's subtitles")
@RestController
@RequestMapping("/partners/videos/{videoAssetId}/subtitles")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PARTNER')")
public class SubtitleController {

    private final SubtitleService subtitleService;

    @Operation(summary = "List subtitles attached to one of the caller's own videos")
    @GetMapping
    public ResponseEntity<List<PartnerSubtitleResponse>> list(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID videoAssetId) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(subtitleService.list(userId, videoAssetId));
    }

    @Operation(summary = "Delete one of the caller's own subtitles")
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID videoAssetId,
            @PathVariable UUID id) {
        UUID userId = CurrentUser.id(principal);
        subtitleService.delete(userId, videoAssetId, id);
        return ResponseEntity.ok(Map.of("message", "Subtitle deleted"));
    }
}
