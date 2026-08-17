package com.tinniestudio.api.modules.season.controller;

import com.tinniestudio.api.modules.season.dto.CreateSeasonRequest;
import com.tinniestudio.api.modules.season.dto.SeasonResponse;
import com.tinniestudio.api.modules.season.dto.UpdateSeasonRequest;
import com.tinniestudio.api.modules.season.service.SeasonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Admin - Seasons", description = "Manage series seasons")
@RestController
@RequestMapping("/admin/contents/{contentId}/seasons")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasRole('PARTNER')")
public class AdminSeasonController {

    private final SeasonService seasonService;

    @Operation(summary = "Create a season (auto-numbered if seasonNumber omitted)")
    @PostMapping
    public ResponseEntity<SeasonResponse> create(
            @PathVariable UUID contentId,
            @RequestBody CreateSeasonRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(seasonService.create(contentId, req, principal));
    }

    @Operation(summary = "Update season metadata")
    @PatchMapping("/{id}")
    public ResponseEntity<SeasonResponse> update(
            @PathVariable UUID contentId,
            @PathVariable UUID id,
            @RequestBody UpdateSeasonRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(seasonService.update(contentId, id, req, principal));
    }

    @Operation(summary = "Delete a season and all its episodes")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> delete(@PathVariable UUID contentId, @PathVariable UUID id) {
        seasonService.delete(contentId, id);
        return ResponseEntity.ok(Map.of("message", "Season deleted successfully"));
    }
}
