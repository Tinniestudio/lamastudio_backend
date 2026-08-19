package com.tinniestudio.api.modules.upload.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.upload.dto.PartnerVideoAssetResponse;
import com.tinniestudio.api.modules.upload.service.PartnerVideoService;
import com.tinniestudio.api.shared.entity.DomainEnums.TargetEntityType;
import com.tinniestudio.api.shared.entity.DomainEnums.VideoAssetType;
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

@Tag(name = "Partner Videos", description = "A partner's own video history per content/season/episode target, and manually setting which one is active")
@RestController
@RequestMapping("/partners/videos")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PARTNER')")
public class PartnerVideoController {

    private final PartnerVideoService partnerVideoService;

    @Operation(summary = "List own video uploads for a content/season/episode target, most recent first")
    @GetMapping
    public ResponseEntity<List<PartnerVideoAssetResponse>> list(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam TargetEntityType targetEntityType,
            @RequestParam UUID targetEntityId,
            @RequestParam(defaultValue = "MAIN_VIDEO") VideoAssetType assetType) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(partnerVideoService.listForTarget(userId, targetEntityType, targetEntityId, assetType));
    }

    @Operation(summary = "Set a READY video as the active one for its target, retiring any previously-active sibling")
    @PatchMapping("/{id}/activate")
    public ResponseEntity<Object> activate(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id) {
        UUID userId = CurrentUser.id(principal);
        partnerVideoService.activate(userId, id);
        return ResponseEntity.ok(Map.of("message", "Video activated"));
    }
}
