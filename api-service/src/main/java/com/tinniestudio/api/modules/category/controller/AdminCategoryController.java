package com.tinniestudio.api.modules.category.controller;

import com.tinniestudio.api.modules.category.dto.CategoryResponse;
import com.tinniestudio.api.modules.category.dto.CreateCategoryRequest;
import com.tinniestudio.api.modules.category.dto.UpdateCategoryRequest;
import com.tinniestudio.api.modules.category.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Admin - Categories", description = "Manage categories")
@RestController
@RequestMapping("/admin/categories")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "List all categories including inactive")
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> listAll() {
        return ResponseEntity.ok(categoryService.listAll());
    }

    // Two @PostMapping/@PatchMapping overloads differentiated by `consumes`: plain JSON for the
    // common case (no poster, or poster already uploaded separately via the presigned-upload
    // flow used everywhere else in the app), and multipart for uploading a poster directly in
    // the same request. Previously ONLY multipart was accepted, so any plain JSON create/update
    // request 415'd — a basic "create a category" call had no way to succeed without also
    // sending a file part.
    @Operation(summary = "Create category (JSON body, no poster)")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CategoryResponse> create(@RequestBody @Valid CreateCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request, null));
    }

    @Operation(summary = "Create category with poster image (multipart)")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CategoryResponse> createWithPoster(
            @RequestPart("request") @Valid CreateCategoryRequest request,
            @RequestPart(value = "poster", required = false) MultipartFile poster) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request, poster));
    }

    @Operation(summary = "Update category (JSON body, no poster change)")
    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CategoryResponse> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateCategoryRequest request) {
        return ResponseEntity.ok(categoryService.update(id, request, null));
    }

    @Operation(summary = "Update category with new poster image (multipart)")
    @PatchMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CategoryResponse> updateWithPoster(
            @PathVariable UUID id,
            @RequestPart("request") @Valid UpdateCategoryRequest request,
            @RequestPart(value = "poster", required = false) MultipartFile poster) {
        return ResponseEntity.ok(categoryService.update(id, request, poster));
    }

    @Operation(summary = "Delete category")
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable UUID id) {
        categoryService.delete(id);
        return ResponseEntity.ok(Map.of("message", "Category deleted successfully"));
    }
}
