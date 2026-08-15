package com.tinniestudio.api.modules.library.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.library.dto.FavoriteResponse;
import com.tinniestudio.api.modules.library.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Favorites", description = "User favorites management")
@RestController
@RequestMapping("/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @Operation(summary = "List the authenticated user's favorites")
    @GetMapping
    public ResponseEntity<Page<FavoriteResponse>> list(
            @AuthenticationPrincipal UserDetails principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(favoriteService.list(CurrentUser.id(principal), pageable));
    }

    @Operation(summary = "Add a content item to favorites")
    @PostMapping("/{contentId}")
    public ResponseEntity<Void> add(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId) {
        favoriteService.add(CurrentUser.id(principal), contentId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "Remove a content item from favorites")
    @DeleteMapping("/{contentId}")
    public ResponseEntity<Object> remove(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID contentId) {
        favoriteService.remove(CurrentUser.id(principal), contentId);
        return ResponseEntity.ok(Map.of("message", "Removed from favorites successfully"));
    }
}
