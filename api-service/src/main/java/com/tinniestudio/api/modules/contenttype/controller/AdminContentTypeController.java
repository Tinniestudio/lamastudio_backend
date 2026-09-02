package com.tinniestudio.api.modules.contenttype.controller;

import com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse;
import com.tinniestudio.api.modules.contenttype.dto.CreateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.dto.UpdateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.service.ContentTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Admin - Content Types", description = "Manage content types")
@RestController
@RequestMapping("/admin/content-types")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminContentTypeController {

    private final ContentTypeService contentTypeService;

    @Operation(summary = "List all content types including inactive")
    @GetMapping
    public ResponseEntity<List<ContentTypeResponse>> listAll() {
        return ResponseEntity.ok(contentTypeService.listAll());
    }

    @Operation(summary = "Create content type")
    @PostMapping
    public ResponseEntity<ContentTypeResponse> create(@RequestBody @Valid CreateContentTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contentTypeService.create(request));
    }

    @Operation(summary = "Update content type")
    @PatchMapping("/{id}")
    public ResponseEntity<ContentTypeResponse> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateContentTypeRequest request) {
        return ResponseEntity.ok(contentTypeService.update(id, request));
    }

    @Operation(summary = "Delete content type")
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable UUID id) {
        contentTypeService.delete(id);
        return ResponseEntity.ok(Map.of("message", "Content type deleted successfully"));
    }
}
