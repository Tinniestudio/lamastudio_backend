package com.tinniestudio.api.modules.episode.controller;

import com.tinniestudio.api.modules.episode.dto.CreateEpisodeRequest;
import com.tinniestudio.api.modules.episode.dto.EpisodeResponse;
import com.tinniestudio.api.modules.episode.dto.ReorderEpisodesRequest;
import com.tinniestudio.api.modules.episode.dto.UpdateEpisodeRequest;
import com.tinniestudio.api.modules.episode.service.EpisodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Admin - Episodes", description = "Manage season episodes")
@RestController
@RequestMapping("/admin/seasons/{seasonId}/episodes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasRole('PARTNER')")
public class AdminEpisodeController {

    private final EpisodeService episodeService;

    @Operation(summary = "Create episode (auto-numbered if episodeNumber omitted)")
    @PostMapping
    public ResponseEntity<EpisodeResponse> create(
            @PathVariable UUID seasonId,
            @Valid @RequestBody CreateEpisodeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(episodeService.create(seasonId, req));
    }

    @Operation(summary = "Update episode metadata")
    @PatchMapping("/{id}")
    public ResponseEntity<EpisodeResponse> update(
            @PathVariable UUID seasonId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEpisodeRequest req) {
        return ResponseEntity.ok(episodeService.update(seasonId, id, req));
    }

    @Operation(summary = "Delete an episode")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> delete(@PathVariable UUID seasonId, @PathVariable UUID id) {
        episodeService.delete(seasonId, id);
        return ResponseEntity.ok(Map.of("message", "Episode deleted successfully"));
    }

    @Operation(summary = "Reorder episodes — provide episodeIds in desired 1..N order")
    @PatchMapping("/reorder")
    public ResponseEntity<Void> reorder(
            @PathVariable UUID seasonId,
            @Valid @RequestBody ReorderEpisodesRequest req) {
        episodeService.reorder(seasonId, req);
        return ResponseEntity.ok().build();
    }
}
