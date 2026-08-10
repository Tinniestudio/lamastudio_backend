package com.tinniestudio.api.modules.library.controller;

import com.tinniestudio.api.modules.library.dto.WatchHistoryResponse;
import com.tinniestudio.api.modules.library.service.WatchHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Watch History", description = "User watch history")
@RestController
@RequestMapping("/history")
@RequiredArgsConstructor
public class HistoryController {

    private final WatchHistoryService historyService;

    @Operation(summary = "List the authenticated user's watch history")
    @GetMapping
    public ResponseEntity<Page<WatchHistoryResponse>> list(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(historyService.list(userId(principal), pageable));
    }

    @Operation(summary = "Delete a single watch history entry")
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID id) {
        historyService.delete(userId(principal), id);
        return ResponseEntity.ok(Map.of("message", "Watch history entry deleted successfully"));
    }

    @Operation(summary = "Clear all watch history for the authenticated user")
    @DeleteMapping
    public ResponseEntity<Object> deleteAll(
            @AuthenticationPrincipal UserDetails principal) {
        historyService.deleteAll(userId(principal));
        return ResponseEntity.ok(Map.of("message", "Watch history cleared successfully"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID userId(UserDetails principal) {
        if (principal == null) throw new AuthenticationCredentialsNotFoundException("No credentials");
        return UUID.fromString(principal.getUsername());
    }
}
