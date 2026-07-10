package com.tinniestudio.api.modules.season.controller;

import com.tinniestudio.api.modules.season.dto.SeasonResponse;
import com.tinniestudio.api.modules.season.service.SeasonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Seasons", description = "Browse series seasons")
@RestController
@RequestMapping("/contents/{contentId}/seasons")
@RequiredArgsConstructor
public class SeasonController {

    private final SeasonService seasonService;

    @Operation(summary = "List all seasons for a series in season order")
    @GetMapping
    public ResponseEntity<List<SeasonResponse>> list(@PathVariable UUID contentId) {
        return ResponseEntity.ok(seasonService.listByContent(contentId));
    }

    @Operation(summary = "Get a specific season")
    @GetMapping("/{id}")
    public ResponseEntity<SeasonResponse> get(@PathVariable UUID contentId, @PathVariable UUID id) {
        return ResponseEntity.ok(seasonService.getById(id));
    }
}
