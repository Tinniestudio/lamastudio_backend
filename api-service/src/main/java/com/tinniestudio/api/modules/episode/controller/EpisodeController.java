package com.tinniestudio.api.modules.episode.controller;

import com.tinniestudio.api.modules.episode.dto.EpisodeResponse;
import com.tinniestudio.api.modules.episode.service.EpisodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Episodes", description = "Browse season episodes")
@RestController
@RequestMapping("/seasons/{seasonId}/episodes")
@RequiredArgsConstructor
public class EpisodeController {

    private final EpisodeService episodeService;

    @Operation(summary = "List all episodes for a season in episode order")
    @GetMapping
    public ResponseEntity<List<EpisodeResponse>> list(@PathVariable UUID seasonId) {
        return ResponseEntity.ok(episodeService.listBySeason(seasonId));
    }

    @Operation(summary = "Get a specific episode")
    @GetMapping("/{id}")
    public ResponseEntity<EpisodeResponse> get(@PathVariable UUID seasonId, @PathVariable UUID id) {
        return ResponseEntity.ok(episodeService.getById(seasonId, id));
    }
}
