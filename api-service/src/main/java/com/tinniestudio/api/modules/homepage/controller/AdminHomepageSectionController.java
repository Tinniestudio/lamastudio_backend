package com.tinniestudio.api.modules.homepage.controller;

import com.tinniestudio.api.modules.homepage.dto.CreateSectionRequest;
import com.tinniestudio.api.modules.homepage.dto.SectionResponse;
import com.tinniestudio.api.modules.homepage.dto.UpdateSectionRequest;
import com.tinniestudio.api.modules.homepage.service.HomepageSectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Admin - Homepage", description = "Manage homepage sections")
@RestController
@RequestMapping("/admin/homepage-sections")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminHomepageSectionController {

    private final HomepageSectionService service;

    @Operation(summary = "List all homepage sections including inactive")
    @GetMapping
    public ResponseEntity<List<SectionResponse>> listAll() {
        return ResponseEntity.ok(service.listAll());
    }

    @Operation(summary = "Create a new homepage section")
    @PostMapping
    public ResponseEntity<SectionResponse> create(@Valid @RequestBody CreateSectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @Operation(summary = "Update a homepage section")
    @PatchMapping("/{id}")
    public ResponseEntity<SectionResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSectionRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @Operation(summary = "Delete a homepage section")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
