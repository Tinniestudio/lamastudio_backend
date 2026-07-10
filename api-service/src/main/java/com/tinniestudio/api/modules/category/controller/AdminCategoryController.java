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

    @Operation(summary = "Create category with optional poster image")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CategoryResponse> create(
            @RequestPart("request") @Valid CreateCategoryRequest request,
            @RequestPart(value = "poster", required = false) MultipartFile poster) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request, poster));
    }

    @Operation(summary = "Update category with optional new poster")
    @PatchMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CategoryResponse> update(
            @PathVariable UUID id,
            @RequestPart("request") @Valid UpdateCategoryRequest request,
            @RequestPart(value = "poster", required = false) MultipartFile poster) {
        return ResponseEntity.ok(categoryService.update(id, request, poster));
    }

    @Operation(summary = "Delete category")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
